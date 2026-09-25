package io.github.markpollack.workflow.flows.compiler;

import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import io.github.markpollack.judge.result.Judgment;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProductionValueCodecTest {
    public record Numeric(long sequence,float fraction,List<Long> references) {}
    public record Renamed(@com.fasterxml.jackson.annotation.JsonProperty("wire") List<Integer> numbers) {}
    private final TypeContracts codec=new TypeContracts();

    @Test void concreteApplicationNumericContractsRemainTyped() {
        var type=new TypeReference<Numeric>() {};
        Numeric original=new Numeric(7L,.5f,List.of(1L,2L));
        Numeric decoded=codec.decode(codec.encode(original,type),type);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.references().getFirst()).isInstanceOf(Long.class);
    }
    @Test void nativeEvidenceUsesItsPortableEqualityAndPreservesInterpretation() throws Exception {
        Verdict original=Verdict.single("numeric",Judgment.pass("evidence").toBuilder().metadata("smallLong",7L).metadata("fraction",.5f).build());
        var type=new TypeReference<Verdict>() {};
        Verdict decoded=codec.decode(codec.encode(original,type),type);
        ObjectMapper mapper=new ObjectMapper();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(decoded))).isEqualTo(mapper.readTree(mapper.writeValueAsBytes(original)));
        Interpretation interpretation=Verdicts.interpret(original);
        var interpretationType=new TypeReference<Interpretation>() {};
        assertThat(codec.decode(codec.encode(interpretation,interpretationType),interpretationType)).isEqualTo(interpretation);
        assertThat(Verdicts.interpret(decoded)).isEqualTo(interpretation);
    }
    @Test void savedBytesSurviveCallerMutationAndCodecMismatchRefuses() {
        var type=new TypeReference<Renamed>() {}; List<Integer> numbers=new ArrayList<>(List.of(1,2));
        TypeContracts.Payload saved=codec.encode(new Renamed(numbers),type); numbers.clear(); saved.bytes()[0]=0;
        assertThat(codec.decode(saved,type)).isEqualTo(new Renamed(List.of(1,2)));
        TypeContracts.Payload wrong=new TypeContracts.Payload(saved.declaredType(),saved.codec(),"other",saved.configuration(),saved.bytes());
        assertThatThrownBy(()->codec.decode(wrong,type)).hasMessageContaining("codec version incompatible");
        assertThat(codec.contract(type.getType()).codec()).isEqualTo(codec.identity());
    }
}
