package com.aitest.api.diff;

import com.aitest.ai.*;
import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.job.*;
import com.aitest.support.ModelFixtureServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real import, persisted diff, CAS and company-protocol boundaries; no alternate parser in the test. */
class ApiDiffHealingIT extends ExchangeHttpTest {
    @Autowired JobService jobs;
    @Autowired AiChangeSetService changes;
    @Autowired AiConversationService conversations;
    @Autowired ModelSettingsService settings;
    static final ModelFixtureServer model;
    static { try { model = new ModelFixtureServer(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @AfterAll static void close() { model.close(); }
    @BeforeEach void configure() { settings.save(new ModelSettingsService.Input(model.url(), "fixture-key", "fixture", 0.1, 30)); }

    @Test void actualImportYieldsGranularChangesStableIdentityAndExplicitAmbiguity() throws Exception {
        var p = project();
        var original = preview(p.id(), "API_DEFINITION", "json", document("userId", false));
        var ids = map(apply(p.id(), original).get("keyToId"));
        Asset definition = assets.get(p.id(), ids.get("api_1").toString());
        Asset test = assets.create(p.id(), AssetType.API_CASE, null, "手工资料用例", Map.of("apiDefinitionId", definition.id(), "path", "/users", "method", "POST"), "MANUAL");
        Asset source = assets.create(p.id(), AssetType.DATABASE_SOURCE, null, "校验数据源", Map.of("jdbcUrl", "jdbc:mysql://127.0.0.1:3307/ai_test_business_test", "username", "fixture"), "MANUAL");
        Asset sql = assets.create(p.id(), AssetType.SQL_VALIDATION, test.id(), "数据库检查", Map.of("sql", "SELECT 1", "databaseSourceId", source.id()), "MANUAL");
        Asset scene = assets.create(p.id(), AssetType.SCENARIO, null, "业务流程", Map.of(), "MANUAL");
        Asset step = assets.create(p.id(), AssetType.SCENARIO_STEP, scene.id(), "调用资料接口", Map.of("stepType", "HTTP", "targetId", test.id()), "MANUAL");
        var next = preview(p.id(), "API_DEFINITION", "json", document("uid", true));
        var diff = diff(p.id(), next, Map.of());
        assertThat(objects(diff.get("items"))).extracting(i -> i.get("kind")).containsExactlyInAnyOrder("CHANGED", "UNCHANGED", "ADDED", "REMOVED");
        var changed = item(diff, "CHANGED");
        assertThat(changed.get("definitionId")).isEqualTo(definition.id());
        assertThat(((List<?>) changed.get("affectedAssetIds")).stream().map(Object::toString).toList()).contains(test.id(), sql.id(), step.id(), scene.id());
        assertThat(objects(changed.get("fields"))).anySatisfy(field -> assertThat(field.get("path").toString()).contains("requestBody", "uid"));
        assertThat(object(request("GET", base(p.id()) + "/" + diff.get("id"), null)).get("items")).isEqualTo(diff.get("items"));
        var result = accept(p.id(), diff, List.of(changed.get("id").toString()), "accept");
        assertThat(map(objects(result.get("assets")).getFirst()).get("id")).isEqualTo(definition.id());
        assertThat(assets.get(p.id(), test.id())).isEqualTo(test);
        assertThat(accept(p.id(), diff, List.of(changed.get("id").toString()), "accept")).isEqualTo(result);
        assertThat(request("POST", base(p.id()) + "/" + diff.get("id") + "/apply", Map.of("itemIds", List.of(item(diff, "ADDED").get("id")), "idempotencyKey", "accept")).statusCode()).isEqualTo(409);
        assertThat(assets.get(p.id(), definition.id()).version()).isEqualTo("2");

        var ambiguousProject = project();
        Asset first = assets.create(ambiguousProject.id(), AssetType.API_DEFINITION, null, "重复定义 1", definition.data(), "MANUAL");
        Asset second = assets.create(ambiguousProject.id(), AssetType.API_DEFINITION, null, "重复定义 2", definition.data(), "MANUAL");
        var duplicateInput = preview(ambiguousProject.id(), "API_DEFINITION", "json", document("uid", true));
        var ambiguous = diff(ambiguousProject.id(), duplicateInput, Map.of());
        assertThat(item(ambiguous, "AMBIGUOUS").get("matchCandidates")).isEqualTo(List.of(first.id(), second.id()));
        var mapped = diff(ambiguousProject.id(), duplicateInput, Map.of("api_1", first.id()));
        assertThat(item(mapped, "CHANGED").get("definitionId")).isEqualTo(first.id());
        assertThat(objects(mapped.get("items"))).noneMatch(i -> "AMBIGUOUS".equals(i.get("kind")));
        var foreign = project();
        assertThat(request("POST", base(foreign.id()) + "/preview", Map.of("importId", next.get("id"))).statusCode()).isEqualTo(404);
        assertThat(request("GET", base(foreign.id()) + "/" + diff.get("id"), null).statusCode()).isEqualTo(404);
        assertThat(request("POST", base(ambiguousProject.id()) + "/preview", Map.of("importId", duplicateInput.get("id"), "definitionMappings", Map.of("api_1", definition.id()))).statusCode()).isEqualTo(404);
    }

    @Test void selectedDefinitionUpdatesAreAtomicAndReferencedRemovalCannotDeleteCases() throws Exception {
        var p = project();
        var initial = preview(p.id(), "API_DEFINITION", "json", document("userId", false));
        var ids = map(apply(p.id(), initial).get("keyToId"));
        Asset original = assets.get(p.id(), ids.get("api_1").toString());
        Asset removed = assets.get(p.id(), ids.get("api_3").toString());
        Asset kept = assets.create(p.id(), AssetType.API_CASE, null, "保留历史用例", Map.of("apiDefinitionId", removed.id(), "path", "/old"), "MANUAL");
        var preview = preview(p.id(), "API_DEFINITION", "json", document("uid", true));
        var diff = diff(p.id(), preview, Map.of());
        var changed = item(diff, "CHANGED"); var added = item(diff, "ADDED"); var deleted = item(diff, "REMOVED");
        var conflict = request("POST", base(p.id()) + "/" + diff.get("id") + "/apply", Map.of("itemIds", List.of(added.get("id"), deleted.get("id")), "idempotencyKey", "blocked-delete"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(assets.get(p.id(), removed.id())).isEqualTo(removed);
        assertThat(assets.get(p.id(), kept.id())).isEqualTo(kept);
        assertThat(assets.list(p.id(), AssetType.API_DEFINITION, null, "", 0, 100).items()).hasSize(3);
        Asset human = assets.update(p.id(), original.id(), original.version(), "人工接口名称", Map.of(), null, "MANUAL");
        assertThat(request("POST", base(p.id()) + "/" + diff.get("id") + "/apply", Map.of("itemIds", List.of(added.get("id"), changed.get("id")), "idempotencyKey", "stale")).statusCode()).isEqualTo(409);
        assertThat(assets.get(p.id(), original.id())).isEqualTo(human);
        assertThat(assets.list(p.id(), AssetType.API_DEFINITION, null, "", 0, 100).items()).hasSize(3);
    }

    @Test void healingIsPreviewOnlyMultiRoundScopedToCurrentHumanStateAndConflictsAtomically() throws Exception {
        var p = project();
        var initial = preview(p.id(), "API_DEFINITION", "json", document("userId", false));
        var ids = map(apply(p.id(), initial).get("keyToId"));
        Asset definition = assets.get(p.id(), ids.get("api_1").toString());
        Asset target = assets.create(p.id(), AssetType.API_CASE, null, "资料请求", Map.of("apiDefinitionId", definition.id(), "method", "POST", "path", "/users", "bodyType", "JSON", "body", Map.of("userId", "manual-value")), "MANUAL");
        Asset protectedCase = assets.create(p.id(), AssetType.API_CASE, null, "已确认人工用例", Map.of("apiDefinitionId", definition.id(), "path", "/users"), "MANUAL");
        protectedCase = assets.update(p.id(), protectedCase.id(), protectedCase.version(), null, Map.of(), true, "MANUAL");
        Asset sibling = assets.create(p.id(), AssetType.API_CASE, null, "无关用例", Map.of("path", "/unrelated"), "MANUAL");
        var next = preview(p.id(), "API_DEFINITION", "json", document("uid", true));
        var diff = diff(p.id(), next, Map.of());
        accept(p.id(), diff, List.of(item(diff, "CHANGED").get("id").toString()), "document");
        String conversation = null;
        for (int round = 1; round <= 3; round++) {
            model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, Map.of("body", Map.of("uid", "manual-value", "round", round)))))));
            Map<String, Object> input = new LinkedHashMap<>(Map.of("instruction", "只修复 uid，本轮 " + round, "idempotencyKey", "heal-" + round));
            if (conversation != null) input.put("conversationId", conversation);
            var response = request("POST", base(p.id()) + "/" + diff.get("id") + "/heal", input);
            assertThat(response.statusCode()).isEqualTo(200);
            var submitted = object(response); conversation = submitted.get("conversationId").toString();
            assertThat(object(request("POST", base(p.id()) + "/" + diff.get("id") + "/heal", input))).isEqualTo(submitted);
            Job done = terminal(p.id(), submitted.get("jobId").toString());
            assertThat(done.status()).withFailMessage("%s", done.error()).isEqualTo("SUCCEEDED");
            assertThat(done.result().get("status")).isEqualTo("PREVIEW");
            assertThat(assets.get(p.id(), target.id())).isEqualTo(target);
            String changeId = done.result().get("changeSetId").toString();
            changes.apply(p.id(), changeId, AiGenerationService.changeIds(changes.get(p.id(), changeId)));
            target = assets.get(p.id(), target.id());
            if (round == 1) target = assets.update(p.id(), target.id(), target.version(), null, Map.of("timeoutMs", 23456), null, "MANUAL");
            assertThat(assets.get(p.id(), protectedCase.id())).isEqualTo(protectedCase);
            assertThat(assets.get(p.id(), sibling.id())).isEqualTo(sibling);
        }
        assertThat(target.data().get("timeoutMs").toString()).isEqualTo("23456");
        assertThat(conversations.messages(p.id(), conversation)).hasSize(6);
        assertThat(model.requests.getLast()).contains("userId", "uid", "23456", "manual-value");
        var otherDiff = diff(p.id(), next, Map.of());
        assertThat(request("POST", base(p.id()) + "/" + otherDiff.get("id") + "/heal", Map.of("instruction", "拒绝跨会话", "conversationId", conversation, "idempotencyKey", "cross")).statusCode()).isEqualTo(409);
        model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, Map.of("timeoutMs", 54321))))));
        var submitted = object(request("POST", base(p.id()) + "/" + diff.get("id") + "/heal", Map.of("instruction", "调整超时", "conversationId", conversation, "idempotencyKey", "conflict")));
        var done = terminal(p.id(), submitted.get("jobId").toString());
        String changeId = done.result().get("changeSetId").toString();
        Asset human = assets.update(p.id(), target.id(), target.version(), "人工保留", Map.of(), null, "MANUAL");
        assertThatThrownBy(() -> changes.apply(p.id(), changeId, AiGenerationService.changeIds(changes.get(p.id(), changeId)))).isInstanceOf(com.aitest.common.Problem.class);
        assertThat(assets.get(p.id(), target.id())).isEqualTo(human);
    }

    @Test void originalImpactRemainsAuditableAfterHumanReferenceChanges() throws Exception {
        var p = project();
        var initial = preview(p.id(), "API_DEFINITION", "json", document("userId", false));
        var ids = map(apply(p.id(), initial).get("keyToId"));
        String definitionId = ids.get("api_1").toString();
        Asset target = assets.create(p.id(), AssetType.API_CASE, null, "原关联用例", Map.of("apiDefinitionId", definitionId, "path", "/users"), "MANUAL");
        var diff = diff(p.id(), preview(p.id(), "API_DEFINITION", "json", document("uid", true)), Map.of());
        assets.update(p.id(), target.id(), target.version(), null, Map.of("apiDefinitionId", ""), null, "MANUAL");
        var current = item(object(request("GET", base(p.id()) + "/" + diff.get("id"), null)), "CHANGED");
        assertThat(current.get("originalAffectedAssetIds")).isEqualTo(List.of(target.id()));
        assertThat((List<?>) current.get("affectedAssetIds")).isEmpty();
        assertThat(objects(current.get("originalLinks"))).contains(Map.of("fromId", target.id(), "toId", definitionId, "field", "apiDefinitionId"));
    }

    @Test void referencedSchemasParameterRequiredTypesResponseAndAuthChangesAreLocalToAnOperation() throws Exception {
        var p = project();
        var oldDoc = json.map(document("userId", false));
        var oldOperation = map(map(map(oldDoc.get("paths")).get("/users")).get("post"));
        oldOperation.put("parameters", List.of(Map.of("in", "query", "name", "limit", "required", false, "schema", Map.of("type", "integer"))));
        oldOperation.put("requestBody", Map.of("content", Map.of("application/json", Map.of("schema", Map.of("$ref", "#/components/schemas/User")))));
        oldDoc.put("security", List.of(Map.of("Auth", List.of())));
        oldDoc.put("components", Map.of("schemas", Map.of("User", Map.of("type", "object", "properties", Map.of("userId", Map.of("type", "integer")))), "securitySchemes", Map.of("Auth", Map.of("type", "http", "scheme", "bearer"))));
        var initial = preview(p.id(), "API_DEFINITION", "json", json.write(oldDoc)); apply(p.id(), initial);
        var newDoc = json.copy(oldDoc);
        var operation = map(map(map(newDoc.get("paths")).get("/users")).get("post"));
        operation.put("parameters", List.of(Map.of("in", "query", "name", "limit", "required", true, "schema", Map.of("type", "string"))));
        operation.put("responses", Map.of("200", Map.of("description", "OK", "content", Map.of("application/json", Map.of("schema", Map.of("type", "string"))))));
        operation.put("security", List.of());
        map(map(newDoc.get("components")).get("schemas")).put("User", Map.of("type", "object", "required", List.of("uid"), "properties", Map.of("uid", Map.of("type", "string"))));
        var diff = diff(p.id(), preview(p.id(), "API_DEFINITION", "json", json.write(newDoc)), Map.of());
        assertThat(objects(diff.get("items"))).filteredOn(i -> "CHANGED".equals(i.get("kind"))).hasSize(1);
        var paths = objects(item(diff, "CHANGED").get("fields")).stream().map(i -> i.get("path").toString()).toList();
        assertThat(paths).contains("/parameters/query:limit/required", "/parameters/query:limit/schema/type", "/requestBody/content/application~1json/schema/properties/uid", "/responses/200/content", "/security");
    }

    @Test void healingCannotInventCredentialsAndEvidenceChangesInvalidateAnUnacceptedPreview() throws Exception {
        var p = project();
        var initial = preview(p.id(), "API_DEFINITION", "json", document("userId", false));
        var ids = map(apply(p.id(), initial).get("keyToId"));
        String definitionId = ids.get("api_1").toString();
        Asset target = assets.create(p.id(), AssetType.API_CASE, null, "人工值必须保留", Map.of("apiDefinitionId", definitionId, "path", "/users", "bodyType", "JSON", "body", Map.of("userId", "keep-value")), "MANUAL");
        var diff = diff(p.id(), preview(p.id(), "API_DEFINITION", "json", document("uid", true)), Map.of());
        accept(p.id(), diff, List.of(item(diff, "CHANGED").get("id").toString()), "accept");
        model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, Map.of("body", Map.of("uid", "keep-value", "password", "invented-credential")))))));
        var bad = object(request("POST", base(p.id()) + "/" + diff.get("id") + "/heal", Map.of("instruction", "只改字段名", "idempotencyKey", "bad")));
        assertThat(terminal(p.id(), bad.get("jobId").toString()).status()).isEqualTo("FAILED");
        assertThat(assets.get(p.id(), target.id())).isEqualTo(target);
        model.enqueue(json.write(Map.of("changes", List.of(new AiChangeSetService.Proposal("MODIFY", target.type(), target.id(), null, null, target.version(), null, Map.of("body", Map.of("uid", "keep-value")))))));
        var good = object(request("POST", base(p.id()) + "/" + diff.get("id") + "/heal", Map.of("instruction", "仅修复 uid", "idempotencyKey", "good")));
        var done = terminal(p.id(), good.get("jobId").toString()); assertThat(done.status()).isEqualTo("SUCCEEDED");
        String changeId = done.result().get("changeSetId").toString();
        Asset definition = assets.get(p.id(), definitionId);
        assets.update(p.id(), definitionId, definition.version(), null, Map.of("path", "/human-new-path"), null, "MANUAL");
        assertThatThrownBy(() -> changes.apply(p.id(), changeId, AiGenerationService.changeIds(changes.get(p.id(), changeId)))).isInstanceOf(com.aitest.common.Problem.class).hasMessageContaining("依据");
        assertThat(assets.get(p.id(), target.id())).isEqualTo(target);
    }

    private Job terminal(String project, String id) { await().atMost(Duration.ofSeconds(40)).until(() -> jobs.get(project, id).terminal()); return jobs.get(project, id); }
    private String base(String project) { return "/api/projects/" + project + "/api-diffs"; }
    private Map<String, Object> diff(String project, Map<String, Object> preview, Map<String, String> mappings) throws Exception {
        var response = request("POST", base(project) + "/preview", Map.of("importId", preview.get("id"), "definitionMappings", mappings));
        assertThat(response.statusCode()).as(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
        return object(response);
    }
    private Map<String, Object> accept(String project, Map<String, Object> diff, List<String> items, String key) throws Exception {
        var response = request("POST", base(project) + "/" + diff.get("id") + "/apply", Map.of("itemIds", items, "idempotencyKey", key));
        assertThat(response.statusCode()).as(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200); return object(response);
    }
    private Map<String, Object> item(Map<String, Object> diff, String kind) { return objects(diff.get("items")).stream().filter(i -> kind.equals(i.get("kind"))).findFirst().orElseThrow(); }
    private String document(String property, boolean newer) {
        var requestSchema = Map.of("type", "object", "required", List.of(property), "properties", Map.of(property, Map.of("type", "string")));
        var operation = Map.of("operationId", "updateUser", "summary", "更新用户", "requestBody", Map.of("required", true, "content", Map.of("application/json", Map.of("schema", requestSchema))), "responses", Map.of("200", Map.of("description", "OK")));
        Map<String, Object> paths = new LinkedHashMap<>(); paths.put("/users", Map.of("post", operation));
        paths.put("/health", Map.of("get", Map.of("operationId", "health", "responses", Map.of("200", Map.of("description", "OK")))));
        paths.put(newer ? "/new" : "/old", Map.of("get", Map.of("operationId", newer ? "newOperation" : "oldOperation", "responses", Map.of("200", Map.of("description", "OK")))));
        return json.write(Map.of("openapi", "3.0.3", "info", Map.of("title", "资料接口", "version", newer ? "2" : "1"), "paths", paths));
    }
}
