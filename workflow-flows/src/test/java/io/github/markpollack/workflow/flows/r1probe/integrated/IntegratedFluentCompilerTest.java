package io.github.markpollack.workflow.flows.r1probe.integrated;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedFluentFixtures.*;
import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.*;
import static org.junit.jupiter.api.Assertions.*;

class IntegratedFluentCompilerTest {
    @TempDir Path directory;
    private int serial;

    private record Compilation(boolean success, String diagnostics, String name, Path output) {}

    private Compilation compile(String root, String outputType, String body) throws IOException {
        String name = "IntegratedConsumer" + serial++;
        String source = """
                import java.time.Duration;
                import java.util.List;
                import io.github.markpollack.workflow.flows.r1probe.integrated.FluentCandidate;
                import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedCompiler;
                import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.*;
                import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedFluentFixtures.*;
                import static io.github.markpollack.workflow.flows.r1probe.integrated.FluentCandidate.*;
                import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedFluentFixtures.*;
                import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedFluentFixtures.CreditOutcome.*;
                import static io.github.markpollack.judge.jury.interpretation.VerdictReading.*;
                public class %s { public static Built<%s,%s> run() { %s } }
                """.formatted(name, root, outputType, body);
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

    private Built<?,?> executes(String root, String output, String statements) throws Exception {
        Compilation result = compile(root, output, statements);
        assertTrue(result.success(), result.diagnostics());
        try (var loader = new URLClassLoader(new java.net.URL[] { result.output().toUri().toURL() }, getClass().getClassLoader())) {
            try { return (Built<?,?>) loader.loadClass(result.name()).getMethod("run").invoke(null); }
            catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                throw failure;
            }
        }
    }

    private void compileRefuses(String root, String output, String statements, String diagnostic) throws IOException {
        Compilation result = compile(root, output, statements);
        assertFalse(result.success(), "unexpected compilation: " + statements);
        assertTrue(result.diagnostics().contains(diagnostic), result.diagnostics());
    }

    private void buildRefuses(String root, String output, String statements) {
        assertThrows(IllegalArgumentException.class, () -> executes(root, output, statements));
    }

    private static final String SEQUENCE = """
            return define("sequence").then(first).then(second).then(third).then(fourth).then(fifth)
                .terminate(SUCCEEDED).build();
            """;
    private static final String DECISION = """
            return define("orders").then(createOrder).then(reserve).decision("credit", credit)
                .when(RESERVED).then(approve).terminate(SUCCEEDED)
                .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build();
            """;
    private static final String JUDGE = """
            return define("story").then(outline).verdict("quality", jury)
                .when(ACCEPTED).then(story).terminate(SUCCEEDED)
                .when(REJECTED).then(rejectionReply).terminate(SUCCEEDED)
                .when(UNDECIDED).then(unresolvedReply).terminate(SUCCEEDED)
                .when(NOT_APPLICABLE).then(notApplicableReply).terminate(SUCCEEDED)
                .when(NOT_ASSESSED).terminate(FAILED, "assessment-failed").end().build();
            """;
    private static final String LOOP = """
            return define("draft").then(initial).repeatUntil("ready", ready).maxIterations(5)
                .then(revise).end().then(publish).terminate(SUCCEEDED).build();
            """;
    private static final String GROUPS = """
            return define("files").then(split)
                .forEach("chunks").maxItems(40).maxInFlight(8).allSuccessful().then(process).end()
                .parallel("aggregate").allSuccessful()
                    .branch("results").then(results).branch("logs").then(logs).end()
                .then(assemble).terminate(SUCCEEDED).build();
            """;
    private static final String CHILD_TIMER = """
            var child = define("inspection").then(inspect).terminate(SUCCEEDED).build();
            return define("job").then(submit).waitFor("settling", Duration.ofSeconds(5))
                .subWorkflow("inspection", child).then(report).terminate(SUCCEEDED).build();
            """;

