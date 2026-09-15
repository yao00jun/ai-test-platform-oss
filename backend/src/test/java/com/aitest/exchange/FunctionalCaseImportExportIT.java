package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionalCaseImportExportIT extends ExchangeHttpTest {
    @Test
    void readableWorkbookAndMarkdownJsonXmindRoundTripHierarchyIndependentStepsAndMultilineText() throws Exception {
        byte[] workbook;
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("功能用例");
            String[][] cells = {{"所属模块 (必填，支持级联/)", "用例名称 (必填)", "优先级 (P0/P1/P2/P3)", "前置条件", "步骤描述 (多步骤换行)", "预期结果 (对应换行)", "备注"},
                    {"用户中心/登录模块", "中文登录", "P0", "用户已注册\n手机号正常", "1. 打开登录页\n检查首屏\n2. 输入\"中文\"账号", "1. 页面显示\n2. 进入首页\n欢迎", "@ops、值 | 管道\n下一行"}};
            for (int r = 0; r < cells.length; r++) { var row = sheet.createRow(r); for (int c = 0; c < cells[r].length; c++) row.createCell(c).setCellValue(cells[r][c]); }
            book.write(out); workbook = out.toByteArray();
        }
        var first = project(); var preview = preview(first.id(), "FUNCTIONAL_CASE", "xlsx", "功能用例.xlsx", workbook, Map.of());
        assertThat(objects(preview.get("errors"))).isEmpty(); apply(first.id(), preview);
        var imported = assets.all(first.id()); assertThat(imported).hasSize(5);
        Asset testcase = imported.stream().filter(a -> a.type() == AssetType.FUNCTIONAL_CASE).findFirst().orElseThrow();
        assertThat(testcase.data()).containsEntry("priority", "P0").containsEntry("precondition", "用户已注册\n手机号正常").containsEntry("remark", "@ops、值 | 管道\n下一行");
        var steps = assets.children(first.id(), testcase.id());
        assertThat(steps).hasSize(2); assertThat(steps.getFirst().data().get("step")).isEqualTo("打开登录页\n检查首屏");
        assertThat(steps.get(1).data().get("expected")).isEqualTo("进入首页\n欢迎");
        var expected = structural(imported);
        for (String format : List.of("xlsx", "md", "json", "xmind")) {
            var exported = request("POST", "/api/projects/" + first.id() + "/exports", Map.of("assetIds", List.of(testcase.id()), "type", "FUNCTIONAL_CASE", "format", format));
            assertThat(exported.statusCode()).as(format + ": " + new String(exported.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            if (format.equals("xlsx")) try (var book = new XSSFWorkbook(new ByteArrayInputStream(exported.body()))) {
                assertThat(book.getSheet("FUNCTIONAL_CASE")).isNotNull(); assertThat(book.getSheet("FUNCTIONAL_STEP")).isNotNull();
                assertThat(book.getSheet("FUNCTIONAL_STEP").getLastRowNum()).isEqualTo(2);
            }
            if (format.equals("md")) assertThat(new String(exported.body(), StandardCharsets.UTF_8)).contains("中文登录", "打开登录页", "检查首屏");
            var destination = project(); var reimport = preview(destination.id(), "FUNCTIONAL_CASE", format, "roundtrip." + format, exported.body(), Map.of());
            assertThat(objects(reimport.get("errors"))).as(format).isEmpty(); apply(destination.id(), reimport);
            assertThat(structural(assets.all(destination.id()))).as(format).isEqualTo(expected);
        }
    }

    @Test
    void modernAndClassicXmindRecognizeDocumentedTopicGrammarAndRejectExternalEntitiesAndTraversal() throws Exception {
        var project = project();
        String classic = """
                <?xml version="1.0" encoding="UTF-8"?>
                <xmap-content xmlns="urn:xmind:xmap:xmlns:content:2.0"><sheet id="s"><title>测试</title><topic id="root"><title>功能测试</title><children><topics type="attached"><topic id="module"><title>模块: 用户中心</title><children><topics type="attached"><topic id="case"><title>用例: 中文登录</title><children><topics type="attached"><topic id="priority"><title>优先级: P0</title></topic><topic id="pre"><title>前置条件: 用户已注册</title></topic><topic id="step"><title>步骤 1: 打开登录页</title><children><topics type="attached"><topic id="expected"><title>预期: 页面显示</title></topic></topics></children></topic></topics></children></topic></topics></children></topic></topics></children></topic></sheet></xmap-content>
                """;
        var valid = preview(project.id(), "FUNCTIONAL_CASE", "xmind", "classic.xmind", zip("content.xml", classic), Map.of());
        assertThat(objects(valid.get("errors"))).isEmpty();
        assertThat(objects(valid.get("nodes"))).extracting(n -> n.get("type")).contains("MODULE", "FUNCTIONAL_CASE", "FUNCTIONAL_STEP");
        String xxe = classic.replace("<xmap-content", "<!DOCTYPE x [<!ENTITY leak SYSTEM 'file:///C:/private.txt'>]><xmap-content").replace("中文登录", "&leak;");
        var refused = preview(project.id(), "FUNCTIONAL_CASE", "xmind", "xxe.xmind", zip("content.xml", xxe), Map.of());
        assertThat(refused.get("status")).isEqualTo("INVALID");
        var traversal = preview(project.id(), "FUNCTIONAL_CASE", "xmind", "bad.xmind", zip("../content.json", "[]"), Map.of());
        assertThat(traversal.get("status")).isEqualTo("INVALID");
        assertThat(assets.all(project.id())).isEmpty();
    }
    private static byte[] zip(String name, String content) throws Exception {
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); zip.finish(); return out.toByteArray(); }
    }
    private static List<String> structural(List<Asset> assets) {
        Map<String, Asset> byId = assets.stream().collect(java.util.stream.Collectors.toMap(Asset::id, a -> a));
        List<String> result = new ArrayList<>();
        for (Asset asset : assets) { String parent = asset.parentId() == null ? "" : byId.get(asset.parentId()).name(); result.add(asset.type() + ":" + parent + ":" + asset.name() + ":" + new java.util.TreeMap<>(asset.data())); }
        result.sort(Comparator.naturalOrder()); return result;
    }
}
