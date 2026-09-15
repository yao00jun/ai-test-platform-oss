package com.aitest.analysis;

import com.aitest.analysis.ast.JavaCodeParser;
import com.aitest.analysis.ast.MapperXmlParser;
import com.aitest.analysis.frontend.FrontendSourceParser;
import com.aitest.analysis.schema.SqlSchemaParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SourceParserTest {
    @Test void selectorsInsideConditionalParentsStayConditionalAndCommentsAreNotRoutes() {
        var diagnostics = new ArrayList<SourceDiagnostic>();
        var result = new FrontendSourceParser().parse("Refund.vue", """
                <script setup>
                const notTemplate = '<template><button data-testid="fake" /></template>';
                // path: '/comment-route'
                const text = "path: '/string-route'";
                const routes = [{ path: '/refund' }];
                </script>
                <template><section v-if="canRefund"><button data-testid="refund">退款</button></section>
                <button data-testid="always">帮助</button></template>
                """, diagnostics);
        assertThat(result.get("selectors")).extracting(row -> row.get("selector")).contains("testId=refund", "testId=always").doesNotContain("testId=fake");
        assertThat(result.get("selectors")).filteredOn(row -> "testId=refund".equals(row.get("selector"))).singleElement().satisfies(row -> assertThat(row.get("conditional")).isEqualTo(true));
        assertThat(result.get("selectors")).filteredOn(row -> "testId=always".equals(row.get("selector"))).singleElement().satisfies(row -> assertThat(row.get("conditional")).isEqualTo(false));
        assertThat(result.get("routes")).extracting(row -> row.get("path")).containsExactly("/refund");
    }

    @Test void mapperEntityExpansionIsNeverSilentlyDroppedFromSql() {
        var diagnostics = new ArrayList<SourceDiagnostic>();
        var statements = new MapperXmlParser().parse("queries.xml", """
                <!DOCTYPE mapper [<!ENTITY predicate "AND deleted = 0">]>
                <mapper namespace="Orders"><select id="active">SELECT id FROM t_order WHERE id = #{id} &predicate;</select></mapper>
                """, diagnostics);
        assertThat(statements).isEmpty();
        assertThat(diagnostics).extracting(SourceDiagnostic::code).contains("MAPPER_ENTITY_REFERENCE");
    }

    @Test void classLevelHttpMethodAndJava21ConstraintsAreRecorded() {
        var diagnostics = new ArrayList<SourceDiagnostic>();
        var result = new JavaCodeParser().parse("OrderApi.java", """
                package shop;
                @RestController @RequestMapping(value="/orders", method=RequestMethod.GET)
                class OrderApi { @RequestMapping("/all") void all() {} }
                record Input(@NotBlank String id) {}
                """, diagnostics);
        assertThat(result.get("endpoints")).singleElement().satisfies(row -> assertThat(row).containsEntry("method", "GET").containsEntry("path", "/orders/all"));
        assertThat(result.get("constraints")).singleElement().satisfies(row -> assertThat(row).containsEntry("target", "id").containsEntry("annotation", "NotBlank"));
        assertThat(diagnostics).isEmpty();
    }

    @Test void tableLevelKeysAffectColumnConstraintsAndParseRecoveryIsVisible() {
        var diagnostics = new ArrayList<SourceDiagnostic>();
        var result = new SqlSchemaParser().parse("schema.sql", "CREATE TABLE `sample`(id BIGINT, code VARCHAR(20), PRIMARY KEY(id), UNIQUE KEY uk_code(code)); CREATE TABLE broken (id ???); CREATE TABLE later(id INT);", diagnostics);
        var tables = objects(result.get("tables"));
        assertThat(tables).filteredOn(row -> "sample".equals(row.get("name"))).singleElement().satisfies(table -> {
            assertThat(objects(table.get("columns"))).filteredOn(row -> "id".equals(row.get("name"))).singleElement().satisfies(column -> assertThat(column).containsEntry("primaryKey", true).containsEntry("nullable", false));
            assertThat(objects(table.get("columns"))).filteredOn(row -> "code".equals(row.get("name"))).singleElement().satisfies(column -> assertThat(column).containsEntry("unique", true));
        });
        assertThat(diagnostics).anySatisfy(diagnostic -> assertThat(diagnostic.code()).isIn("DDL_PARSE_ERROR", "UNSUPPORTED_DDL"));
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
}
