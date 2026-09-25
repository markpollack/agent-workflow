package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import io.github.markpollack.workflow.patterns.graph.NodeType;
import io.github.markpollack.workflow.flows.workflow.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** Structural lowering postconditions, using analyzed exits rather than redoing data-flow inference. */
final class RegionTopology {
    private final Map<SummaryKey,RegionSummary> summaries;
    private final Set<WorkflowEdge> expected=new HashSet<>();
    private final Set<WorkflowEdge> backEdges=new HashSet<>();
    private final String finish;
    private final Map<String,WorkflowNode> nodes=new HashMap<>();
    private final Set<String> expectedNodes=new HashSet<>();

    private RegionTopology(Definition<?,?> definition,Map<SummaryKey,RegionSummary> summaries,WorkflowGraph<?,?> graph) {
        this.summaries=summaries;
        for(WorkflowNode node:graph.nodes()) require(nodes.putIfAbsent(node.name(),node)==null,"duplicate graph ID");
        finish=Coordinates.root(definition).child("completion","terminal",0).graphName();
    }

    static Set<WorkflowEdge> verify(Definition<?,?> definition,WorkflowGraph<?,?> graph,Map<SummaryKey,RegionSummary> summaries) {
        RegionTopology check=new RegionTopology(definition,summaries,graph);
        check.step(check.finish);
        String start=check.sequence(definition.nodes(),Coordinates.root(definition));
        require(Objects.equals(start,graph.startNode())&&check.finish.equals(graph.finishNode()),"lowered graph boundary disagreement");
        require(check.expectedNodes.equals(check.nodes.keySet()),"graph node set disagrees with regions");
        require(check.expected.equals(new HashSet<>(graph.edges())),"edges disagree with authoritative region topology");
        return Set.copyOf(check.backEdges);
    }

