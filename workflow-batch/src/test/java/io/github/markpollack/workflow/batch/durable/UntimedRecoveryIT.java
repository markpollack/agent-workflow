package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class UntimedRecoveryIT {
    @Test void killedUntimedProcessReusesCommittedResultAfterTenDays() throws Exception {
        Path directory = Path.of("target/durable-evidence/untimed", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(directory);
        Process first = launch(directory, "crash");
        try {
            long stop = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(directory.resolve("kill-point.txt"))) {
                if (!first.isAlive() || System.nanoTime() >= stop)
                    throw new AssertionError(Files.readString(directory.resolve("crash.log")));
                Thread.sleep(10);
            }
            first.destroyForcibly();
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).isNotZero();
            Files.writeString(directory.resolve("kill-receipt.txt"), "pid=" + first.pid() + ", exit=" + first.exitValue() + ", method=Process.destroyForcibly\n");
            Process recovery = launch(directory, "recover");
            try {
                assertThat(recovery.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(recovery.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log"))).isZero();
            } finally { if (recovery.isAlive()) recovery.destroyForcibly(); }
            assertThat(Files.readAllLines(directory.resolve("calls.txt"))).hasSize(2);
        } finally { if (first.isAlive()) first.destroyForcibly(); }
    }
    private static Process launch(Path directory, String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                UntimedProcessWorker.class.getName(), directory.toString(), mode, "true")
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode + ".log").toFile()).start();
    }
}
