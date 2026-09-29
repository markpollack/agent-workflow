package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.batch.examples.SequentialRecoveryExample;
import static org.assertj.core.api.Assertions.*;

class SequentialExampleIT {
    @Test void suppliedConstructorDependencyAndSavedProgressSurviveKilledProcess() throws Exception {
        Path directory=Path.of("target/durable-evidence/sequential-example",UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(directory);Process first=launch(directory,"pause-after-first");
        try {
            long limit=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!Files.exists(directory.resolve("ready.txt"))) {
                if(!first.isAlive()||System.nanoTime()>limit) throw new AssertionError(Files.readString(directory.resolve("pause-after-first.log")));
                Thread.sleep(10);
            }
            String[] marker=Files.readString(directory.resolve("ready.txt")).split(" ");
            assertThat(Long.parseLong(marker[0])).isEqualTo(first.pid());assertThat(marker[2]).contains("receipt");
            // An independently launched JVM must refuse ownership while the original process is alive.
            Process duplicate=launch(directory,"run");
            try {assertThat(duplicate.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(duplicate.exitValue()).isNotZero();}
            finally {if(duplicate.isAlive())duplicate.destroyForcibly();}
            assertThat(Files.readString(directory.resolve("run.log"))).contains("another runtime owns this database");
            first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(first.exitValue()).isNotZero();
            Process recovered=launch(directory,"recover");
            try {assertThat(recovered.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(recovered.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log"))).isZero();}
            finally {if(recovered.isAlive())recovered.destroyForcibly();}
            assertThat(recovered.pid()).isNotEqualTo(first.pid());
            assertThat(Files.readAllLines(directory.resolve("greet.calls"))).hasSize(1);
            String log=Files.readString(directory.resolve("recover.log"));
            assertThat(log).contains("node="+marker[2]+" status=ACTIVE","STEP receipt thread=main run="+marker[1],"Receipt: Hello, Ada").doesNotContain("STEP greet");
            Files.writeString(directory.resolve("receipt.json"),new ObjectMapper().writeValueAsString(Map.of(
                    "killedPid",first.pid(),"exit",first.exitValue(),"recoveredPid",recovered.pid(),"runId",marker[1],
                    "duplicatePid",duplicate.pid(),"duplicateExit",duplicate.exitValue(),"method","Process.destroyForcibly")));
        } finally {if(first.isAlive())first.destroyForcibly();}
    }
    private static Process launch(Path directory,String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                SequentialRecoveryExample.class.getName(),directory.toString(),mode)
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
    }
}
