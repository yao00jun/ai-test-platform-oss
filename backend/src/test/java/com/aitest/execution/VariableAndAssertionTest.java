package com.aitest.execution;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class VariableAndAssertionTest {
    final VariableResolver resolver = new VariableResolver(new JsonCodec());
    @Test void typedValuesStayTypedAndMissingOrCyclicVariablesFail() {
        var variables = Map.<String,Object>of("amount", 12, "token", "a'b", "user", Map.of("id", 8));
        assertThat(resolver.resolve(Map.of("amount", "${amount}", "text", "Bearer ${token}", "id", "${user.id}"), variables))
                .isEqualTo(Map.of("amount", 12, "text", "Bearer a'b", "id", 8));
        assertThatThrownBy(() -> resolver.resolve("${missing}", variables)).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> resolver.resolve("${a}", Map.of("a", "${b}", "b", "${a}"))).isInstanceOf(Problem.class);
        assertThat(resolver.references(Map.of("x", "${a}-${b}"))).containsExactlyInAnyOrder("a", "b");
    }
    @Test void assertionsReportFailuresInsteadOfChangingExpectedValuesToMatchActualResponse() {
        AssertionEvaluator evaluator = new AssertionEvaluator(new JsonCodec(), resolver);
        var result = evaluator.evaluate(List.of(
                Map.of("type", "status_code", "expected", 200),
                Map.of("type", "jsonpath", "path", "$.amount", "operator", "eq", "expected", "${amount}"),
                Map.of("type", "response_time", "operator", "lte", "expected", 50)),
                Map.of("status", 500, "body", "{\"amount\":12}", "headers", Map.of(), "durationMs", 70), Map.of("amount", 12));
        assertThat(result).extracting(AssertionResult::passed).containsExactly(false, true, false);
        assertThat(result.getFirst().expected()).isEqualTo(200);
        assertThatThrownBy(() -> evaluator.validate(List.of(Map.of("type", "eval", "expected", "System.exit(0)")))).isInstanceOf(Problem.class);
    }
}
