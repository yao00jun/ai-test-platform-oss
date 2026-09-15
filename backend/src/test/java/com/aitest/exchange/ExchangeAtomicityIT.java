package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExchangeAtomicityIT extends ExchangeHttpTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void malformedLateRowAndCrossProjectMappingLeaveEveryExistingRecordUntouched() throws Exception {
        var project = project();
        Asset original = assets.create(project.id(), AssetType.FUNCTIONAL_CASE, null, "人工确认", Map.of("remark", "@mail.example 只是文本"), "MANUAL");
        original = assets.update(project.id(), original.id(), original.version(), null, Map.of(), true, "MANUAL");
        List<Asset> before = assets.all(project.id());
        String malformed = bundle(List.of(node("ok", "FUNCTIONAL_CASE", "Valid", null, Map.of("priority", "P0"), Map.of()),
                node("bad", "FUNCTIONAL_CASE", "Bad", null, Map.of("priority", "P8"), Map.of())));
        var preview = preview(project.id(), "FUNCTIONAL_CASE", "json", malformed);
        assertThat(preview.get("status")).isEqualTo("INVALID");
        assertThat(objects(preview.get("errors"))).anySatisfy(error -> assertThat(error).containsEntry("row", 2).containsEntry("field", "priority"));
        var refused = request("POST", "/api/projects/" + project.id() + "/imports/" + preview.get("id") + "/apply", Map.of());
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(assets.all(project.id())).isEqualTo(before);

        var other = project(); var external = assets.create(other.id(), AssetType.API_CASE, null, "Other", Map.of("path", "/other"), "MANUAL");
        String cross = json.write(Map.of("formatVersion", "aitest.exchange/v1", "nodes", List.of(node("scenario", "SCENARIO", "Scenario", null, Map.of(), Map.of()),
                        node("step", "SCENARIO_STEP", "HTTP", "scenario", Map.of("stepType", "HTTP"), Map.of("targetId", "external_api"))),
                "externalReferences", Map.of("external_api", Map.of("type", "API_CASE", "name", "User binding"))));
        preview = preview(project.id(), "SCENARIO", "json", "cross.json", cross.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("referenceMappings", json.write(Map.of("external_api", external.id()))));
        assertThat(preview.get("status")).isEqualTo("INVALID");
        assertThat(objects(preview.get("errors"))).anySatisfy(error -> assertThat(error.get("field").toString()).contains("reference"));
        assertThat(assets.all(project.id())).isEqualTo(before);
        assertThat(assets.get(project.id(), original.id())).isEqualTo(original);
    }

    @Test
    void completeLocalGraphGetsFreshIdsAndApplyIsIdempotentWhileLiteralAtTextIsPreserved() throws Exception {
        var project = project();
        var preview = preview(project.id(), "SCENARIO", "json", bundle(List.of(
                node("step", "SCENARIO_STEP", "HTTP first", "scenario", Map.of("stepType", "HTTP"), Map.of("targetId", "api")),
                node("scenario", "SCENARIO", "Scenario", null, Map.of("description", "写给 @ops 的说明"), Map.of()),
                node("api", "API_CASE", "GET", null, Map.of("path", "/health"), Map.of()))));
        assertThat(objects(preview.get("errors"))).isEmpty();
        assertThat(assets.all(project.id())).isEmpty();
        var applyRequest = java.net.http.HttpRequest.newBuilder(uri("/api/projects/" + project.id() + "/imports/" + preview.get("id") + "/apply"))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
        var firstFuture = http.sendAsync(applyRequest, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        var secondFuture = http.sendAsync(applyRequest, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        var firstResponse = firstFuture.join(); var secondResponse = secondFuture.join();
        assertThat(firstResponse.statusCode()).isEqualTo(200); assertThat(secondResponse.statusCode()).isEqualTo(200);
        var first = object(firstResponse); var second = object(secondResponse);
        assertThat(second).isEqualTo(first);
        assertThat(apply(project.id(), preview)).isEqualTo(first);
        var ids = map(first.get("keyToId"));
        assertThat(ids).hasSize(3); assertThat(ids.values()).doesNotContain("step", "scenario", "api");
        var step = assets.get(project.id(), ids.get("step").toString());
        assertThat(step.parentId()).isEqualTo(ids.get("scenario")); assertThat(step.data().get("targetId")).isEqualTo(ids.get("api"));
        assertThat(assets.get(project.id(), ids.get("scenario").toString()).data().get("description")).isEqualTo("写给 @ops 的说明");
        assertThat(assets.all(project.id())).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset_relation WHERE from_id=? AND to_id=?", Integer.class, step.id(), ids.get("api"))).isEqualTo(1);
    }

    @Test
    void missingDependenciesArePreflightErrorsAndChangedExternalBindingsAreRecheckedAtApply() throws Exception {
        var project = project();
        var missing = preview(project.id(), "SCENARIO", "json", bundle(List.of(node("scenario", "SCENARIO", "Scenario", null, Map.of(), Map.of()),
                node("step", "SCENARIO_STEP", "HTTP", "scenario", Map.of("stepType", "HTTP"), Map.of("targetId", "absent")))));
        assertThat(missing.get("status")).isEqualTo("INVALID");
        assertThat(objects(missing.get("errors"))).anySatisfy(error -> assertThat(error.get("field").toString()).contains("targetId"));
        assertThat(assets.all(project.id())).isEmpty();

        var external = assets.create(project.id(), AssetType.API_CASE, null, "Bound API", Map.of("path", "/health"), "MANUAL");
        String portable = json.write(Map.of("formatVersion", ExchangeBundle.VERSION,
                "nodes", List.of(node("scenario", "SCENARIO", "Scenario", null, Map.of(), Map.of()),
                        node("step", "SCENARIO_STEP", "HTTP", "scenario", Map.of("stepType", "HTTP"), Map.of("targetId", "external_api"))),
                "externalReferences", Map.of("external_api", Map.of("type", "API_CASE", "name", "User binding"))));
        var ready = preview(project.id(), "SCENARIO", "json", "bound.json", portable.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Map.of("referenceMappings", json.write(Map.of("external_api", external.id()))));
        assertThat(ready.get("status")).isEqualTo("READY");
        assertThat(map(map(ready.get("metadata")).get("externalReferences"))).containsKey("external_api");
        var reopened = object(request("GET", "/api/projects/" + project.id() + "/imports/" + ready.get("id"), null));
        assertThat(map(reopened.get("metadata")).get("referenceMappings")).isEqualTo(Map.of("external_api", external.id()));
        assets.delete(project.id(), external.id(), external.version());
        var refused = request("POST", "/api/projects/" + project.id() + "/imports/" + ready.get("id") + "/apply", Map.of());
        assertThat(refused.statusCode()).isEqualTo(422); assertThat(object(refused)).containsEntry("code", "IMPORT_INVALID");
        assertThat(assets.all(project.id())).isEmpty();
    }

    @Test
    void previewPersistsOnlyEncryptedPrivatePayloadAndRedactedPublicRows() throws Exception {
        var project = project(); String secret = "private-token-7c083";
        var preview = preview(project.id(), "API_CASE", "curl", "curl 'https://api.example.test/me' -H 'Authorization: Bearer " + secret + "'");
        assertThat(json.write(preview)).doesNotContain(secret);
        var stored = jdbc.queryForMap("SELECT parsed_rows,errors,warnings,metadata,private_payload FROM import_job WHERE id=?", preview.get("id"));
        assertThat(stored.get("private_payload").toString()).startsWith("enc:v1:").doesNotContain(secret);
        for (String field : List.of("parsed_rows", "errors", "warnings", "metadata")) assertThat(String.valueOf(stored.get(field))).doesNotContain(secret);
        assertThat(preview.get("checksum").toString()).hasSize(64);
        var fetched = request("GET", "/api/projects/" + project.id() + "/imports/" + preview.get("id"), null);
        assertThat(fetched.statusCode()).isEqualTo(200); assertThat(object(fetched)).isEqualTo(preview);
        apply(project.id(), preview);
        assertThat(assets.all(project.id())).hasSize(2);
    }
}
