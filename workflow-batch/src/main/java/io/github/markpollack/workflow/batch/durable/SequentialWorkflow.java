package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** Small sequential authoring facade; build uses the existing immutable validating compiler. */
public final class SequentialWorkflow {
    private final ApplicationDeployment deployment;
    private final String name;
    private final List<Call> calls = new ArrayList<>();
    private final List<ExecutableIdentity> selections = new ArrayList<>();
    private End end;
    private Duration duration;

    SequentialWorkflow(ApplicationDeployment deployment, String name) {
        this.deployment = deployment; this.name = Objects.requireNonNull(name);
    }
    /** Place a registered step; a repeated registration requires a distinct authored placement. */
    public SequentialWorkflow then(String registration) { return then(registration, registration); }
    /** Place the selected supplied step at a stable authored location. */
    public SequentialWorkflow then(String placement, String registration) {
        if (end != null) throw new IllegalStateException("definition is already terminated");
        ExecutableIdentity selected = deployment.selection(registration);
        calls.add(new Call(placement, Op.declared(placement, selected.input(), selected.output())));
        selections.add(selected); return this;
    }
    /** Apply an optional tighter finite bound through the compiler's existing policy. */
    public SequentialWorkflow maxDuration(Duration duration) {
        if (this.duration != null) throw new IllegalStateException("duration already configured");
        this.duration = Objects.requireNonNull(duration); return this;
    }
    /** Declare explicit successful or unsuccessful completion. */
    public SequentialWorkflow terminate(Terminal terminal) {
        if (end != null) throw new IllegalStateException("terminal already configured");
        end = new End(Objects.requireNonNull(terminal), ""); return this;
    }
    /** Infer concrete boundaries from supplied steps and compile every binding and continuation. */
    public ValidatedWorkflow build() {
        if (calls.isEmpty() || end == null) throw new IllegalStateException("steps and explicit terminal required");
        List<Node> nodes = new ArrayList<>(calls); nodes.add(end);
        var definition = new Definition<>(name, selections.getFirst().input(), selections.getLast().output(), nodes, duration);
        return ValidatedWorkflow.compileSequential(definition, selections, DeadlinePolicy.DEFAULT);
    }
}
