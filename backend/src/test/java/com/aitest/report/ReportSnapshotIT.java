package com.aitest.report;

import com.aitest.asset.*;
import com.aitest.exchange.*;
import com.aitest.execution.*;
import com.aitest.job.JobService;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.InetSocketAddress;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ReportSnapshotIT extends ExchangeHttpTest {
    @Autowired ExecutionCoordinator coordinator;
    @Autowired RunRepository runs;
    @Autowired JobService jobs;

    @Test void reportsUseRealMixedRunStatisticsAndImmutableNamesWithChineseEvidence() throws Exception {
        String project = project().id(); String secret = "Report-secret-never-export-743";
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            site.setExecutor(executor);
            site.createContext("/", request -> {
                boolean page = request.getRequestURI().getPath().equals("/page");
                byte[] body = (page ? "<meta charset='utf-8'><h1>订单质量验收</h1><p id='state'>页面正常</p>" : json.write(Map.of("message", "中文长报文与退款边界说明。".repeat(90)))).getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", page ? "text/html; charset=utf-8" : "application/json");
                request.sendResponseHeaders(200, body.length); request.getResponseBody().write(body); request.close();
            }); site.start();
            String base = "http://127.0.0.1:" + site.getAddress().getPort();
            Asset environment = create(project, AssetType.ENVIRONMENT, null, "测试环境", Map.of("baseUrl", base, "headers", Map.of("Authorization", "Bearer ${apiKey}"), "variables", Map.of("apiKey", secret)));
            Asset data = create(project, AssetType.DATASET, null, "两行退款输入", Map.of("columns", List.of("expectedStatus"), "rows", List.of(Map.of("expectedStatus", 200), Map.of("expectedStatus", 201))));
            Asset api = create(project, AssetType.API_CASE, null, "原始接口快照 <script>alert(1)</script>", Map.of("path", "/json", "assertions", List.of(Map.of("type", "status", "operator", "eq", "expected", "${expectedStatus}"))));
            Asset ui = create(project, AssetType.UI_SCENARIO, null, "浏览器失败截图", Map.of("baseUrl", base));
            create(project, AssetType.UI_STEP, ui.id(), "访问订单", Map.of("action", "navigate", "url", "/page"));
            create(project, AssetType.UI_STEP, ui.id(), "核对退款状态", Map.of("action", "assertText", "selector", "#state", "expected", "未出现的结果", "timeoutMs", 500));
            Asset manual = create(project, AssetType.FUNCTIONAL_CASE, null, "人工核验清单", Map.of("precondition", "由业务验收人员确认"));
            Asset plan = create(project, AssetType.TEST_PLAN, null, "订单持续测试报告", Map.of("environmentId", environment.id()));
            create(project, AssetType.PLAN_ITEM, plan.id(), "接口数据驱动", Map.of("targetId", api.id(), "datasetId", data.id()));
            create(project, AssetType.PLAN_ITEM, plan.id(), "UI 验证", Map.of("targetId", ui.id()));
            create(project, AssetType.PLAN_ITEM, plan.id(), "人工检查", Map.of("targetId", manual.id(), "executionMode", "MANUAL"));
            var submitted = coordinator.submit(project, new ExecutionCoordinator.Request(plan.id(), environment.id(), null, UUID.randomUUID().toString()));
            await().atMost(Duration.ofSeconds(60)).until(() -> jobs.get(project, submitted.jobId()).terminal());
            Map<String, Object> run = runs.get(project, submitted.runId());
            assertThat(map(map(run.get("summary")).get("counts"))).containsEntry("PASSED", 1L).containsEntry("FAILED", 2L).containsEntry("MANUAL_PENDING", 1L);
            assets.update(project, api.id(), api.version(), "后续修改不进入旧报告", Map.of("path", "/changed"), null, "MANUAL");
            String path = "/api/projects/" + project + "/runs/" + submitted.runId() + "/report";
            var html = request("GET", path + "?format=html", null);
            assertThat(html.statusCode()).as(new String(html.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            String document = new String(html.body(), StandardCharsets.UTF_8);
            assertThat(document).contains("运行项 4", "独立用例 3", "数据行 2", "人工核验清单", "原始接口快照 &lt;script&gt;", "data:image/png;base64,")
                    .doesNotContain(secret, "<script>alert(1)</script>", "后续修改不进入旧报告");
            var pdf = request("GET", path + "?format=pdf", null);
            assertThat(pdf.statusCode()).as(new String(pdf.body(), StandardCharsets.UTF_8)).isEqualTo(200);
            assertThat(pdf.body()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
            Path evidence = Path.of("../.runtime/evidence/reports"); Files.createDirectories(evidence);
            Files.write(evidence.resolve("mixed-run-report.html"), html.body()); Files.write(evidence.resolve("mixed-run-report.pdf"), pdf.body());
            try (var parsed = Loader.loadPDF(pdf.body())) {
                String text = new PDFTextStripper().getText(parsed);
                assertThat(text).contains("订单持续测试报告", "人工核验清单", "中文长报文", "数据行 2").doesNotContain(secret);
                PDFRenderer renderer = new PDFRenderer(parsed);
                for (int page = 0; page < parsed.getNumberOfPages(); page++) javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, 90), "PNG", evidence.resolve("mixed-run-page-" + (page + 1) + ".png").toFile());
            }
            var pack = request("GET", path + "?format=zip", null);
            assertThat(pack.statusCode()).isEqualTo(200);
            var entries = ExchangeIO.unzip(pack.body(), "report.zip");
            assertThat(entries).containsKeys("report.html", "report.json");
            assertThat(entries.keySet()).anyMatch(name -> name.startsWith("artifacts/") && name.endsWith(".png"));
            assertThat(request("GET", "/api/projects/" + project().id() + "/runs/" + submitted.runId() + "/report?format=html", null).statusCode()).isEqualTo(404);
            var manualItem = objects(run.get("items")).stream().filter(item -> item.get("assetId").equals(manual.id())).findFirst().orElseThrow();
            runs.manual(project, submitted.runId(), manualItem.get("id").toString(), manualItem.get("manualVersion").toString(), "PASSED", "=1+1");
            var revised = request("GET", path + "?format=json", null);
            assertThat(revised.statusCode()).isEqualTo(200);
            assertThat(((Number) map(map(object(revised).get("summary")).get("counts")).get("PASSED")).intValue()).isEqualTo(2);
            var csv = request("GET", path + "?format=csv", null);
            assertThat(csv.statusCode()).isEqualTo(200);
            String csvText = new String(csv.body(), StandardCharsets.UTF_8);
            assertThat(csvText).startsWith("\uFEFF").doesNotContain(secret);
            try (var parsed = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(new java.io.StringReader(csvText.substring(1)))) {
                var records = parsed.getRecords();
                assertThat(records).hasSize(4);
                assertThat(records.stream().filter(record -> record.get("用例 ID").equals(manual.id())).findFirst().orElseThrow().get("备注")).isEqualTo("'=1+1");
                assertThat(records.stream().filter(record -> record.get("结果").equals("PASSED"))).hasSize(2);
                assertThat(records.stream().filter(record -> record.get("用例 ID").equals(api.id())).map(record -> record.get("数据行"))).containsExactly("1", "2");
            }
            var xlsx = request("GET", path + "?format=xlsx", null);
            assertThat(xlsx.statusCode()).isEqualTo(200);
            try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx.body()))) {
                var sheet = workbook.getSheetAt(0);
                assertThat(sheet.getLastRowNum()).isEqualTo(4);
                assertThat(sheet.getRow(1).getCell(5).getCellType()).isEqualTo(CellType.NUMERIC);
                var row = java.util.stream.IntStream.rangeClosed(1, 4).mapToObj(sheet::getRow).filter(item -> item.getCell(1).getStringCellValue().equals(manual.id())).findFirst().orElseThrow();
                assertThat(row.getCell(7).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(7).getStringCellValue()).isEqualTo("=1+1");
                for (var item : sheet) for (var cell : item) if (cell.getCellType() == CellType.STRING) assertThat(cell.getStringCellValue()).doesNotContain(secret);
            }
        } finally { site.stop(0); }
    }
    private Asset create(String project, AssetType type, String parent, String name, Map<String, Object> data) { return assets.create(project, type, parent, name, data, "MANUAL"); }
}
