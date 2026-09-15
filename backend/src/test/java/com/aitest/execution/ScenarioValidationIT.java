package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ScenarioValidationIT extends ExchangeHttpTest {
    @Test void staticValidationDetectsProducerOrderAndStepOverrideLifetime() throws Exception {
        String project = project().id();
        Asset producer = assets.create(project, AssetType.API_CASE, null, "登录", Map.of("path", "/login", "extractors", List.of(Map.of("variable", "token", "jsonpath", "$.token"))), "MANUAL");
        Asset consumer = assets.create(project, AssetType.API_CASE, null, "下单", Map.of("path", "/orders", "headers", Map.of("Authorization", "Bearer ${token}")), "MANUAL");
        Asset scenario = assets.create(project, AssetType.SCENARIO, null, "调用链", Map.of(), "MANUAL");
        Asset first = assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "错误的顺序", Map.of("targetId", consumer.id()), "MANUAL");
        Asset second = assets.create(project, AssetType.SCENARIO_STEP, scenario.id(), "登录", Map.of("targetId", producer.id()), "MANUAL");
        var invalid = validate(project, scenario.id(), Map.of());
        assertThat(invalid).containsEntry("valid", false);
        assertThat(objects(invalid.get("errors"))).anySatisfy(error -> assertThat(error).containsEntry("assetId", consumer.id()).containsEntry("variable", "token"));
        assets.reorder(project, AssetType.SCENARIO_STEP, scenario.id(), List.of(new AssetService.OrderItem(second.id(), second.version()), new AssetService.OrderItem(first.id(), first.version())));
        assertThat(validate(project, scenario.id(), Map.of())).containsEntry("valid", true);

        Asset temp = assets.create(project, AssetType.API_CASE, null, "临时参数", Map.of("path", "/items/${temporary}"), "MANUAL");
        Asset local = assets.create(project, AssetType.SCENARIO, null, "步骤变量作用域", Map.of(), "MANUAL");
        assets.create(project, AssetType.SCENARIO_STEP, local.id(), "局部覆盖", Map.of("targetId", temp.id(), "variables", Map.of("temporary", 1)), "MANUAL");
        assets.create(project, AssetType.SCENARIO_STEP, local.id(), "覆盖已过期", Map.of("targetId", temp.id()), "MANUAL");
        assertThat(objects(validate(project, local.id(), Map.of()).get("errors"))).singleElement().satisfies(error -> assertThat(error).containsEntry("variable", "temporary"));
    }
    @Test void datasetPathsAndVariableCyclesAreCheckedWithoutRunningNetworkRequests() throws Exception {
        String project = project().id();
        Asset api = assets.create(project, AssetType.API_CASE, null, "金额校验", Map.of("path", "http://127.0.0.1:1/${row.amount}/${seed}"), "MANUAL");
        Asset environment = assets.create(project, AssetType.ENVIRONMENT, null, "变量环境", Map.of("baseUrl", "http://127.0.0.1:1", "variables", Map.of("seed", "${other}", "other", "${seed}")), "MANUAL");
        Asset dataset = assets.create(project, AssetType.DATASET, null, "数据", Map.of("columns", List.of("amount"), "rows", List.of(Map.of("amount", 42))), "MANUAL");
        var result = validate(project, api.id(), Map.of("environmentId", environment.id(), "datasetId", dataset.id()));
        assertThat(result).containsEntry("valid", false);
        assertThat(objects(result.get("errors"))).anySatisfy(error -> assertThat(error.get("message").toString()).contains("循环"));
        environment = assets.update(project, environment.id(), environment.version(), null, Map.of("variables", Map.of("seed", "fixed")), null, "MANUAL");
        assertThat(validate(project, api.id(), Map.of("environmentId", environment.id(), "datasetId", dataset.id()))).containsEntry("valid", true);
    }
    private Map<String, Object> validate(String project, String id, Map<String, Object> body) throws Exception {
        var response = request("POST", "/api/projects/" + project + "/assets/" + id + "/validate-execution", body);
        assertThat(response.statusCode()).as(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200); return object(response);
    }
}