    private record Shape(String input, String output, Class<?> inputType, Class<?> outputType, String source) {}
    private static List<Shape> shapes() {
        return List.of(new Shape("Request", "Reply", Request.class, Reply.class, SEQUENCE),
                new Shape("OrderRequest", "OrderReply", OrderRequest.class, OrderReply.class, DECISION),
                new Shape("StoryRequest", "StoryReply", StoryRequest.class, StoryReply.class, JUDGE),
                new Shape("DraftRequest", "Published", DraftRequest.class, Published.class, LOOP),
                new Shape("Source", "Bundle", Source.class, Bundle.class, GROUPS),
                new Shape("JobRequest", "JobReport", JobRequest.class, JobReport.class, CHILD_TIMER));
    }

    @Test void allSixConcreteInputOutputAssignmentsCompileAndUseOneImplementedBuild() throws Exception {
        for (Shape shape : shapes()) {
            assertFalse(shape.source().contains(".<"));
            Built<?,?> built = executes(shape.input(), shape.output(), shape.source());
            assertEquals(shape.inputType(), built.input(), shape.input());
            assertEquals(shape.outputType(), built.output(), shape.output());
            assertNotNull(built.graph());
            assertFalse(built.bindings().isEmpty());
        }
    }

    @Test void everyShapeRejectsWrongInputAndWrongOutputJavaAssignments() throws Exception {
        for (Shape shape : shapes()) {
            compileRefuses("String", shape.output(), shape.source(), "incompatible types");
            compileRefuses(shape.input(), "String", shape.source(), "incompatible types");
        }
    }

    @Test void savedEarlierInputIsTheExactDerivedDispatchFact() throws Exception {
        Built<?,?> built = executes("Request", "Reply", SEQUENCE);
        Binding first = operation(built, "first");
        Binding second = operation(built, "second");
        Binding fourth = operation(built, "fourth");
        Binding fifth = operation(built, "fifth");
        assertEquals(SecondInput.class, second.input().type());
        assertEquals(List.of(First.class, Request.class), second.input().components().stream().map(Fact::type).toList());
        assertEquals(first.output().identity(), second.input().components().get(0).identity());
        assertEquals(FinalInput.class, fifth.input().type());
        assertEquals(fourth.output().identity(), fifth.input().components().get(0).identity());
        assertEquals(second.input().identity(), fifth.input().components().get(1).identity());
    }

    private static Binding operation(Built<?,?> built, String name) {
        return built.bindings().stream().filter(b -> b.operation().endsWith(":" + name)).findFirst().orElseThrow();
    }

    @Test void firstFailedArmDoesNotDetermineTheSuccessfulOutput() throws Exception {
        String source = """
                return define("failed-first").then(createOrder).then(reserve).decision("credit", credit)
                    .when(RESERVED).terminate(FAILED, "not-approved")
                    .when(UNAVAILABLE).then(reject).terminate(SUCCEEDED).end().build();
                """;
        Built<?,?> built = executes("OrderRequest", "OrderReply", source);
        assertEquals(OrderReply.class, built.output());
        compileRefuses("OrderRequest", "Credit", source, "incompatible types");
    }

    @Test void emptyFirstArmCanContinueToAConcreteCommonOutput() throws Exception {
        Built<?,?> built = executes("OrderRequest", "OrderReply", """
                return define("empty-first").then(createOrder).then(reserve).decision("credit", credit)
                    .when(RESERVED).when(UNAVAILABLE).end().then(approve).terminate(SUCCEEDED).build();
                """);
        assertEquals(OrderReply.class, built.output());
        buildRefuses("OrderRequest", "Credit", """
                return define("dangling").then(createOrder).then(reserve).decision("credit", credit)
                    .when(RESERVED).when(UNAVAILABLE).end().build();
                """);
    }

