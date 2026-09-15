package com.aitest.analysis;

import com.aitest.analysis.schema.*;
import com.aitest.common.*;
import com.aitest.engine.sql.*;
import com.aitest.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SchemaQueryValidatorTest {
    private final List<Map<String, Object>> tables = Values.objects(new SqlSchemaParser().parse("schema.sql", "CREATE TABLE orders(id BIGINT, state VARCHAR(20)); CREATE TABLE refunds(id BIGINT, order_id BIGINT, amount DECIMAL(12,2));", new ArrayList<>()).get("tables"));
    private final SchemaQueryValidator validator = new SchemaQueryValidator(new SqlParameters(new VariableResolver(new JsonCodec())), new SqlPolicyValidator());
    @Test void joinedCteAndCorrelatedSubqueryUseRealColumnsAndReturnBindings() {
        var result = validator.validate(Map.of("sql", "WITH totals AS (SELECT order_id, SUM(amount) AS total FROM refunds GROUP BY order_id) SELECT o.state, t.total FROM orders o JOIN totals t ON t.order_id=o.id WHERE o.id=:orderId AND EXISTS (SELECT 1 FROM refunds r WHERE r.order_id=o.id)",
                "exports", Map.of("total", "total"), "assertions", List.of(Map.of("type", "field", "field", "state", "expected", "REFUNDED"))), tables);
        assertThat(result.get("outputColumns")).isEqualTo(List.of("state", "total"));
        assertThat(result.get("parameters")).isEqualTo(List.of("orderId"));
    }
    @Test void missingOrAmbiguousIdentifiersAndInvalidOutputBindingsAreRejected() {
        for (String query : List.of("SELECT id FROM absent", "SELECT invented FROM orders", "SELECT o.state FROM orders o JOIN refunds r ON r.missing=o.id", "SELECT id FROM orders o JOIN refunds r ON r.order_id=o.id", "SELECT o.state FROM orders o WHERE EXISTS (SELECT 1 FROM refunds r WHERE r.missing=o.id)", "SELECT state AS invented FROM orders WHERE invented='x'", "SELECT state FROM orders WHERE id=?"))
            assertThatThrownBy(() -> validator.validate(Map.of("sql", query), tables)).as(query).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> validator.validate(Map.of("sql", "SELECT state FROM orders", "exports", Map.of("state", "missing")), tables)).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> validator.validate(Map.of("sql", "SELECT state FROM orders", "assertions", List.of(Map.of("type", "field", "field", "missing", "expected", 1))), tables)).isInstanceOf(Problem.class);
    }
    @Test void qualifiedWildcardsAndOutputAliasesKeepActualResultColumnNames() {
        assertThat(validator.validate(Map.of("sql", "SELECT o.* FROM orders o ORDER BY o.id"), tables).get("outputColumns")).isEqualTo(List.of("id", "state"));
        assertThat(validator.validate(Map.of("sql", "SELECT SUM(amount) AS total FROM refunds HAVING total>0 ORDER BY total"), tables).get("outputColumns")).isEqualTo(List.of("total"));
        assertThatThrownBy(() -> validator.validate(Map.of("sql", "SELECT * FROM orders o JOIN refunds r ON r.order_id=o.id"), tables)).isInstanceOf(Problem.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT id FROM orders FETCH FIRST missing ROWS ONLY",
            "SELECT id FROM orders ORDER BY id WITH FILL FROM missing",
            "SELECT id FROM orders LIMIT 2 BY missing",
            "SELECT id FROM orders UNION ALL SELECT order_id FROM refunds LIMIT missing",
            "SELECT DISTINCT ON (missing) id FROM orders",
            "SELECT TOP (missing) id FROM orders",
            "SELECT id FROM orders SETTINGS missing=1",
            "SELECT id FROM orders ORDER BY id INTERPOLATE (missing AS 1)"
    })
    void unmodeledSelectClausesNeverProduceSuccessfulSchemaEvidence(String query) {
        assertThatThrownBy(() -> validator.validate(Map.of("sql", query), tables)).as(query)
                .isInstanceOfSatisfying(Problem.class, failure -> assertThat(failure.code()).isEqualTo("SOURCE_QUERY_INVALID"));
    }
    @Test void ordinaryAndUnionPaginationCountParametersWithoutTreatingThemAsOutputColumns() {
        assertThat(validator.validate(Map.of("sql", "SELECT id FROM orders ORDER BY id LIMIT :count OFFSET :start"), tables))
                .containsEntry("outputColumns", List.of("id")).containsEntry("parameters", List.of("count", "start"));
        assertThat(validator.validate(Map.of("sql", "SELECT id FROM orders UNION ALL SELECT order_id FROM refunds ORDER BY id LIMIT :count OFFSET :start"), tables))
                .containsEntry("outputColumns", List.of("id")).containsEntry("parameters", List.of("count", "start"));
    }
}
