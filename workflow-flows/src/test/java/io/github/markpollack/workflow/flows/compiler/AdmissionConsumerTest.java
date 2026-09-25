package io.github.markpollack.workflow.flows.compiler;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdmissionConsumerTest {
    private boolean compiles(String body) throws Exception {
        String source="import io.github.markpollack.workflow.flows.compiler.*; import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*; import io.github.markpollack.workflow.flows.workflow.*; import java.util.*; import java.time.*; class Consumer { "+body+" }";
        var output=Files.createTempDirectory("workflow-admission-consumer-");
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        JavaFileObject unit=new SimpleJavaFileObject(URI.create("string:///Consumer.java"),JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            return compiler.getTask(null,manager,diagnostics,List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",output.toString()),null,List.of(unit)).call();
        } finally {
            try(var files=Files.walk(output)) { for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
        }
    }
    @Test void consumersMustSupplyDefinitionsInsteadOfUncheckedGraphsOrCompilations() throws Exception {
        assertThat(compiles("Object valid(Definition<?,?> definition) { return ValidatedWorkflow.compile(definition, Map.of()); }")).isTrue();
        assertThat(compiles("Object invalid(WorkflowGraph<?,?> graph) { return ValidatedWorkflow.compile(graph, Map.of()); }")).isFalse();
        assertThat(compiles("Object invalid(Compilation<?,?> compilation) { return ValidatedWorkflow.compile(compilation, Map.of()); }")).isFalse();
        assertThat(compiles("Object invalid(Compilation<?,?> compilation) { return new ValidatedWorkflow(compilation, Map.of()); }")).isFalse();
    }

    public static class WithoutJudge {
        public static void main(String[] args) {
            ValidatedWorkflow.compile(new WorkflowModel.Definition<>("plain",String.class,String.class,
                    List.of(new WorkflowModel.End(WorkflowModel.Terminal.SUCCEEDED,"")),java.time.Duration.ofMinutes(1)),java.util.Map.of());
            System.out.println("sequential admission without optional Judge dependency passed");
        }
    }
    @Test void ordinaryAdmissionDoesNotRequireTheOptionalJudgeDependency() throws Exception {
        String separator=java.io.File.pathSeparator;
        String classpath=String.join(separator,java.util.Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator)))
                .filter(entry->!entry.contains("/agent-judge-")).toList());
        Process process=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",classpath,WithoutJudge.class.getName()).redirectErrorStream(true).start();
        boolean finished=process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS);
        if(!finished) process.destroyForcibly();
        assertThat(finished).isTrue();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("sequential admission without optional Judge dependency passed");
    }
}
