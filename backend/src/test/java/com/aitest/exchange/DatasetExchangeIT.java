package com.aitest.exchange;

import com.aitest.asset.AssetType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetExchangeIT extends ExchangeHttpTest {
    @Test
    void bomQuotedChineseCommaNewlineNullNumberDateAndLeadingZerosSurviveCsvAndXlsx() throws Exception {
        var project = project();
        String csv = "\uFEFF编号,名称,数量,日期,备注\r\n001,\"中文,订单\n换行\",12.5,2026-09-14,\\N\r\n002,\"引号\"\"测试\",0,2026-09-15,\"\"\r\n";
        var preview = preview(project.id(), "DATASET", "csv", "中文.csv", csv.getBytes(StandardCharsets.UTF_8), Map.of("columnMappings", "{\"编号\":\"id\"}", "columnTypes", "{\"数量\":\"NUMBER\",\"日期\":\"DATE\"}"));
        assertThat(objects(preview.get("errors"))).isEmpty();
        assertThat(map(preview.get("metadata")).get("columnMappings")).isEqualTo(Map.of("编号", "id"));
        assertThat(map(preview.get("metadata")).get("originalColumns")).isEqualTo(List.of("编号", "名称", "数量", "日期", "备注"));
        apply(project.id(), preview); var dataset = assets.all(project.id()).getFirst();
        var rows = objects(dataset.data().get("rows"));
        assertThat(rows.getFirst()).containsEntry("id", "001").containsEntry("名称", "中文,订单\n换行").containsEntry("数量", 12.5).containsEntry("日期", "2026-09-14").containsEntry("备注", null);
        assertThat(rows.get(1)).containsEntry("名称", "引号\"测试").containsEntry("备注", "");
        for (String format : List.of("csv", "xlsx")) {
            var exported = request("POST", "/api/projects/" + project.id() + "/exports", Map.of("type", "DATASET", "assetIds", List.of(dataset.id()), "format", format));
            assertThat(exported.statusCode()).isEqualTo(200);
            var destination = project(); var again = preview(destination.id(), "DATASET", format, "roundtrip." + format, exported.body(), Map.of());
            assertThat(objects(again.get("errors"))).as(format).isEmpty(); apply(destination.id(), again);
            assertThat(assets.all(destination.id()).getFirst().data().get("columns")).isEqualTo(dataset.data().get("columns"));
            assertThat(assets.all(destination.id()).getFirst().data().get("rows")).isEqualTo(dataset.data().get("rows"));
        }
    }
    @Test
    void workbookCellFormatsKeepIdentifiersAndFormulasAreNeverEvaluated() throws Exception {
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("数据集"); sheet.createRow(0).createCell(0).setCellValue("编号");
            var cell = sheet.createRow(1).createCell(0); cell.setCellValue(1); var style = book.createCellStyle(); style.setDataFormat(book.createDataFormat().getFormat("00000")); cell.setCellStyle(style);
            book.write(out); bytes = out.toByteArray();
        }
        var project = project(); var preview = preview(project.id(), "DATASET", "xlsx", "identifiers.xlsx", bytes, Map.of());
        assertThat(objects(preview.get("errors"))).isEmpty();
        assertThat(objects(map(objects(preview.get("nodes")).getFirst().get("data")).get("rows")).getFirst()).containsEntry("编号", "00001");
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("数据集"); sheet.createRow(0).createCell(0).setCellValue("数值"); sheet.createRow(1).createCell(0).setCellFormula("1+1"); book.write(out); bytes = out.toByteArray();
        }
        preview = preview(project.id(), "DATASET", "xlsx", "formula.xlsx", bytes, Map.of());
        assertThat(preview.get("status")).isEqualTo("INVALID");
        assertThat(objects(preview.get("errors"))).anySatisfy(error -> assertThat(error.get("row")).isEqualTo(2));
        assertThat(assets.all(project.id())).isEmpty();
    }
}