    @Test void emptySuccessfulFirstArmPreservesItsIncomingOutputContract() throws Exception {
        String source = """
                return define("empty-success").then(createOrder).decision("credit", credit)
                    .when(RESERVED).terminate(SUCCEEDED)
                    .when(UNAVAILABLE).terminate(SUCCEEDED).end().build();
                """;
        // Supply the routing operation's required input without changing the retained business carrier.
        source = source.replace("decision(\"credit\", credit)", "decision(\"credit\", Op.named(\"route\", Pending.class, CreditOutcome.class))");
        Built<?,?> built = executes("OrderRequest", "Pending", source);
        assertEquals(Pending.class, built.output());
        compileRefuses("OrderRequest", "OrderReply", source, "incompatible types");
    }

    @Test void laterSuccessfulArmTypeMismatchIsRefusedByTheSameBuild() {
        buildRefuses("OrderRequest", "OrderReply", DECISION.replace("then(reject)", "then(reserve)"));
    }

    @Test void directDynamicAndStaticProductsHaveConcreteTypedBoundaries() throws Exception {
        Built<?,?> fan = executes("Source", "List<Result>", """
                return define("fan").then(split).forEach("chunks").maxItems(40).maxInFlight(8)
                    .allSuccessful().then(process).end().terminate(SUCCEEDED).build();
                """);
        assertEquals(new ListType(Result.class), fan.output());
        Built<?,?> heterogeneous = executes("Request", "Products", """
                return define("product").then(first).parallel("g", Products.class).allSuccessful()
                    .branch("results").then(Op.named("one", Request.class, ResultFile.class))
                    .branch("logs").then(Op.named("two", Request.class, LogFile.class))
                    .end().terminate(SUCCEEDED).build();
                """);
        assertEquals(Products.class, heterogeneous.output());
        assertEquals(List.of(ResultFile.class, LogFile.class), heterogeneous.products().getFirst().members().stream().map(Fact::type).toList());
        Built<?,?> homogeneous = executes("Request", "List<First>", """
                return define("homogeneous").then(first).parallel("g", new TypeRef<List<First>>() {}).allSuccessful()
                    .branch("one").then(first).branch("two").then(first).end().terminate(SUCCEEDED).build();
                """);
        assertEquals(new ListType(First.class), homogeneous.output());
    }

    @Test void directGroupContractCannotExposeTheFeederOrInventMissingRoles() {
        buildRefuses("Request", "First", """
                return define("feeder").then(first).parallel("g", First.class).allSuccessful()
                    .branch("results").then(Op.named("one", Request.class, ResultFile.class))
                    .branch("logs").then(Op.named("two", Request.class, LogFile.class))
                    .end().terminate(SUCCEEDED).build();
                """);
        buildRefuses("Request", "Products", """
                return define("missing-role").then(first).parallel("g", Products.class).allSuccessful()
                    .branch("one").then(Op.named("one", Request.class, ResultFile.class))
                    .branch("two").then(Op.named("two", Request.class, ResultFile.class))
                    .end().terminate(SUCCEEDED).build();
                """);
    }

    @Test void capExitIsAnExplicitTypedPolicyDistinctFromTrueAndFailure() throws Exception {
        Built<?,?> defaultPolicy = executes("DraftRequest", "Draft", """
                return define("default-cap").then(initial).repeatUntil("ready", ready).maxIterations(5)
                    .then(revise).end().terminate(SUCCEEDED).build();
                """);
        Built<?,?> ordinaryExit = executes("DraftRequest", "Draft", """
                return define("ordinary-cap").then(initial).repeatUntil("ready", ready).maxIterations(5)
                    .onLimit(LimitPolicy.EXIT).then(revise).end().terminate(SUCCEEDED).build();
                """);
        assertEquals(Draft.class, ordinaryExit.output());
        assertEquals(LoopExit.LIMIT_FAILURE, defaultPolicy.loops().getFirst().evaluate(false, 5));
        assertEquals(LoopExit.CAP_REACHED, ordinaryExit.loops().getFirst().evaluate(false, 5));
        assertEquals(LoopExit.TEST_TRUE, ordinaryExit.loops().getFirst().evaluate(true, 5));
        assertEquals(LoopExit.CONTINUE, ordinaryExit.loops().getFirst().evaluate(false, 4));
    }

