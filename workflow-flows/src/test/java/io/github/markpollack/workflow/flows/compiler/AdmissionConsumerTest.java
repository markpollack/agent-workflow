package io.github.markpollack.workflow.flows.compiler;

import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdmissionConsumerTest {
    private boolean compiles(String body) throws Exception {
        String source="import io.github.markpollack.workflow.flows.*; import io.github.markpollack.workflow.flows.compiler.*; import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*; import io.github.markpollack.workflow.flows.workflow.*; import java.util.*; import java.time.*; class Consumer { "+body+" }";
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

    @Test void stagedFluentGrammarRequiresANonemptyExplicitlyTerminatedSequence() throws Exception {
        String method="Object build(Step<?,?> step) { return Workflows.define(\"typed\")";
        assertThat(compiles(method+".then(step).terminate(Terminal.SUCCEEDED).build(); }")).isTrue();
        assertThat(compiles(method+".build(); }")).isFalse();
        assertThat(compiles(method+".terminate(Terminal.SUCCEEDED).build(); }")).isFalse();
        assertThat(compiles(method+".then(step).build(); }")).isFalse();
        assertThat(compiles(method+".then(step).terminate(Terminal.SUCCEEDED).then(step).build(); }")).isFalse();
    }

    @Test void consumersCloseChoiceArmsThroughThePublicDsl() throws Exception {
        String prefix="enum Route { LEFT, RIGHT } Object build(Step<?,?> choose,Step<?,?> arm) { return Workflows.define(\"choice\").decision(\"route\",choose)";
        assertThat(compiles(prefix+".when(Route.LEFT).then(arm).terminate(Terminal.SUCCEEDED).when(Route.RIGHT).then(arm).terminate(Terminal.SUCCEEDED).end().build(); }")).isTrue();
        assertThat(compiles(prefix+".when(Route.LEFT).then(arm).build(); }")).isFalse();
        assertThat(compiles(prefix+".build(); }")).isFalse();
    }

    public static class WithoutJudge {
        public static class Echo implements io.github.markpollack.workflow.flows.Step<String,String> {
            public String execute(io.github.markpollack.workflow.flows.StepContext context,String input) { return input; }
        }
        public static void main(String[] args) {
            io.github.markpollack.workflow.flows.Workflows.define("plain").then(new Echo())
                .terminate(WorkflowModel.Terminal.SUCCEEDED).build();
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
