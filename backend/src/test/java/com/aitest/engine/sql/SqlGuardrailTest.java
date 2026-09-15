package com.aitest.engine.sql;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.execution.VariableResolver;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SqlGuardrailTest {
    final SqlPolicyValidator policy = new SqlPolicyValidator();
    @Test void astRejectsDdlMultiStatementsTautologiesAndDataModifyingCte() {
        for (String sql : new String[]{"DROP TABLE orders", "SELECT 1; DELETE FROM orders", "DELETE FROM orders", "UPDATE orders SET state='X' WHERE 1=1", "DELETE FROM orders WHERE id=id", "DELETE FROM orders WHERE id=1 OR 2=2", "SELECT SLEEP(20)", "SELECT 1 INTO OUTFILE '/tmp/file'", "WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x"})
            assertThatThrownBy(() -> policy.validate(sql, true)).as(sql).isInstanceOf(Problem.class);
        assertThat(policy.validate("WITH x AS (SELECT id FROM orders) SELECT * FROM x", false).write()).isFalse();
        assertThat(policy.validate("UPDATE orders SET state=? WHERE id=?", true).write()).isTrue();
        assertThatThrownBy(() -> policy.validate("UPDATE orders SET state=? WHERE id=?", false)).isInstanceOf(Problem.class);
    }
    @Test void quotedAndNamedParametersAreBoundAsValuesAndNeverBecomeSql() {
        SqlParameters parameters = new SqlParameters(new VariableResolver(new JsonCodec()));
        var prepared = parameters.bind("SELECT * FROM orders WHERE no='${orderNo}' AND owner=:owner AND note='literal :text'", Map.of("orderNo", "x' OR 1=1 --", "owner", 7));
        assertThat(prepared.sql()).isEqualTo("SELECT * FROM orders WHERE no=? AND owner=? AND note='literal :text'");
        assertThat(prepared.values()).containsExactly("x' OR 1=1 --", 7);
        assertThatThrownBy(() -> policy.validate(parameters.bind("SELECT * FROM ${table}", Map.of("table", "orders")).sql(), false)).isInstanceOf(Problem.class);
    }
}
