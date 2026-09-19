package com.aitest.acceptance;
import org.junit.jupiter.api.Tag;

import com.aitest.ai.ModelSettingsService;
import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.JobService;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Exercises the four public capabilities of each module through real HTTP and MySQL. */
@TestPropertySource(properties = {"aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
@Tag("slow")
class CapabilityMatrixIT extends ExchangeHttpTest {
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception failure) { throw new ExceptionInInitializerError(failure); } }
    @Autowired ModelSettingsService settings;
    @Autowired JobService jobs;
    @AfterAll static void closeModel() { model.close(); }

    record Module(String label, AssetType type, String template, List<String> formats, Map<String, Object> data) {
        @Override public String toString() { return label; }
    }

    static Stream<Module> modules() {
        return Stream.of(
                new Module("工作台", AssetType.DASHBOARD, "WORKBENCH", List.of("json", "md"), Map.of("notes", "人工备注\n保留中文", "cards", List.of(Map.of("schemaVersion", "aitest.dashboard-card/v1", "id", "quality", "type", "quality", "title", "质量概览", "size", "wide", "visible", true, "fields", List.of("runCount", "passRatePercent"))))),
                new Module("项目管理", AssetType.PROJECT, "PROJECT", List.of("json", "xlsx"), Map.of("description", "人工项目说明", "backendRepoPath", "D:\\源码\\backend", "frontendRepoPath", "D:\\源码\\frontend", "sqlScriptPath", "D:\\源码\\schema.sql")),
                new Module("测试用例", AssetType.FUNCTIONAL_CASE, "FUNCTIONAL", List.of("json", "xlsx"), Map.of("priority", "P0", "caseType", "BOUNDARY", "precondition", "退款金额为边界值", "tags", List.of("退款", "人工"))),
                new Module("接口测试", AssetType.API_CASE, "API", List.of("json", "yaml"), Map.of("method", "POST", "path", "/refund", "bodyType", "JSON", "body", Map.of("amount", 0, "note", "中文\n第二行"), "assertions", List.of(Map.of("type", "status_code", "expected", 200)))),
                new Module("场景自动化", AssetType.SCENARIO, "SCENARIO", List.of("json", "yaml"), Map.of("description", "登录后退款", "variables", Map.of("amount", 2, "confirmed", false), "continueOnFailure", false)),
                new Module("Playwright UI", AssetType.UI_SCENARIO, "UI", List.of("json", "yaml"), Map.of("description", "退款表单", "baseUrl", "https://example.invalid", "viewportWidth", 1280, "viewportHeight", 720)),
                new Module("测试计划", AssetType.TEST_PLAN, "PLAN", List.of("json", "xlsx"), Map.of("description", "退款回归", "scheduleEnabled", false, "timezone", "Asia/Shanghai", "cronExpression", "0 0 9 * * *", "concurrency", 2)),
                new Module("缺陷管理", AssetType.BUG, "BUG", List.of("json", "xlsx"), Map.of("severity", "CRITICAL", "reproduceSteps", "1. 输入边界值\n2. 提交", "actualResult", "出现错误", "expectedResult", "拒绝无效金额", "rootCauseAnalysis", "人工检查边界")));
    }

    @ParameterizedTest(name = "{0}: AI generation, manual CRUD, template and portable formats")
    @MethodSource("modules")
    void eachModuleHasRealGenerationManualAuthorityTemplatesAndRoundTrips(Module module) throws Exception {
        settings.save(new ModelSettingsService.Input(model.url(), "matrix-fixture", "fixture", 0.1, 30));
        Asset project = createProject("验收 " + module.label());
        Asset untouched = assets.create(project.id(), AssetType.MODULE, null, "不应被改动的旁项", Map.of("description", "保持原内容、版本和时间"), "MANUAL");

        var change = module.type() == AssetType.PROJECT
                ? Map.of("operation", "MODIFY", "targetType", "PROJECT", "targetId", project.id(), "baseVersion", project.version(), "data", module.data())
                : Map.of("operation", "ADD", "targetType", module.type().name(), "localKey", "draft", "name", "AI " + module.label(), "data", module.data());
        model.enqueue(module.type() == AssetType.FUNCTIONAL_CASE
                ? "featureCaseStart\n## AI 测试用例\n### 前置条件\n退款金额为边界值\n### 测试步骤与预期结果\n| 步骤 | 预期 |\n| --- | --- |\n| 提交零元退款 | 拒绝无效金额 |\n### 备注\nP0\nfeatureCaseEnd"
                : json.write(Map.of("changes", List.of(change))));
        var generated = request("POST", "/api/ai/generate", Map.of("projectId", project.id(), "type", module.type(), "instruction", "生成退款边界测试资料", "idempotencyKey", UUID.randomUUID().toString()));
        assertThat(generated.statusCode()).as(body(generated.body())).isEqualTo(202);
        String job = object(generated).get("jobId").toString();
        await().atMost(Duration.ofSeconds(35)).until(() -> jobs.get(project.id(), job).terminal());
        assertThat(jobs.get(project.id(), job).status()).as(jobs.get(project.id(), job).error()).isEqualTo("SUCCEEDED");
        var aiAssets = assets.list(project.id(), module.type(), null, "", 0, 100).items();
        assertThat(aiAssets).hasSize(1);
        Map<String, Object> expectedAi = module.type() == AssetType.FUNCTIONAL_CASE
                ? Map.of("priority", "P0", "precondition", "<p>退款金额为边界值</p>\n") : module.data();
        expectedAi.forEach((key, value) -> assertThat(wire(aiAssets.getFirst().data().get(key))).as(key).isEqualTo(wire(value)));
        if (module.type() == AssetType.FUNCTIONAL_CASE)
            assertThat(assets.children(project.id(), aiAssets.getFirst().id())).singleElement().satisfies(step -> assertThat(step.data()).containsEntry("step", "提交零元退款").containsEntry("expected", "拒绝无效金额"));

        Asset manual;
        if (module.type() == AssetType.PROJECT) manual = assets.get(project.id(), project.id());
        else {
            var created = request("POST", "/api/projects/" + project.id() + "/assets", Map.of("type", module.type(), "name", "人工 " + module.label(), "data", module.data()));
            assertThat(created.statusCode()).as(body(created.body())).isEqualTo(201);
            manual = json.convert(object(created), Asset.class);
        }
        var changed = request("PATCH", assetPath(project.id(), manual.id()), Map.of("baseVersion", manual.version(), "name", "人工最终稿 " + module.label(), "data", module.data(), "confirmed", true));
        assertThat(changed.statusCode()).as(body(changed.body())).isEqualTo(200);
        Asset saved = json.convert(object(changed), Asset.class);
        assertThat(saved.id()).isEqualTo(manual.id());
        assertThat(saved.version()).isNotEqualTo(manual.version());
        assertThat(saved.confirmed()).isTrue();
        assertThat(object(request("GET", assetPath(project.id(), manual.id()), null)).get("name")).isEqualTo(saved.name());
        assertThat(assets.get(project.id(), untouched.id())).isEqualTo(untouched);

        // A template is usable only if its advertised parser accepts it and application creates assets.
        var families = objects(json.tree(body(request("GET", "/api/templates", null).body())));
        var family = families.stream().filter(value -> module.template().equals(value.get("family"))).findFirst().orElseThrow();
        var variant = objects(family.get("variants")).getFirst();
        var template = request("GET", "/api/templates/" + module.template() + "?format=" + variant.get("format"), null);
        assertThat(template.statusCode()).isEqualTo(200);
        Asset templateProject = createProject("模板 " + module.label());
        var checked = preview(templateProject.id(), variant.get("type").toString(), variant.get("importFormat").toString(), variant.get("filename").toString(), template.body(), Map.of());
        assertThat(objects(checked.get("errors"))).isEmpty();
        apply(templateProject.id(), checked);
        assertThat(assets.all(templateProject.id())).isNotEmpty();

        for (String format : module.formats()) {
            var exported = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", module.type(), "assetIds", List.of(saved.id()), "format", format));
            assertThat(exported.statusCode()).as(module.label() + "/" + format + ": " + (exported.statusCode() == 200 ? "download" : body(exported.body()))).isEqualTo(200);
            Asset destination = createProject("导入 " + module.label() + " " + format);
            var imported = preview(destination.id(), module.type().name(), format, "assets." + format, exported.body(), Map.of());
            assertThat(objects(imported.get("errors"))).as(module.label() + "/" + format).isEmpty();
            apply(destination.id(), imported);
            assertThat(assets.get(destination.id(), destination.id())).isEqualTo(destination);
            if (module.type() == AssetType.PROJECT) {
                assertThat(map(map(imported.get("metadata")).get("project"))).containsEntry("name", saved.name());
                assertThat(map(map(map(imported.get("metadata")).get("project")).get("data"))).containsAllEntriesOf(module.data());
                assertThat(assets.all(destination.id())).singleElement().satisfies(copy -> {
                    assertThat(copy.name()).isEqualTo(untouched.name());
                    assertThat(copy.data()).isEqualTo(untouched.data());
                    assertThat(copy.id()).isNotEqualTo(untouched.id());
                });
            } else {
                assertThat(assets.list(destination.id(), module.type(), null, "", 0, 100).items()).singleElement().satisfies(copy -> {
                    assertThat(copy.name()).isEqualTo(saved.name());
                    assertThat(wire(copy.data())).isEqualTo(wire(saved.data()));
                    assertThat(copy.id()).isNotEqualTo(saved.id());
                });
            }
        }

        var deleted = request("DELETE", assetPath(project.id(), saved.id()) + "?baseVersion=" + saved.version(), null);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(request("GET", assetPath(project.id(), saved.id()), null).statusCode()).isEqualTo(404);
        if (module.type() != AssetType.PROJECT) {
            assertThat(assets.get(project.id(), untouched.id())).isEqualTo(untouched);
            assertThat(assets.get(project.id(), aiAssets.getFirst().id())).isEqualTo(aiAssets.getFirst());
        }
    }

    private Asset createProject(String name) throws Exception {
        var response = request("POST", "/api/projects", Map.of("name", name, "data", Map.of("description", "验收项目")));
        assertThat(response.statusCode()).as(body(response.body())).isEqualTo(201);
        return json.convert(object(response), Asset.class);
    }
    private String assetPath(String project, String id) { return "/api/projects/" + project + "/assets/" + id; }
    private String body(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private Object wire(Object value) { return json.tree(json.write(value)); }
}
