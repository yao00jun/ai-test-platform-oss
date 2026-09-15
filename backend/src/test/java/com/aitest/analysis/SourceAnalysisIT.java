package com.aitest.analysis;

import com.aitest.asset.Asset;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class SourceAnalysisIT extends ExchangeHttpTest {
    @TempDir Path temporary;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;

    @Test void localJavaVueAndDdlBecomeAnImmutableProjectScopedEvidenceSnapshot() throws Exception {
        String project = project().id();
        Path backend = Files.createDirectories(temporary.resolve("后端源码"));
        Path frontend = Files.createDirectories(temporary.resolve("前端组件"));
        Path java = backend.resolve("Orders.java");
        Files.writeString(java, """
                package shop;
                import java.math.BigDecimal;
                import org.springframework.web.bind.annotation.*;
                @RestController @RequestMapping({"/orders", "/v2/orders"})
                class Orders {
                    private OrderService service;
                    @PostMapping("/{id}/refund")
                    void refund(@PathVariable long id, @RequestBody @Valid RefundRequest request) {
                        service.refund(id, request.amount());
                    }
                }
                class OrderService {
                    private OrderMapper orderMapper;
                    void refund(long id, BigDecimal amount) {
                        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
                        orderMapper.updateStatus(id, "REFUNDED");
                    }
                }
                record RefundRequest(@DecimalMin("0.01") BigDecimal amount, @Size(max=20) String reason) {}
                @TableName("t_order") class Order {
                    @TableId long id;
                    @TableField("pay_status") String status;
                    private static final String API_KEY = "source-fixture-secret";
                }
                interface OrderMapper { void updateStatus(long id, String status); }
                """);
        Files.writeString(backend.resolve("OrderMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="shop.OrderMapper"><update id="updateStatus">
                UPDATE t_order SET pay_status = #{status} WHERE id = #{id}
                </update></mapper>
                """);
        Files.writeString(frontend.resolve("Refund.vue"), """
                <script setup>const misleading = '<button data-testid="not-rendered">';</script>
                <template><section><input id="amount" placeholder="退款金额" />
                <button data-testid="submit-refund" aria-label="提交退款">提交</button></section></template>
                """);
        Path ddl = temporary.resolve("业务表.sql");
        Files.writeString(ddl, """
                CREATE TABLE t_user(id BIGINT PRIMARY KEY);
                CREATE TABLE t_order(id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL,
                  pay_status ENUM('CREATED','REFUNDED') NOT NULL COMMENT '退款状态',
                  CONSTRAINT fk_order_user FOREIGN KEY(user_id) REFERENCES t_user(id),
                  UNIQUE KEY uk_order_user(id,user_id));
                """);
        Map<String, Object> input = Map.of("backendRepoPath", backend.toString(), "frontendRepoPath", frontend.toString(), "sqlScriptPath", ddl.toString(), "idempotencyKey", "five-inputs");
        Map<String, Object> submission = submit(project, input);
        Map<String, Object> snapshot = finish(project, submission);
        assertThat(snapshot.get("status")).isEqualTo("READY");
        Map<String, Object> result = map(snapshot.get("result"));
        var backendResult = map(result.get("backend"));
        assertThat(objects(backendResult.get("endpoints"))).anySatisfy(endpoint -> assertThat(endpoint).containsEntry("method", "POST").containsEntry("path", "/orders/{id}/refund"));
        assertThat(objects(backendResult.get("endpoints"))).anySatisfy(endpoint -> assertThat(endpoint).containsEntry("path", "/v2/orders/{id}/refund"));
        assertThat(objects(backendResult.get("constraints"))).anySatisfy(constraint -> assertThat(constraint).containsEntry("annotation", "DecimalMin").containsEntry("value", "0.01"));
        assertThat(objects(backendResult.get("branches"))).anySatisfy(branch -> assertThat(branch.get("condition").toString()).contains("amount.signum() <= 0"));
        assertThat(objects(backendResult.get("models"))).anySatisfy(model -> assertThat(model).containsEntry("table", "t_order"));
        assertThat(objects(backendResult.get("mapperStatements"))).anySatisfy(statement -> assertThat(statement).containsEntry("namespace", "shop.OrderMapper").containsEntry("id", "updateStatus"));
        assertThat(objects(map(result.get("frontend")).get("selectors"))).extracting(selector -> selector.get("selector")).contains("testId=submit-refund", "placeholder=退款金额").doesNotContain("testId=not-rendered");
        var tables = objects(map(result.get("database")).get("tables"));
        var order = tables.stream().filter(table -> "t_order".equals(table.get("name"))).findFirst().orElseThrow();
        assertThat(objects(order.get("columns"))).filteredOn(column -> "pay_status".equals(column.get("name")))
                .singleElement().satisfies(column -> assertThat(column.get("type").toString()).containsIgnoringCase("ENUM"));
        assertThat(json.write(order)).contains("REFUNDED", "fk_order_user", "t_user", "退款状态");
        assertThat(snapshot.get("fileCount")).isEqualTo(4);
        assertThat(json.write(snapshot)).doesNotContain("source-fixture-secret");
        String id = submission.get("analysisId").toString();
        var excerpt = object(request("GET", path(project) + "/" + id + "/file?kind=BACKEND&path=Orders.java&from=13&to=16", null));
        assertThat(excerpt.get("content").toString()).contains("amount.signum() <= 0");
        var secretExcerpt = object(request("GET", path(project) + "/" + id + "/file?kind=BACKEND&path=Orders.java&from=20&to=24", null));
        assertThat(secretExcerpt.get("content").toString()).contains("API_KEY").doesNotContain("source-fixture-secret");
        Files.writeString(java, "class ReplacedAfterSnapshot {}");
        assertThat(object(request("GET", path(project) + "/" + id, null))).isEqualTo(snapshot);
        assertThat(object(request("POST", path(project), input))).containsEntry("analysisId", id).containsEntry("jobId", submission.get("jobId"));
        assertThat(request("GET", path(project().id()) + "/" + id, null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT content_cipher FROM source_snapshot_file WHERE snapshot_id=? AND path='Orders.java'", String.class, id)).startsWith("enc:v1:").doesNotContain("amount.signum");
        assertThat(assets.all(project)).isEmpty();
    }

    @Test void syntaxFailuresAndDynamicSelectorsRemainVisibleWhileNoSourceIsExecuted() throws Exception {
        String project = project().id();
        Path root = Files.createDirectories(temporary.resolve("部分可解析"));
        Files.writeString(root.resolve("Broken.java"), "class Broken { void incomplete(");
        Files.writeString(root.resolve("Page.tsx"), "export const Page = () => <><input data-testid={row.id} /><button data-testid='static-submit'>Go</button></>;");
        Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(root.resolve("node_modules/Skip.java"), "class ShouldNeverBeScanned {}");
        Files.writeString(root.resolve("build.ps1"), "throw 'Do not execute this source' ");
        var snapshot = finish(project, submit(project, Map.of("backendRepoPath", root.toString(), "frontendRepoPath", root.toString(), "idempotencyKey", "partial")));
        assertThat(snapshot.get("status")).isEqualTo("PARTIAL");
        assertThat(objects(snapshot.get("diagnostics"))).anySatisfy(diagnostic -> assertThat(diagnostic).containsEntry("code", "JAVA_PARSE_ERROR").containsEntry("path", "Broken.java"));
        var selectors = objects(map(map(snapshot.get("result")).get("frontend")).get("selectors"));
        assertThat(selectors).extracting(selector -> selector.get("selector")).contains("testId=static-submit").doesNotContain("testId=row.id");
        assertThat(objects(snapshot.get("diagnostics"))).anySatisfy(diagnostic -> assertThat(diagnostic).containsEntry("code", "DYNAMIC_SELECTOR"));
        assertThat(json.write(snapshot)).doesNotContain("ShouldNeverBeScanned");
    }

    @Test void projectSourceSettingsUseNormalAssetVersionsAndAnalysisValidatesInputs() throws Exception {
        Asset project = project();
        Path source = Files.createDirectories(temporary.resolve("项目默认源码"));
        Files.writeString(source.resolve("Simple.java"), "package sample; class Simple { int total() { return 1; } }");
        var updated = request("PATCH", "/api/projects/" + project.id() + "/assets/" + project.id(), Map.of("baseVersion", project.version(), "data", Map.of("backendRepoPath", source.toString())));
        assertThat(updated.statusCode()).as(new String(updated.body())).isEqualTo(200);
        var submitted = submit(project.id(), Map.of("idempotencyKey", "configured-path"));
        assertThat(finish(project.id(), submitted).get("status")).isEqualTo("READY");
        assertThat(request("POST", path(project.id()), Map.of("idempotencyKey", "configured-path", "ddlText", "CREATE TABLE extra(id INT);" )).statusCode()).isEqualTo(409);
        assertThat(request("POST", path(project.id()), Map.of("idempotencyKey", "relative", "backendRepoPath", "relative/path")).statusCode()).isEqualTo(422);
        assertThat(request("POST", path(project.id()), Map.of("idempotencyKey", "unknown", "executeShell", "echo bad")).statusCode()).isEqualTo(400);
    }

    private String path(String project) { return "/api/projects/" + project + "/source-analyses"; }
    private Map<String, Object> submit(String project, Map<String, Object> input) throws Exception {
        var response = request("POST", path(project), input);
        assertThat(response.statusCode()).as(new String(response.body())).isEqualTo(200);
        return object(response);
    }
    private Map<String, Object> finish(String project, Map<String, Object> submission) throws Exception {
        String job = submission.get("jobId").toString();
        await().atMost(Duration.ofSeconds(30)).until(() -> jobs.get(project, job).terminal());
        assertThat(jobs.get(project, job).status()).as(jobs.get(project, job).error()).isEqualTo("SUCCEEDED");
        var response = request("GET", path(project) + "/" + submission.get("analysisId"), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return object(response);
    }
}