    @Test void structurallyLegalButUnboundChainRefusesThroughFluentBuild() {
        buildRefuses("Request", "JobReport", "return define(\"unbound\").then(first).then(report).terminate(SUCCEEDED).build();");
    }

    @Test void localChoiceEndRestoresMemberThenGroupEndRestoresRoot() throws Exception {
        Built<?,?> built = executes("OrderRequest", "OrderReply", """
                return define("nested").then(createOrder).parallel("members", new TypeRef<List<OrderReply>>() {}).allSuccessful()
                    .branch("one").decision("credit", Op.named("route", Pending.class, CreditOutcome.class))
                        .when(RESERVED).then(approve).when(UNAVAILABLE).then(reject).end()
                    .branch("two").then(reject).end().then(approve).terminate(SUCCEEDED).build();
                """);
        assertEquals(OrderReply.class, built.output());
    }

    @Test void typeChangingChoicesRestoreLoopAndFanOutputStages() throws Exception {
        String loop = """
                return define("changed-loop").then(first)
                    .repeatUntil("done", Op.named("done", Second.class, Boolean.class)).maxIterations(3)
                    .decision("route", Op.named("route", First.class, CreditOutcome.class))
                        .when(RESERVED).then(Op.named("left", Request.class, Second.class))
                        .when(UNAVAILABLE).then(Op.named("right", Request.class, Second.class)).end()
                    .end().terminate(SUCCEEDED).build();
                """;
        Built<?,?> loopBuilt = executes("Request", "Second", loop);
        assertEquals(Second.class, loopBuilt.output());
        compileRefuses("Request", "First", loop, "incompatible types");
        String fan = """
                return define("changed-fan").then(split).forEach("chunks").maxItems(3).allSuccessful().then(process)
                    .decision("route", Op.named("route", Result.class, CreditOutcome.class))
                        .when(RESERVED).then(Op.named("left", Result.class, First.class))
                        .when(UNAVAILABLE).then(Op.named("right", Result.class, First.class)).end()
                    .end().terminate(SUCCEEDED).build();
                """;
        Built<?,?> fanBuilt = executes("Source", "List<First>", fan);
        assertEquals(new ListType(First.class), fanBuilt.output());
        compileRefuses("Source", "List<Result>", fan, "incompatible types");
    }

    @Test void groupsInsideFirstAndLaterRootArmsRestoreTheCorrectArm() throws Exception {
        String source = """
                return define("arm-groups").then(createOrder)
                    .decision("route", Op.named("route", Pending.class, CreditOutcome.class))
                    .when(RESERVED).parallel("first", new TypeRef<List<OrderReply>>() {}).allSuccessful()
                        .branch("one").then(approve).branch("two").then(reject).end()
                        .then(approve).terminate(SUCCEEDED)
                    .when(UNAVAILABLE).parallel("later").allSuccessful()
                        .branch("one").then(approve).branch("two").then(reject).end()
                        .then(reject).terminate(SUCCEEDED)
                    .end().build();
                """;
        Built<?,?> built = executes("OrderRequest", "OrderReply", source);
        assertEquals(OrderReply.class, built.output());
        assertEquals(2, built.products().size());
        compileRefuses("OrderRequest", "Pending", source, "incompatible types");
    }

    @Test void childCanBeTheFirstLoopBodyInvocation() throws Exception {
        Built<?,?> built = executes("JobRequest", "JobStatus", """
                var check = define("check").then(inspect).terminate(SUCCEEDED).build();
                return define("parent").then(submit)
                    .repeatUntil("ready", Op.named("ready", JobStatus.class, Boolean.class)).maxIterations(3)
                    .subWorkflow("inspect", check).end().terminate(SUCCEEDED).build();
                """);
        assertEquals(JobStatus.class, built.output());
    }