    private RegionSummary summary(Placement p) {
        List<RegionSummary> matches=summaries.values().stream().filter(s->s.placement().equals(p)).toList();
        require(!matches.isEmpty(),"missing region summary");
        RegionSummary first=matches.getFirst();
        for(RegionSummary next:matches) require(first.continues()==next.continues()&&first.normalExits().equals(next.normalExits()),"template continuation disagreement");
        return first;
    }
    private String sequence(List<Node> nodes,Placement parent) {
        String entry=null; List<Placement> previous=List.of();
        for(int i=0;i<nodes.size();i++) {
            Placement placement=Coordinates.node(parent,nodes.get(i),i);
            if(i>0) require(!previous.isEmpty(),"authored successor after terminal region");
            if(entry==null) entry=placement.graphName();
            for(Placement exit:previous) edge(exit.graphName(),placement.graphName());
            node(nodes.get(i),placement);
            previous=summary(placement).normalExits();
        }
        RegionSummary sequence=summary(parent);
        boolean continues=nodes.isEmpty()||summary(Coordinates.node(parent,nodes.getLast(),nodes.size()-1)).continues();
        require(sequence.continues()==continues&&sequence.normalExits().equals(previous),"sequence summary disagrees with its regions");
        return entry;
    }
    private void node(Node node,Placement p) {
        RegionSummary summary=summary(p); String id=p.graphName();
        boolean normal;
        switch(node) {
            case Call ignored -> { normal=true; step(id); }
            case WorkflowModel.Timer ignored -> { normal=true; step(id); }
            case End end -> {
                step(id); normal=false; require(summary.terminals().contains(end.terminal()),"terminal effect disagrees with definition"); edge(id,finish);
            }
            case Child child -> {
                step(id); normal=hasSuccessfulExit(child.definition().nodes());
                if(!normal) edge(id,finish);
            }
            case Choice choice -> {
                String route=id,join=p.child("join","choice",0).graphName();
                if(choice.assessment()!=null) {
                    String interpretation=p.child("evidence","interpretation",0).graphName();
                    route=p.child("routing","native-reading",0).graphName(); edge(id,interpretation); edge(interpretation,route);
                    step(id); step(interpretation);
                }
                WorkflowNode.DecisionNode routing=expect(route,WorkflowNode.DecisionNode.class);
                require(Objects.equals(routing.joinNodeName(),summary.continues()?join:null),"decision join reference disagreement");
                if(summary.continues()) step(join);
                normal=false;
                for(int i=0;i<choice.arms().size();i++) {
                    Arm arm=choice.arms().get(i); Placement path=p.child("arm",arm.outcome().name(),i);
                    String entry=sequence(arm.nodes(),path);
                    expected.add(WorkflowEdge.conditional(route,entry==null?join:entry,new EdgeCondition.OptionMatch(arm.outcome().name()),arm.outcome().name()));
                    RegionSummary armSummary=summary(path); normal|=armSummary.continues();
                    for(Placement exit:armSummary.normalExits()) edge(exit.graphName(),join);
                }
            }
            case Parallel group -> {
                normal=true; String join=p.child("join","parallel",0).graphName(); fork(id,join); step(join);
                for(int i=0;i<group.members().size();i++) {
                    Member member=group.members().get(i); Placement path=p.child("member",member.name(),i);
                    String entry=sequence(member.nodes(),path); summary(path).requireNormalResult("parallel topology");
                    expected.add(WorkflowEdge.conditional(id,entry,new EdgeCondition.BranchIndex(i),member.name()));
                    for(Placement exit:summary(path).normalExits()) edge(exit.graphName(),join);
                }
            }
            case Fan fan -> {
                normal=true; Placement path=p.child("body","item",0); String entry=sequence(fan.body(),path);
                String join=p.child("join","fan",0).graphName(); fork(id,join); step(join);
                summary(path).requireNormalResult("fan topology");
                expected.add(WorkflowEdge.conditional(id,entry,new EdgeCondition.BranchIndex(0),"manifest-item"));
                for(Placement exit:summary(path).normalExits()) edge(exit.graphName(),p.child("join","fan",0).graphName());
            }
            case Loop loop -> {
                normal=true; step(id); Placement path=p.child("body","loop",0); String entry=sequence(loop.body(),path);
                summary(path).requireNormalResult("loop topology");
                String test=p.child("test",loop.test().name(),0).graphName();
                expect(test,WorkflowNode.LoopCheckNode.class); expect(p.child("exit","loop",0).graphName(),WorkflowNode.LoopExitNode.class);
                edge(id,entry); for(Placement exit:summary(path).normalExits()) edge(exit.graphName(),test);
                WorkflowEdge back=WorkflowEdge.conditional(test,entry,new EdgeCondition.LoopContinue(),"continue below cap");
                expected.add(back); backEdges.add(back);
                expected.add(WorkflowEdge.conditional(test,p.child("exit","loop",0).graphName(),new EdgeCondition.LoopExit(),"true or configured cap exit"));
            }
        }
        require(summary.continues()==normal,"node continuation summary disagrees with construct");
        require(summary.normalExits().equals(normal?List.of(Coordinates.normalExit(node,p)):List.of()),"node normal exits disagree with construct");
    }
    // Only root-level successful Ends return a child result; child-internal successes do not return the parent.
    private static boolean hasSuccessfulExit(List<Node> nodes) {
        for(Node node:nodes) {
            if(node instanceof End end&&end.terminal()==Terminal.SUCCEEDED) return true;
            if(node instanceof Choice choice&&choice.arms().stream().anyMatch(a->hasSuccessfulExit(a.nodes()))) return true;
        }
        return false;
    }
    private <T extends WorkflowNode> T expect(String id,Class<T> kind) {
        WorkflowNode node=nodes.get(id);
        require(kind.isInstance(node),"graph node kind disagrees with region: "+id);
        expectedNodes.add(id); return kind.cast(node);
    }
    private void step(String id) {
        WorkflowNode.StepNode step=expect(id,WorkflowNode.StepNode.class);
        require(step.type()==NodeType.DETERMINISTIC,"adapter node kind disagreement");
    }
    private void fork(String id,String join) {
        require(Objects.equals(expect(id,WorkflowNode.ForkNode.class).joinNodeName(),join),"fork join reference disagreement");
    }
    private void edge(String from,String to) { expected.add(WorkflowEdge.sequence(from,to)); }
    private static void require(boolean condition,String diagnostic) { if(!condition) throw new IllegalArgumentException(diagnostic); }
}
