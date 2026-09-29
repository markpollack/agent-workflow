package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;

class ProcessRecoveryIT {
    @ParameterizedTest(name="kill {0}, occurrence {1}")
    @CsvSource({
        "BEFORE_ADMISSION_COMMIT,1,0,0,1,1",
        "AFTER_ADMISSION_COMMIT,1,1,0,1,1",
        "BEFORE_DISPATCH_COMMIT,1,1,0,1,1",
        "AFTER_DISPATCH_COMMIT,1,1,1,1,1",
        "AFTER_HANDLER_RETURN,1,1,1,2,1",
        "BEFORE_RESULT_COMMIT,1,1,1,2,1",
        "AFTER_RESULT_COMMIT,1,1,1,1,1",
        "AFTER_DISPATCH_COMMIT,2,1,2,1,1",
        "AFTER_HANDLER_RETURN,2,1,2,1,2",
        "AFTER_RESULT_COMMIT,2,1,2,1,1",
        "BEFORE_TERMINAL_COMMIT,1,1,5,1,1",
        "AFTER_RESULT_COMMIT,5,0,5,1,1"
    })
    void killedJvmRecoversExactFacts(String boundary,int occurrence,int discovered,int invocations,int firstCalls,int secondCalls) throws Exception {
        Path directory=Path.of("target/durable-evidence/process",boundary+"-"+occurrence+"-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(directory);
        ObjectMapper mapper=new ObjectMapper();
        Process worker=launch(directory,"crash",boundary,occurrence);
        try {
            Path marker=directory.resolve("kill-point.json");long timeout=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!Files.exists(marker)) {
                if(!worker.isAlive()) throw new AssertionError(Files.readString(directory.resolve("crash.log")));
                if(System.nanoTime()>timeout) throw new AssertionError("kill marker timeout: "+Files.readString(directory.resolve("crash.log")));
                Thread.sleep(10);
            }
            JsonNode point=mapper.readTree(Files.readString(marker));assertThat(point.path("pid").asLong()).isEqualTo(worker.pid());
            worker.destroyForcibly();assertThat(worker.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(worker.exitValue()).isNotZero();
            Files.writeString(directory.resolve("kill-receipt.json"),mapper.writeValueAsString(Map.of("pid",worker.pid(),"exit",worker.exitValue(),"method","Process.destroyForcibly","boundary",boundary,"occurrence",occurrence)));
            Process recovery=launch(directory,"recover",boundary,occurrence);
            try {
                assertThat(recovery.waitFor(40,TimeUnit.SECONDS)).withFailMessage("recovery timeout: %s",directory).isTrue();
                assertThat(recovery.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log"))).isZero();
            } finally {if(recovery.isAlive())recovery.destroyForcibly();}
            assertThat(recovery.pid()).isNotEqualTo(worker.pid());
            JsonNode discovery=mapper.readTree(Files.readString(directory.resolve("recover-discovery.json")));
            assertThat(discovery.path("runs").size()).isEqualTo(discovered);
            JsonNode before=mapper.readTree(Files.readString(directory.resolve("recover-before.json")));
            JsonNode after=mapper.readTree(Files.readString(directory.resolve("recover-after.json")));
            assertThat(before.path("invocations").size()).isEqualTo(invocations);
            assertThat(after.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(after.path("node").asText()).isEqualTo(KernelFixtures.o01(KernelFixtures.deployment(Map.of()),true).terminal().name());
            assertThat(after.path("deadline")).isEqualTo(before.path("deadline"));
            assertThat(Files.readAllLines(directory.resolve("first.calls"))).hasSize(firstCalls);
            assertThat(Files.readAllLines(directory.resolve("second.calls"))).hasSize(secondCalls);
            assertThat(Files.readAllLines(directory.resolve("fifth.calls"))).hasSize(boundary.equals("BEFORE_TERMINAL_COMMIT") ? 2 : 1).allMatch(line->line.endsWith("first:original/original"));
            String secondInput=after.path("invocations").get(1).path("input").asText();
            String fifthInput=after.path("invocations").get(4).path("input").asText();
            assertThat(after.path("values").path(fifthInput).path("components").get(1).asText()).isEqualTo(secondInput);
            if(occurrence==2) assertThat(after.path("values").path(secondInput)).isEqualTo(before.path("values").path(secondInput));
            if(boundary.equals("AFTER_RESULT_COMMIT")) {
                for(int i=0;i<occurrence;i++) {
                    var call=before.path("invocations").get(i);assertThat(call.path("status").asText()).isEqualTo("COMMITTED");
                    assertThat(after.path("invocations").get(i)).isEqualTo(call);
                }
            }
            Set<String> deliveries=new HashSet<>();
            for(JsonNode call:after.path("invocations")) for(JsonNode delivery:call.path("attempts")) assertThat(deliveries.add(delivery.path("id").asText())).isTrue();
            long expectedSequence=1;for(JsonNode event:after.path("events")) assertThat(event.path("sequence").asLong()).isEqualTo(expectedSequence++);
            assertThat(Files.readString(directory.resolve("recover-result.json"))).contains("first:original/original/changed-first");
        } finally {if(worker.isAlive())worker.destroyForcibly();}
    }
    @ParameterizedTest(name="different build refuses {0} run in another JVM")
    @org.junit.jupiter.params.provider.ValueSource(strings={"admit","complete"})
    void changedDeploymentRefusesActiveAndTerminalRunsInSeparateJvm(String mode) throws Exception {
        Path directory=Path.of("target/durable-evidence/process-mismatch",mode+"-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(directory);
        ObjectMapper mapper=new ObjectMapper();Process original=launch(directory,mode,"none",0);
        try {assertThat(original.waitFor(40,TimeUnit.SECONDS)).isTrue();assertThat(original.exitValue()).withFailMessage(Files.readString(directory.resolve(mode+".log"))).isZero();}
        finally {if(original.isAlive()) original.destroyForcibly();}
        Map<String,List<String>> counters=new TreeMap<>();
        try(var files=Files.list(directory)) {for(Path file:files.filter(p->p.toString().endsWith(".calls")).toList()) counters.put(file.getFileName().toString(),Files.readAllLines(file));}
        Process mismatch=launch(directory,"mismatch","none",0);
        try {assertThat(mismatch.waitFor(40,TimeUnit.SECONDS)).isTrue();assertThat(mismatch.exitValue()).withFailMessage(Files.readString(directory.resolve("mismatch.log"))).isZero();}
        finally {if(mismatch.isAlive()) mismatch.destroyForcibly();}
        assertThat(mismatch.pid()).isNotEqualTo(original.pid());
        assertThat(Files.readString(directory.resolve("mismatch-after.json"))).isEqualTo(Files.readString(directory.resolve("mismatch-before.json")));
        var result=mapper.readTree(Files.readString(directory.resolve("mismatch-result.json")));
        for(String action:List.of("start","resume","result")) assertThat(result.path("refusals").path(action).asText()).isEqualTo("COMPATIBILITY");
        Map<String,List<String>> after=new TreeMap<>();
        try(var files=Files.list(directory)) {for(Path file:files.filter(p->p.toString().endsWith(".calls")).toList()) after.put(file.getFileName().toString(),Files.readAllLines(file));}
        assertThat(after).isEqualTo(counters);
        Files.writeString(directory.resolve("process-receipt.json"),mapper.writeValueAsString(Map.of("originalPid",original.pid(),"mismatchPid",mismatch.pid(),"mode",mode)));
    }
    private static Process launch(Path directory,String mode,String boundary,int occurrence) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(java,"-cp",classpath,ProcessWorker.class.getName(),directory.toString(),mode,boundary,Integer.toString(occurrence))
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
    }
}
