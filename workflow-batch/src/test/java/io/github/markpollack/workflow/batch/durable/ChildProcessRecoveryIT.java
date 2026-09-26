package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class ChildProcessRecoveryIT {
    @ParameterizedTest(name="child kill {0}")
    @CsvSource({
        "BEFORE_CHILD_RESERVATION,1,1",
        "AFTER_CHILD_CREATE_BEFORE_COMMIT,1,1",
        "BEFORE_CHILD_ACCEPT_COMMIT,1,1",
        "AFTER_CHILD_ACCEPT_COMMIT,2,1",
        "BEFORE_DISPATCH_COMMIT,2,1",
        "AFTER_DISPATCH_COMMIT,2,1",
        "AFTER_HANDLER_RETURN,2,2",
        "BEFORE_RESULT_COMMIT,2,2",
        "AFTER_RESULT_COMMIT,2,1",
        "BEFORE_CHILD_SETTLEMENT_COMMIT,2,1",
        "AFTER_CHILD_SETTLEMENT_COMMIT,2,1"
    })
    void killedJvmRecoversChildWithoutDuplicateAcceptanceOrSettlement(String boundary,int committedRows,int externalCalls) throws Exception {
        Path directory=Path.of("target/durable-evidence/children",boundary+"-"+UUID.randomUUID()).toAbsolutePath();Files.createDirectories(directory);
        ObjectMapper mapper=new ObjectMapper();Process worker=launch(directory,"crash",boundary);
        try {
            Path marker=directory.resolve("kill.json");long until=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!Files.exists(marker)) {
                if(!worker.isAlive()||System.nanoTime()>until) throw new AssertionError(Files.readString(directory.resolve("crash.log")));
                Thread.sleep(10);
            }
            assertThat(mapper.readTree(Files.readString(marker)).path("pid").asLong()).isEqualTo(worker.pid());
            worker.destroyForcibly();assertThat(worker.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(worker.exitValue()).isNotZero();
            Process recovery=launch(directory,"recover",boundary);
            try {
                assertThat(recovery.waitFor(40,TimeUnit.SECONDS)).isTrue();
                assertThat(recovery.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log"))).isZero();
            } finally {if(recovery.isAlive()) recovery.destroyForcibly();}
            assertThat(recovery.pid()).isNotEqualTo(worker.pid());
            Files.writeString(directory.resolve("receipt.json"),mapper.writeValueAsString(Map.of("killPid",worker.pid(),"killExit",worker.exitValue(),
                    "recoveryPid",recovery.pid(),"method","Process.destroyForcibly","boundary",boundary)));
            var before=mapper.readTree(Files.readString(directory.resolve("recover-before.json")));
            var after=mapper.readTree(Files.readString(directory.resolve("recover-after.json")));
            assertThat(before.size()).isEqualTo(committedRows);assertThat(after.size()).isEqualTo(2);
            String id=Files.readString(directory.resolve("parent-id.txt"));var parent=after.path(id);
            var call=parent.path("invocations").get(0);String childId=call.path("childId").asText();var child=after.path(childId);
            assertThat(parent.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(child.path("status").asText()).isEqualTo("SUCCEEDED");
            assertThat(parent.path("descendants").asInt()).isEqualTo(1);assertThat(child.path("parentId").asText()).isEqualTo(id);
            assertThat(child.path("parentInvocation").asText()).isEqualTo(call.path("id").asText());
            assertThat(call.path("deliveries").size()).isZero();assertThat(call.path("childOutcome").asText()).isEqualTo("SUCCEEDED");
            assertThat(parent.path("deadline")).isEqualTo(before.path(id).path("deadline"));
            assertThat(child.path("deadline")).isEqualTo(parent.path("deadline"));
            var parentInput=parent.path("values").path(call.path("input").asText());
            var childInput=child.path("values").elements().next();
            assertThat(childInput.path("payload")).isEqualTo(parentInput.path("payload"));
            assertThat(childInput.path("sourceRun").asText()).isEqualTo(id);
            assertThat(childInput.path("sourceValue").asText()).isEqualTo(parentInput.path("id").asText());
            var output=parent.path("values").path(call.path("output").asText());
            assertThat(output.path("payload")).isEqualTo(child.path("values").path(child.path("output").asText()).path("payload"));
            assertThat(output.path("sourceRun").asText()).isEqualTo(childId);assertThat(output.path("sourceValue")).isEqualTo(child.path("output"));
            if(committedRows==2) {
                assertThat(childId).isEqualTo(before.path(id).path("invocations").get(0).path("childId").asText());
                assertThat(childInput).isEqualTo(before.path(childId).path("values").path(childInput.path("id").asText()));
            }
            for(var run:List.of(parent,child)) {
                long sequence=1;int settlements=0,acceptances=0;
                for(var event:run.path("events")) {
                    assertThat(event.path("sequence").asLong()).isEqualTo(sequence++);
                    if(event.path("kind").asText().equals("CHILD_SETTLED")) settlements++;
                    if(event.path("kind").asText().equals("CHILD_ACCEPTED")) acceptances++;
                }
                assertThat(settlements).isEqualTo(run==parent?1:0);assertThat(acceptances).isEqualTo(run==parent?1:0);
            }
            assertThat(Files.readAllLines(directory.resolve("echo.calls"))).hasSize(externalCalls);
            assertThat(mapper.readTree(Files.readString(directory.resolve("recover-result.json"))).path("value").path("text").asText()).isEqualTo("exact-original");
        } finally {if(worker.isAlive()) worker.destroyForcibly();}
    }
    private static Process launch(Path directory,String mode,String boundary) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        return new ProcessBuilder(java,"-cp",classpath,ChildProcessWorker.class.getName(),directory.toString(),mode,boundary)
                .redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
    }
}
