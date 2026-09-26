package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.batch.durable.DurableWorkflows.*;

/** One-store child acceptance and scoped parent-call settlement. No application invocation occurs here. */
final class LocalChildren {
    private LocalChildren() {}

    static void advance(JdbcRunStore.Tx tx, RunState parent, RunState.Invocation call,
            ValidatedWorkflow.InvocationRecipe recipe, ValidatedWorkflow workflow, ValidatedWorkflow childWorkflow,
            ApplicationDeployment deployment, ExecutionPolicy policy, BoundaryHooks hooks) throws Exception {
        var input=parent.values.get(valueId(recipe.input()));
        verifyValue(input,workflow.values().get(recipe.input()));
        if(!call.id.equals(Digests.fields(parent.id,recipe.placement().graphName(),"root"))
                ||!call.placement.equals(recipe.placement().graphName())
                ||!call.input.equals(input.id)||!call.output.equals(valueId(recipe.output()))
                ||!call.status.equals("UNRESOLVED")||!call.deliveries.isEmpty()||call.settledAt!=0)
            throw new WorkflowRefusal("CHILD_FACTS_CHANGED","child invocation facts disagree");
        String expectedId=Digests.fields("local-child-v1",parent.id,call.id);
        String compatibility=compatibility(childWorkflow,deployment.manifest(),policy);
        var rootRecipe=childWorkflow.values().values().stream().filter(v->v.identity().role().equals("root"))
                .findFirst().orElseThrow(()->new WorkflowRefusal("BINDING_INVALID","child root recipe missing"));
        ResolvedApplication resolved=new ResolvedApplication(deployment,childWorkflow);
        resolved.decode(input.payload,childWorkflow.definition().input());
        RunState child;
        if(call.childId.isEmpty()) {
            RunState root=tx.get(parent.rootId);
            if(parent.depth>=root.maximumChildDepth||root.descendants>=root.maximumDescendants) {
                tx.terminal(parent,"FAILED","CHILD_LIMIT_EXCEEDED","finite child lineage allowance exhausted","store");
                return;
            }
            hooks.at("BEFORE_CHILD_RESERVATION",parent.id);
            if(tx.byKey("child:"+call.id)!=null)
                throw new WorkflowRefusal("CHILD_FACTS_CHANGED","unattached child reservation already exists");
            child=new RunState();child.id=expectedId;child.key="child:"+call.id;
            child.admission=Digests.fields(compatibility,input.digest);child.compatibility=compatibility;
            child.display=childWorkflow.definition().name();child.authored=childWorkflow.authoredIdentity();
            child.deployment=deployment.manifest();child.admitted=tx.now;
            long local=Math.addExact(tx.now,childWorkflow.definition().deadline().toMillis());
            child.deadline=Math.min(parent.deadline,local);
            child.deadlineOrigin=parent.deadline<=local?"INHERITED:"+parent.id+":"+parent.deadlineOrigin:childWorkflow.deadlineOrigin();
            child.leaseMillis=parent.leaseMillis;child.maximumDeliveries=parent.maximumDeliveries;
            child.maximumChildDepth=root.maximumChildDepth;child.maximumDescendants=root.maximumDescendants;
            child.rootId=root.id;child.parentId=parent.id;child.parentInvocation=call.id;child.depth=parent.depth+1;
            putValue(child,rootRecipe,input.payload,"");
            var childRoot=child.values.get(valueId(rootRecipe.identity()));
            childRoot.sourceRun=parent.id;childRoot.sourceValue=input.id;
            child.event("ADMITTED",tx.now,child.admission);
            child.event("CHILD_ATTACHED",tx.now,parent.id+":"+call.id);
            root.descendants=Math.incrementExact(root.descendants);
            call.childId=child.id;
            parent.event("CHILD_ACCEPTED",tx.now,call.id+":"+child.id);
            tx.save(root);tx.insert(child);
            hooks.at("AFTER_CHILD_CREATE_BEFORE_COMMIT",parent.id);
            release(parent,tx.now);tx.save(parent);
            hooks.at("BEFORE_CHILD_ACCEPT_COMMIT",parent.id);
            return;
        }
        if(!call.childId.equals(expectedId)) throw new WorkflowRefusal("CHILD_FACTS_CHANGED","child identity changed");
        child=tx.get(call.childId);
        var childRoot=child.values.get(valueId(rootRecipe.identity()));
        verifyValue(childRoot,rootRecipe);
        long local=Math.addExact(child.admitted,childWorkflow.definition().deadline().toMillis());
        String origin=parent.deadline<=local?"INHERITED:"+parent.id+":"+parent.deadlineOrigin:childWorkflow.deadlineOrigin();
        if(!child.parentId.equals(parent.id)||!child.parentInvocation.equals(call.id)||!child.rootId.equals(parent.rootId)
                ||child.depth!=parent.depth+1||!child.compatibility.equals(compatibility)||!child.deployment.equals(deployment.manifest())
                ||!child.admission.equals(Digests.fields(compatibility,input.digest))
                ||child.deadline!=Math.min(parent.deadline,local)||!child.deadlineOrigin.equals(origin)
                ||!childRoot.sourceRun.equals(parent.id)||!childRoot.sourceValue.equals(input.id)
                ||!java.util.Arrays.equals(childRoot.payload,input.payload))
            throw new WorkflowRefusal("CHILD_FACTS_CHANGED","accepted child lineage/input/deadline/compatibility disagrees");
        tx.observe(child);
        if(child.active()) { release(parent,tx.now);tx.save(parent);return; }
        call.childOutcome=child.status;call.childReason=child.reasonCode;call.settledAt=tx.now;
        if(child.status.equals("SUCCEEDED")) {
            var outputRecipe=childWorkflow.values().get(childWorkflow.terminal().successValue());
            var output=child.values.get(child.output);
            verifyValue(output,outputRecipe);
            resolved.decode(output.payload,childWorkflow.definition().output());
            putValue(parent,workflow.values().get(recipe.output()),output.payload,call.id);
            var parentOutput=parent.values.get(call.output);
            parentOutput.sourceRun=child.id;parentOutput.sourceValue=output.id;
            call.status="COMMITTED";parent.next++;
            parent.event("CHILD_SETTLED",tx.now,call.id+":"+child.id+":SUCCEEDED");
            if(parent.next==workflow.invocations().size()) finish(tx,parent,workflow);
            else tx.save(parent);
        } else {
            call.status="FAILED";
            parent.event("CHILD_SETTLED",tx.now,call.id+":"+child.id+":"+child.status);
            tx.terminal(parent,"FAILED","CHILD_"+child.status,child.reasonCode+":"+child.reasonMessage,child.id);
        }
        hooks.at("BEFORE_CHILD_SETTLEMENT_COMMIT",parent.id);
    }

    private static void release(RunState parent,long now) {
        parent.generation=Math.incrementExact(parent.generation);parent.owner="";parent.leaseUntil=0;
        parent.event("WAITING_CHILD",now,parent.invocations.get(parent.next).childId);
    }
}