    @Test void unavailableVerbsAndUsedConfigurationAreRejectedByJava() throws Exception {
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).when(RESERVED);", "when");
        compileRefuses("OrderRequest", "OrderReply", "return define(\"x\").then(createOrder).decision(\"d\", credit).then(approve);", "then");
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).parallel(\"g\").branch(\"one\");", "branch");
        compileRefuses("DraftRequest", "Draft", "return define(\"x\").then(initial).repeatUntil(\"r\", ready).then(revise);", "then");
        compileRefuses("Source", "List<Result>", "return define(\"x\").then(split).forEach(\"f\").allSuccessful();", "allSuccessful");
        compileRefuses("Source", "List<Result>", "return define(\"x\").then(split).forEach(\"f\").maxItems(2).maxItems(3);", "maxItems");
        compileRefuses("Source", "List<Result>", "return define(\"x\").then(split).forEach(\"f\").maxItems(2).maxInFlight(1).maxInFlight(2);", "maxInFlight");
        compileRefuses("DraftRequest", "Draft", "return define(\"x\").then(initial).repeatUntil(\"r\", ready).maxIterations(2).onLimit(LimitPolicy.EXIT).onLimit(LimitPolicy.FAIL);", "onLimit");
        compileRefuses("DraftRequest", "Draft", "return define(\"x\").then(initial).repeatUntil(\"r\", ready).maxIterations(2).then(revise).terminate(SUCCEEDED);", "terminate");
        compileRefuses("OrderRequest", "OrderReply", "return define(\"x\").then(createOrder).parallel(\"g\").allSuccessful().branch(\"one\").decision(\"d\", credit).when(RESERVED).terminate(SUCCEEDED);", "terminate");
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).terminate(SUCCEEDED).then(fifth);", "then");
    }

    @Test void lambdasAndArbitraryBackEdgesHaveNoIntegratedAuthoringOverload() throws Exception {
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).decision(\"d\", x -> true);", "functional interface");
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).repeatUntil(\"r\", x -> true);", "functional interface");
        compileRefuses("Request", "Reply", "return define(\"x\").then(first).backTo(\"first\");", "backTo");
    }

    @Test void callersCannotFabricateConcreteOperationOrRawBuildTypeWitnesses() throws Exception {
        compileRefuses("Request", "Reply", """
                Op<Request,Reply> fabricated = Op.named("fake", First.class, JobReport.class);
                return define("fake").then(fabricated).terminate(SUCCEEDED).build();
                """, "incompatible");
        compileRefuses("Request", "Reply", """
                Definition<Request,Reply> raw = new Definition<>("raw", Request.class, Reply.class, List.of());
                return IntegratedCompiler.build(raw);
                """, "incompatible types");
        assertEquals(0, FluentCandidate.Seq.class.getMethod("build").getTypeParameters().length);
        assertEquals(0, FluentCandidate.Closed.class.getMethod("build").getTypeParameters().length);
    }

    @Test void inheritedTypeRefCannotLieAboutTheCompiledBoundary() {
        buildRefuses("Request", "Reply", """
                class Swapped<A,B> extends TypeRef<B> {}
                class Trap extends Swapped<First,Reply> {}
                Op<Request,Reply> forged = Op.named("mismatch", Request.class, new Trap());
                return define("mismatch").then(forged).terminate(SUCCEEDED).build();
                """);
    }

    @Test void staleHandlesCannotProduceAnIntegratedDefinition() throws Exception {
        assertThrows(IllegalStateException.class, () -> executes("Request", "Reply", """
                var old = define("stale").then(first);
                var current = old.then(second);
                old.then(third);
                return current.then(fifth).terminate(SUCCEEDED).build();
                """));
    }
}
