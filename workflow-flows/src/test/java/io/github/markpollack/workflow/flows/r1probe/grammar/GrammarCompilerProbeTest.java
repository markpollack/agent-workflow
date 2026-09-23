package io.github.markpollack.workflow.flows.r1probe.grammar;

import java.io.IOException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class GrammarCompilerProbeTest {
    @TempDir Path directory;
    private int serial;

    private record Compilation(boolean success, String diagnostics, String name, Path output) {}

    private Compilation compile(String body) throws IOException {
        String name = "Consumer" + serial++;
        String source = """
                import java.time.Duration;
                import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype;
                import io.github.markpollack.workflow.flows.r1probe.grammar.BoundaryInferenceCandidate;
                import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.*;
                import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.*;
                import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.*;
                import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Terminal.*;
                import static io.github.markpollack.workflow.flows.r1probe.grammar.GrammarFixtures.CreditOutcome.*;
                import static io.github.markpollack.judge.jury.interpretation.VerdictReading.*;
                public class %s { %s }
                """.formatted(name, body);
        JavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = Files.createDirectory(directory.resolve(name));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            boolean success = compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21", "-classpath", classpath, "-d", output.toString()), null, List.of(unit)).call();
            return new Compilation(success, diagnostics.getDiagnostics().stream()
                    .map(d -> d.getKind() + " " + d.getMessage(null)).collect(Collectors.joining("\n")), name, output);
        }
    }

    private GrammarPrototype.Definition<?> executes(String root, String statements) throws Exception {
        Compilation result = compile("public static Definition<" + root + "> run() { " + statements + " }");
        assertTrue(result.success(), result.diagnostics());
        try (var loader = new URLClassLoader(new java.net.URL[] { result.output().toUri().toURL() }, getClass().getClassLoader())) {
            return (GrammarPrototype.Definition<?>) loader.loadClass(result.name()).getMethod("run").invoke(null);
        }
    }

    private void refuses(String statements, String diagnostic) throws IOException {
        Compilation result = compile("public static void run() { " + statements + " }");
        assertFalse(result.success(), "unexpected compilation: " + statements);
        assertTrue(result.diagnostics().contains(diagnostic), result.diagnostics());
    }

    @Test void sequentialFirstThenInfersConcreteRoot() throws Exception {
        var built = executes("Request", """
                return GrammarPrototype.define("sequence").then(first).then(second).then(third)
                    .then(fourth).then(fifth).terminate(SUCCEEDED).build();
                """);
        assertEquals(Request.class, built.input());
        assertEquals(Set.of(Reply.class), built.successfulOutputs());
    }

    @Test void exhaustiveDecisionRestoresTypedRoot() throws Exception {
        var built = executes("OrderRequest", """
                return GrammarPrototype.define("orders").then(createOrder).then(reserve).decision("credit", credit)
                    .when(RESERVED).then(approve).terminate(SUCCEEDED)
                    .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build();
                """);
        assertEquals(OrderRequest.class, built.input());
        assertEquals(Set.of(OrderReply.class), built.successfulOutputs());
    }

    @Test void allNativeReadingsCompileWithoutWorkflowReadingEnum() throws Exception {
        var built = executes("StoryRequest", """
                return GrammarPrototype.define("story").then(outline).verdict("quality", jury)
                    .when(ACCEPTED).then(story).terminate(SUCCEEDED)
                    .when(REJECTED).then(rejectionReply).terminate(SUCCEEDED)
                    .when(UNDECIDED).then(unresolvedReply).terminate(SUCCEEDED)
                    .when(NOT_APPLICABLE).then(notApplicableReply).terminate(SUCCEEDED)
                    .when(NOT_ASSESSED).terminate(FAILED, "assessment-failed").end().build();
                """);
        assertEquals(Set.of(StoryReply.class), built.successfulOutputs());
    }

    @Test void boundedLoopRestoresRootAfterBody() throws Exception {
        var built = executes("DraftRequest", """
                return GrammarPrototype.define("draft").then(initial)
                    .repeatUntil("ready", ready).maxIterations(5).then(revise).end()
                    .then(publish).terminate(SUCCEEDED).build();
                """);
        assertEquals(Set.of(Published.class), built.successfulOutputs());
    }

    @Test void dynamicThenHeterogeneousStaticGroupCompiles() throws Exception {
        var built = executes("Source", """
                return GrammarPrototype.define("files").then(split)
                    .forEach("chunks").maxItems(40).maxInFlight(8).allSuccessful().then(process).end()
                    .parallel("aggregate").allSuccessful()
                        .branch("results").then(results).branch("logs").then(logs).end()
                    .then(assemble).terminate(SUCCEEDED).build();
                """);
        assertEquals(Source.class, built.input());
        assertEquals(Set.of(Bundle.class), built.successfulOutputs());
    }

    @Test void childAndTimerRetainTypedRoot() throws Exception {
        var built = executes("JobRequest", """
                var child = GrammarPrototype.define("inspection").then(inspect).terminate(SUCCEEDED).build();
                return GrammarPrototype.define("job").then(submit).waitFor("settling", Duration.ofSeconds(5))
                    .subWorkflow("inspection", child).then(report).terminate(SUCCEEDED).build();
                """);
        assertEquals(Set.of(JobReport.class), built.successfulOutputs());
    }

    @Test void explicitRootSupportsChoiceAtEntry() throws Exception {
        var built = executes("Credit", """
                return GrammarPrototype.define("entry", Credit.class).decision("credit", credit)
                    .when(RESERVED).terminate(SUCCEEDED).when(UNAVAILABLE).terminate(SUCCEEDED).end().build();
                """);
        assertEquals(Credit.class, built.input());
    }

    @Test void nestedChoiceEndRestoresMemberThenGroupEndRestoresRoot() throws Exception {
        var built = executes("OrderRequest", """
                return GrammarPrototype.define("nested").then(createOrder).parallel("group").allSuccessful()
                    .branch("one").decision("credit", credit).when(RESERVED).then(approve)
                    .when(UNAVAILABLE).then(reject).end().branch("two").then(reject).end()
                    .then(approve).terminate(SUCCEEDED).build();
                """);
        assertEquals(OrderRequest.class, built.input());
    }

    @Test void regionInsideRootChoiceRestoresArm() throws Exception {
        executes("OrderRequest", """
                return GrammarPrototype.define("outer").then(createOrder).decision("credit", credit)
                    .when(RESERVED).parallel("checks").allSuccessful().branch("one").then(approve).end()
                    .then(approve).terminate(SUCCEEDED)
                    .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build();
                """);
    }

    @Test void usedBoundsPoliciesAndIllegalVerbsAreUnavailable() throws Exception {
        refuses("GrammarPrototype.define(\"x\").then(first).when(RESERVED);", "when");
        refuses("GrammarPrototype.define(\"x\").then(first).decision(\"d\", credit).then(first);", "then");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\").branch(\"a\");", "branch");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\").allSuccessful().end();", "end");
        refuses("GrammarPrototype.define(\"x\").then(first).repeatUntil(\"r\", ready).then(revise);", "then");
        refuses("GrammarPrototype.define(\"x\").then(first).forEach(\"f\").allSuccessful();", "allSuccessful");
        refuses("GrammarPrototype.define(\"x\").then(first).forEach(\"f\").maxItems(2).then(process);", "then");
        refuses("GrammarPrototype.define(\"x\").then(first).forEach(\"f\").maxItems(2).maxItems(3);", "maxItems");
        refuses("GrammarPrototype.define(\"x\").then(first).forEach(\"f\").maxItems(2).maxInFlight(1).maxInFlight(2);", "maxInFlight");
        refuses("GrammarPrototype.define(\"x\").then(first).forEach(\"f\").maxItems(2).allSuccessful().allSuccessful();", "allSuccessful");
        refuses("GrammarPrototype.define(\"x\").then(first).repeatUntil(\"r\", ready).maxIterations(2).maxIterations(3);", "maxIterations");
        refuses("GrammarPrototype.define(\"x\").then(first).terminate(SUCCEEDED).then(first);", "then");
        refuses("GrammarPrototype.define(\"x\").then(first).end();", "end");
    }

    @Test void localRegionsAndLocalChoiceArmsCannotTerminateRoot() throws Exception {
        refuses("GrammarPrototype.define(\"x\").then(first).repeatUntil(\"r\", ready).maxIterations(2).terminate(SUCCEEDED);", "terminate");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\").allSuccessful().branch(\"a\").terminate(SUCCEEDED);", "terminate");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\").allSuccessful().branch(\"a\").decision(\"d\", credit).when(RESERVED).terminate(SUCCEEDED);", "terminate");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\").allSuccessful().branch(\"a\").decision(\"d\", credit).when(RESERVED).when(UNAVAILABLE).end().terminate(SUCCEEDED);", "terminate");
    }

    @Test void lambdaPredicateAndBackEdgeHaveNoAuthoringOverload() throws Exception {
        refuses("GrammarPrototype.define(\"x\").then(first).decision(\"d\", x -> true);", "functional interface");
        refuses("GrammarPrototype.define(\"x\").then(first).repeatUntil(\"r\", x -> true);", "functional interface");
        refuses("GrammarPrototype.define(\"x\").then(first).parallel(\"g\", b -> b.then(first));", "parallel");
        refuses("GrammarPrototype.define(\"x\").then(first).backTo(\"first\");", "backTo");
    }

    @Test void twoParameterSignatureCandidateInfersLinearAndTerminalChoiceBoundaries() throws Exception {
        String linear = "BoundaryInferenceCandidate.define(\"linear\").then(first).then(second).then(third).then(fourth).then(fifth).terminate(SUCCEEDED).build()";
        String choice = "BoundaryInferenceCandidate.define(\"choice\").then(createOrder).then(reserve).decision(\"credit\", credit).when(RESERVED).then(approve).terminate(SUCCEEDED).when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build()";
        for (String statement : List.of(
                "BoundaryInferenceCandidate.Workflow<Request, Reply> typed = " + linear + ";",
                "BoundaryInferenceCandidate.Workflow<OrderRequest, OrderReply> typed = " + choice + ";")) {
            Compilation result = compile("public static void run() { " + statement + " }");
            assertTrue(result.success(), result.diagnostics());
        }
        refuses("BoundaryInferenceCandidate.Workflow<Request, JobReport> wrong = " + linear + ";", "incompatible types");
        refuses("BoundaryInferenceCandidate.Workflow<OrderRequest, JobReport> wrong = " + choice + ";", "incompatible types");
        Compilation mixed = compile("public static void run() { BoundaryInferenceCandidate.Workflow<OrderRequest, OrderReply> typed = "
                + choice.replace("then(reject)", "then(first)") + "; }");
        assertTrue(mixed.success(), mixed.diagnostics());
    }

    @Test void wrongRootAssignmentIsRejectedButOutputParameterIsNotProvided() throws Exception {
        refuses("Definition<JobRequest> wrong = GrammarPrototype.define(\"x\").then(first).terminate(SUCCEEDED).build();", "incompatible types");
        refuses("Definition<OrderRequest, OrderReply> typed = GrammarPrototype.define(\"x\").then(createOrder).decision(\"d\", credit).when(RESERVED).then(approve).terminate(SUCCEEDED).when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build();", "wrong number of type arguments");
        var wrongOutput = executes("OrderRequest", """
                return GrammarPrototype.define("wrong-result").then(createOrder).decision("d", credit)
                    .when(RESERVED).then(first).terminate(SUCCEEDED)
                    .when(UNAVAILABLE).then(first).terminate(SUCCEEDED).end().build();
                """);
        assertEquals(Set.of(First.class), wrongOutput.successfulOutputs());
        assertNotEquals(Set.of(OrderReply.class), wrongOutput.successfulOutputs());
    }
}
