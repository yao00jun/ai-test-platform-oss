package com.aitest.exchange;

import com.aitest.asset.AssetType;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ExchangeExportIT extends ExchangeHttpTest {
    @Test
    void unsupportedLossyOpenApiCollisionsReturnActionable422InsteadOfInternalError() throws Exception {
        var project = project();
        var first = assets.create(project.id(), AssetType.API_CASE, null, "One", Map.of("path", "/same"), "MANUAL");
        var second = assets.create(project.id(), AssetType.API_CASE, null, "Two", Map.of("path", "/same"), "MANUAL");
        var response = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "API_CASE", "assetIds", List.of(first.id(), second.id()), "format", "openapi-json"));
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(object(response)).containsEntry("code", "EXPORT_INVALID");
    }

    @Test
    void selectedCaseExportIncludesAncestorModulesAndItsStepsButNoUnselectedSiblings() throws Exception {
        var project = project(); var module = assets.create(project.id(), AssetType.MODULE, null, "Module", Map.of(), "MANUAL");
        var selected = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, module.id(), "Selected", Map.of(), "MANUAL");
        assets.create(project.id(), AssetType.FUNCTIONAL_STEP, selected.id(), "Step", Map.of("step", "Click"), "MANUAL");
        assets.create(project.id(), AssetType.FUNCTIONAL_CASE, module.id(), "Unselected", Map.of(), "MANUAL");
        var exported = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "FUNCTIONAL_CASE", "assetIds", List.of(selected.id()), "format", "json"));
        assertThat(exported.statusCode()).isEqualTo(200);
        assertThat(objects(object(exported).get("nodes"))).extracting(n -> n.get("name")).containsExactlyInAnyOrder("Module", "Selected", "Step");
    }

    @Test
    void projectAndScenarioPortableArchivesCreateNewCrossReferencesAndOmitCredentials() throws Exception {
        var project = project();
        var env = assets.create(project.id(), AssetType.ENVIRONMENT, null, "Environment", Map.of("baseUrl", "https://example.invalid", "headers", Map.of("Authorization", "Bearer sample-private-env")), "MANUAL");
        var db = assets.create(project.id(), AssetType.DATABASE_SOURCE, null, "Database", Map.of("jdbcUrl", "jdbc:mysql://example.invalid:3306/demo", "username", "demo", "password", "sample-private-db", "environmentId", env.id()), "MANUAL");
        var api = assets.create(project.id(), AssetType.API_CASE, null, "API", Map.of("method", "POST", "path", "/orders", "bodyType", "JSON", "body", Map.of("message", "@ops, 中文", "password", "sample-private-api")), "MANUAL");
        var sql = assets.create(project.id(), AssetType.SQL_VALIDATION, null, "SQL", Map.of("databaseSourceId", db.id(), "sql", "SELECT 1 AS ready"), "MANUAL");
        var scenario = assets.create(project.id(), AssetType.SCENARIO, null, "Scenario", Map.of(), "MANUAL");
        assets.create(project.id(), AssetType.SCENARIO_STEP, scenario.id(), "HTTP", Map.of("stepType", "HTTP", "targetId", api.id()), "MANUAL");
        assets.create(project.id(), AssetType.SCENARIO_STEP, scenario.id(), "SQL", Map.of("stepType", "SQL", "targetId", sql.id()), "MANUAL");
        for (String type : List.of("SCENARIO", "PROJECT")) for (String format : List.of("json", "yaml", "zip")) {
            var response = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", type, "assetIds", type.equals("PROJECT") ? List.of(project.id()) : List.of(scenario.id()), "format", format));
            assertThat(response.statusCode()).isEqualTo(200);
            String raw = format.equals("zip") ? new String(ExchangeIO.unzip(response.body(), "export.zip").get("bundle.json"), StandardCharsets.UTF_8) : new String(response.body(), StandardCharsets.UTF_8);
            assertThat(raw).doesNotContain("sample-private-env", "sample-private-db", "sample-private-api", project.id(), scenario.id(), api.id(), sql.id());
            var destination = project(); var preview = preview(destination.id(), type, format, "export." + format, response.body(), Map.of());
            assertThat(objects(preview.get("errors"))).as(type + "/" + format).isEmpty(); apply(destination.id(), preview);
            var copy = assets.all(destination.id()); assertThat(copy).hasSize(7);
            var importedScenario = copy.stream().filter(a -> a.type() == AssetType.SCENARIO).findFirst().orElseThrow();
            for (var step : assets.children(destination.id(), importedScenario.id())) assertThat(copy).anySatisfy(a -> assertThat(a.id()).isEqualTo(step.data().get("targetId")));
        }
    }

    @Test
    void apiOpenapiAndCurlExportsCanBeReimportedWithBodyAndQueryIntact() throws Exception {
        var project = project();
        var api = assets.create(project.id(), AssetType.API_CASE, null, "创建订单", Map.of("method", "POST", "path", "/orders", "headers", Map.of("Content-Type", "application/json"), "queryParams", Map.of("term", "a b"), "bodyType", "JSON", "body", Map.of("note", "单引号'和中文\n多行", "count", 2)), "MANUAL");
        for (String format : List.of("openapi-json", "openapi-yaml", "curl")) {
            var response = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "API_CASE", "assetIds", List.of(api.id()), "format", format));
            assertThat(response.statusCode()).isEqualTo(200);
            var preview = preview(project.id(), "API_DEFINITION", format, "api." + format, response.body(), Map.of());
            assertThat(objects(preview.get("errors"))).as(format).isEmpty();
            var data = map(objects(preview.get("nodes")).getFirst().get("data"));
            assertThat(data).containsEntry("method", "POST").containsEntry("path", "/orders");
            assertThat(map(data.get("queryParams"))).containsEntry("term", "a b"); assertThat(map(data.get("body"))).containsEntry("note", "单引号'和中文\n多行").containsEntry("count", 2);
        }
    }
}
