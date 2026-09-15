package com.aitest.exchange;

import com.aitest.asset.AssetType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetMappingBoundaryIT extends ExchangeHttpTest {
    @Autowired JdbcTemplate jdbc;

    @Test void mappingsAndTypesUseTheWorkbookUnionButOnlyTransformTheRelevantSheets() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var users = book.createSheet("users"); users.createRow(0).createCell(0).setCellValue("user_id"); users.createRow(1).createCell(0).setCellValue("001");
            var orders = book.createSheet("orders"); orders.createRow(0).createCell(0).setCellValue("order_id"); orders.createRow(1).createCell(0).setCellValue("A002");
            book.write(out); bytes = out.toByteArray();
        }
        var project = project();
        var preview = preview(project.id(), "DATASET", "xlsx", "two-sheets.xlsx", bytes, Map.of("columnMappings", "{\"user_id\":\"id\"}", "columnTypes", "{\"user_id\":\"INTEGER\"}"));
        assertThat(preview.get("status")).as(json.write(preview.get("errors"))).isEqualTo("READY");
        var nodes = objects(preview.get("nodes"));
        assertThat(map(nodes.getFirst().get("data"))).containsEntry("columns", List.of("id")).containsEntry("rows", List.of(Map.of("id", 1)));
        assertThat(map(nodes.get(1).get("data"))).containsEntry("columns", List.of("order_id")).containsEntry("rows", List.of(Map.of("order_id", "A002")));
        assertThat(map(preview.get("metadata"))).containsEntry("originalColumns", List.of("user_id", "order_id")).containsEntry("mappingCapabilities", Map.of("renameColumns", true, "convertTypes", true));
        var legacyMetadata = new LinkedHashMap<>(map(preview.get("metadata"))); legacyMetadata.remove("mappingCapabilities");
        jdbc.update("UPDATE import_job SET metadata=? WHERE id=? AND project_id=?", json.write(legacyMetadata), preview.get("id"), project.id());
        var reopened = object(request("GET", "/api/projects/" + project.id() + "/imports/" + preview.get("id"), null));
        assertThat(map(reopened.get("metadata"))).containsEntry("columnMappings", Map.of("user_id", "id")).containsEntry("mappingCapabilities", Map.of("renameColumns", true, "convertTypes", true));
        apply(project.id(), reopened);
        assertThat(assets.all(project.id())).hasSize(2);
        var invalid = preview(project.id(), "DATASET", "xlsx", "unknown.xlsx", bytes, Map.of("columnMappings", "{\"unknown\":\"id\"}"));
        assertThat(invalid.get("status")).isEqualTo("INVALID");
        assertThat(objects(invalid.get("errors"))).anySatisfy(issue -> assertThat(issue.get("field")).isEqualTo("columnMappings.unknown"));
    }

    @ParameterizedTest @ValueSource(strings = {"json", "yaml", "zip", "csv", "xlsx"})
    void portableFilesRejectUnimplementedMappingsAndTypedFilesRejectTypeCoercion(String format) throws Exception {
        var project = project();
        var dataset = assets.create(project.id(), AssetType.DATASET, null, "typed", Map.of("columns", List.of("id", "payload"), "rows", List.of(Map.of("id", "001", "payload", Map.of("enabled", true)))), "MANUAL");
            var exported = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "DATASET", "assetIds", List.of(dataset.id()), "format", format));
            assertThat(exported.statusCode()).isEqualTo(200);
            boolean tabular = format.equals("csv") || format.equals("xlsx");
            var unsupported = preview(project.id(), "DATASET", format, "typed." + format, exported.body(), tabular ? Map.of("columnTypes", "{\"id\":\"NUMBER\"}") : Map.of("columnMappings", "{\"id\":\"renamed\"}"));
            assertThat(unsupported.get("status")).as(format).isEqualTo("INVALID");
            assertThat(objects(unsupported.get("errors"))).anySatisfy(issue -> assertThat(issue.get("field")).isEqualTo(tabular ? "columnTypes" : "columnMappings"));
            var accepted = preview(project.id(), "DATASET", format, "valid." + format, exported.body(), tabular ? Map.of("columnMappings", "{\"id\":\"renamed\"}") : Map.of());
            assertThat(accepted.get("status")).as(format).isEqualTo("READY");
            assertThat(map(accepted.get("metadata")).get("mappingCapabilities")).isEqualTo(Map.of("renameColumns", tabular, "convertTypes", false));
            var row = objects(map(objects(accepted.get("nodes")).getFirst().get("data")).get("rows")).getFirst();
            assertThat(row).containsEntry(tabular ? "renamed" : "id", "001").containsEntry("payload", Map.of("enabled", true));
        assertThat(assets.all(project.id())).containsExactly(dataset);
    }

    @Test void ordinaryCsvTransformsValuesAndRejectsUnknownDeclarations() throws Exception {
        var project = project(); byte[] bytes = "amount,active\r\n007,true\r\n".getBytes(StandardCharsets.UTF_8);
        var transformed = preview(project.id(), "DATASET", "csv", "ordinary.csv", bytes, Map.of("columnMappings", "{\"amount\":\"total\"}", "columnTypes", "{\"total\":\"INTEGER\",\"active\":\"BOOLEAN\"}"));
        assertThat(transformed.get("status")).isEqualTo("READY");
        assertThat(map(transformed.get("metadata")).get("mappingCapabilities")).isEqualTo(Map.of("renameColumns", true, "convertTypes", true));
        assertThat(objects(map(objects(transformed.get("nodes")).getFirst().get("data")).get("rows"))).containsExactly(Map.of("total", 7, "active", true));
        var unknown = preview(project.id(), "DATASET", "csv", "unknown.csv", bytes, Map.of("columnTypes", "{\"absent\":\"NUMBER\"}"));
        assertThat(unknown.get("status")).isEqualTo("INVALID");
        assertThat(assets.all(project.id())).isEmpty();
    }

    @Test void legacyReadyPreviewsCannotApplyPreviouslyIgnoredTypeChoices() throws Exception {
        var project = project();
        var dataset = assets.create(project.id(), AssetType.DATASET, null, "legacy", Map.of("columns", List.of("id"), "rows", List.of(Map.of("id", "001"))), "MANUAL");
        var exported = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "DATASET", "assetIds", List.of(dataset.id()), "format", "csv"));
        var saved = preview(project.id(), "DATASET", "csv", "legacy.csv", exported.body(), Map.of());
        var metadata = new LinkedHashMap<>(map(saved.get("metadata"))); metadata.remove("mappingCapabilities"); metadata.put("columnTypes", Map.of("id", "NUMBER"));
        jdbc.update("UPDATE import_job SET metadata=? WHERE id=? AND project_id=?", json.write(metadata), saved.get("id"), project.id());
        var reopened = object(request("GET", "/api/projects/" + project.id() + "/imports/" + saved.get("id"), null));
        assertThat(reopened.get("status")).isEqualTo("INVALID");
        assertThat(objects(reopened.get("errors"))).anySatisfy(issue -> assertThat(issue.get("field")).isEqualTo("columnTypes"));
        assertThat(request("POST", "/api/projects/" + project.id() + "/imports/" + saved.get("id") + "/apply", Map.of()).statusCode()).isEqualTo(422);
        assertThat(assets.all(project.id())).containsExactly(dataset);
    }
}
