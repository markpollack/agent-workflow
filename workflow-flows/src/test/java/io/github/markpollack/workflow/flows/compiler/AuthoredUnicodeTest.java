package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.*;

class AuthoredUnicodeTest {
    enum Field { DEFINITION, PLACEMENT, REASON, PROFILE }

    @ParameterizedTest @EnumSource(Field.class)
    void malformedAuthoredFieldsRefuseBeforeExecutableWorkflowIsReturned(Field field) {
        for(String bad:List.of("\uD800","\uDC00","a\uD800b","a\uDC00b","\uDC00\uD800",
                "\uD800\uD800","\uDC00\uDC00","\uD83D\uDE00\uD800")) {
            assertThatThrownBy(()->withField(field,bad)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("well-formed Unicode");
        }
    }

    @ParameterizedTest @EnumSource(Field.class)
    void literalQuestionsAndSupplementaryCharactersAreValidAndDistinct(Field field) {
        var question=withField(field,"?");var supplementary=withField(field,"\uD83D\uDE00");
        assertThat(question.authoredIdentity()).isNotEqualTo(supplementary.authoredIdentity());
        assertThat(withField(field,"??").authoredIdentity()).isNotEqualTo(supplementary.authoredIdentity());
        assertThat(withField(field,"\u00E9").authoredIdentity()).isNotEqualTo(withField(field,"e\u0301").authoredIdentity());
        assertThat(withField(field,"\uD83D\uDE00").authoredIdentity()).isEqualTo(supplementary.authoredIdentity());
    }

    private static ValidatedWorkflow withField(Field field,String text) {
        return workflow(field==Field.DEFINITION?text:"simple",field==Field.PLACEMENT?text:"work",
                field==Field.REASON?text:"done",field==Field.PROFILE?text:"local-v1",
                null,String.class,1,Terminal.SUCCEEDED);
    }

    record Golden(String label,String name,String call,String reason,String profile,Duration duration,
            Type type,int count,Terminal terminal,String identity) {
        @Override public String toString() { return label; }
    }
    // Captured from the preceding implementation; these are compatibility fixtures, not recomputed expectations.
    static Stream<Golden> unchangedIdentities() {
        return Stream.of(
                new Golden("ascii","simple","work","done","local-v1",Duration.ofMinutes(2),String.class,1,Terminal.SUCCEEDED,
                        "sha256:4b5d9bd88528a9245863f943385c1e3a59ea67dc93fc0b40b15b3459b29ccbeb"),
                new Golden("question","?","?","?","?",null,String.class,1,Terminal.SUCCEEDED,
                        "sha256:0b4aaa3c96ed4cda59c49200990383b1127a7201f33dad382cfd2dbe24f0195f"),
                new Golden("supplementary","flow-\uD83D\uDE00","call-\uD834\uDD1E","done-\uD83D\uDE80","policy-\uD83D\uDE00",null,String.class,1,Terminal.SUCCEEDED,
                        "sha256:b43b8127ca304aeb54d1ce72d73eeffc70e1a09164a47452545b79da57a126ee"),
                new Golden("decomposed","e\u0301","work","done","local-v1",null,String.class,1,Terminal.SUCCEEDED,
                        "sha256:e0b9a7168abdeb73a1885467621abad0725fef62708dcf14409e8ff3ae2eb2b5"),
                new Golden("composed","\u00E9","work","done","local-v1",null,String.class,1,Terminal.SUCCEEDED,
                        "sha256:814608b6632a13b0bb431eaf24e156fef9ee1286bf179c927dc79ad47fa80c7e"),
                new Golden("terminal","terminal","unused","failed","local-v1",Duration.ofHours(2),String.class,0,Terminal.FAILED,
                        "sha256:a00a50f10cded77022d7ff35a6f6fc5e0c81f47e6cc6987329bfb540ed946fb1"),
                new Golden("generic","list","step","done","custom",Duration.ofMinutes(3),new ListType(String.class),2,Terminal.SUCCEEDED,
                        "sha256:ce8af5020f332faa74930614b04c4fea2bebb6fa65b5a45793a2c747b9181767"));
    }
    @ParameterizedTest(name="unchanged {0}") @MethodSource("unchangedIdentities")
    void wellFormedDefinitionsKeepTheirPreviousIdentity(Golden golden) {
        assertThat(workflow(golden.name(),golden.call(),golden.reason(),golden.profile(),golden.duration(),
                golden.type(),golden.count(),golden.terminal()).authoredIdentity()).isEqualTo(golden.identity());
    }

    public record HighWire(@JsonProperty("wire-\uD800") String text) {}
    public record LowWire(@JsonProperty("wire-\uDC00") String text) {}
    public record SupplementaryWire(@JsonProperty("wire-\uD83D\uDE00") String text) {}
    @Test void sharedShapeIdentityFieldsRejectMalformedPropertyNamesAndPreserveValidUnicode() {
        for(Class<?> type:List.of(HighWire.class,LowWire.class)) {
            assertThatThrownBy(()->workflow("wire","work","done","local-v1",null,type,1,Terminal.SUCCEEDED))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("well-formed Unicode");
        }
        assertThat(workflow("wire","work","done","local-v1",null,SupplementaryWire.class,1,Terminal.SUCCEEDED)
                .inputContract().javaType()).isEqualTo(SupplementaryWire.class.getName());
    }

    private static ValidatedWorkflow workflow(String name,String call,String reason,String profile,
            Duration authored,Type type,int count,Terminal terminal) {
        List<Node> nodes=new ArrayList<>();
        for(int i=0;i<count;i++) nodes.add(new Call(call+i,Op.declared("operation",type,type)));
        nodes.add(new End(terminal,reason));
        var policy=new DeadlinePolicy(profile,Duration.ofHours(1));
        var definition=new Definition<>(name,type,type,nodes,authored);
        var placements=StructuredWorkflowCompiler.compile(new Definition<>(name,type,type,nodes,policy.resolve(authored)));
        Map<Placement,ExecutableIdentity> selections=new LinkedHashMap<>();
        placements.bindings().forEach(b->selections.put(b.placement(),new ExecutableIdentity("example.Operation",
                "sha256:"+"1".repeat(64),"sha256:"+"2".repeat(64),type,type)));
        return ValidatedWorkflow.compile(definition,selections,policy);
    }
}
