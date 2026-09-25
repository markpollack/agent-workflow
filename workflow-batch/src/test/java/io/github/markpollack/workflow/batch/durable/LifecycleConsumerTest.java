package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import static org.assertj.core.api.Assertions.*;

class LifecycleConsumerTest {
    @TempDir Path directory;
    @Test void ordinaryConsumerCompilesAndCannotSupplyRawGraphOrCompilation() throws Exception {
        String valid="""
                import java.nio.file.Path;
                import io.github.markpollack.workflow.batch.durable.*;
                import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
                public class Consumer {
                    public Object run(Path database, ValidatedWorkflow workflow, ExecutableBundle bundle, Object input) {
                        try (var runtime=DurableWorkflows.open(database)) {
                            var admitted=runtime.start(workflow,"request-17",input,bundle);
                            runtime.discover();
                            runtime.inspect(admitted.runId());
                            var completed=runtime.resume(admitted.runId(),workflow);
                            if (completed.status()==RunSnapshot.Status.SUCCEEDED) return runtime.result(admitted.runId(),workflow);
                            return runtime.cancel(admitted.runId(),"consumer","stop");
                        }
                    }
                }
                """;
        assertThat(compile("Consumer",valid)).isTrue();
        for(String type:List.of("io.github.markpollack.workflow.flows.workflow.WorkflowGraph<?,?>",
                "io.github.markpollack.workflow.flows.compiler.WorkflowModel.Compilation<?,?>")) {
            String source="import io.github.markpollack.workflow.batch.durable.*; public class Bypass { void start(DurableWorkflows runtime,"+type+
                    " raw,ExecutableBundle bundle) { runtime.start(raw,\"key\",\"input\",bundle); } }";
            assertThat(compile("Bypass",source)).isFalse();
        }
    }
    private boolean compile(String name,String source) throws Exception {
        Path file=directory.resolve(name+".java");Files.writeString(file,source);
        var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            boolean success=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",directory.toString()),null,manager.getJavaFileObjects(file)).call();
            Path evidence=Path.of("target/durable-evidence/consumer");Files.createDirectories(evidence);
            Files.writeString(evidence.resolve(name+"-"+Math.abs(source.hashCode())+".txt"),"success="+success+"\n"+source+"\n"+diagnostics.getDiagnostics());
            return success;
        }
    }
}
