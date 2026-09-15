package com.aitest.report;

import com.aitest.common.*;
import com.aitest.exchange.*;
import com.aitest.exchange.render.*;
import com.aitest.execution.Values;
import com.aitest.storage.FileStorageService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static com.aitest.exchange.render.HtmlDocument.*;

@Service
public final class ReportService {
    private record Attachment(String id, String name, String mediaType, String checksum, byte[] bytes, String error) { }
    private final ReportSnapshotReader reader;
    private final JsonCodec json;
    private final FileStorageService files;
    private final PdfRenderer pdf;
    public ReportService(ReportSnapshotReader reader, JsonCodec json, FileStorageService files, PdfRenderer pdf) { this.reader = reader; this.json = json; this.files = files; this.pdf = pdf; }

    public ExportFile export(String projectId, String runId, String format) {
        if (!Set.of("html", "pdf", "json", "zip", "csv", "xlsx").contains(format)) throw Problem.invalid("报告格式支持 html、pdf、json、zip、csv、xlsx");
        Map<String, Object> snapshot = reader.read(projectId, runId);
        if (format.equals("json")) return new ExportFile("report.json", "application/json", bytes(json.write(snapshot)));
        if (Set.of("csv", "xlsx").contains(format)) return matrix(snapshot, format);
        Map<String, Attachment> attachments = attachments(projectId, snapshot);
        String document = html(snapshot, attachments);
        if (format.equals("html")) return new ExportFile("report.html", "text/html; charset=utf-8", bytes(document));
        if (format.equals("pdf")) return new ExportFile("report.pdf", "application/pdf", pdf.render(document));
        Map<String, byte[]> entries = new LinkedHashMap<>(); entries.put("report.html", bytes(document)); entries.put("report.json", bytes(json.write(snapshot)));
        for (Attachment file : attachments.values()) if (file.bytes() != null) {
            String name = "artifacts/" + file.id() + "-" + file.name().replaceAll("[^\\p{L}\\p{N}._-]", "_");
            if (entries.size() >= ExchangeIO.MAX_FILES || entries.values().stream().mapToLong(item -> item.length).sum() + file.bytes().length > ExchangeIO.MAX_EXPANDED_BYTES)
                throw new Problem(422, "REPORT_TOO_LARGE", "证据包超过 256 个文件或 64 MB，请下载 HTML/JSON 并按运行项分别下载附件");
            entries.put(name, file.bytes());
        }
        return new ExportFile("report-evidence.zip", "application/zip", ExchangeIO.zip(entries));
    }
    private Map<String, Attachment> attachments(String projectId, Map<String, Object> snapshot) {
        Set<String> ids = new LinkedHashSet<>();
        for (var item : Values.objects(snapshot.get("items"))) for (var step : Values.objects(item.get("steps"))) {
            Object value = Values.map(step.get("result")).get("artifactIds");
            if (value instanceof List<?> list) list.forEach(id -> ids.add(id.toString()));
        }
        Map<String, Attachment> result = new LinkedHashMap<>(); long total = 0;
        for (String id : ids) try {
            var file = files.get(projectId, id); total += file.size();
            if (total > 64L * 1024 * 1024) throw new Problem(422, "REPORT_TOO_LARGE", "运行证据超过 64 MB，请下载 JSON/CSV 明细并单独下载附件");
            result.put(id, new Attachment(id, file.name(), file.mediaType(), file.sha256(), Files.readAllBytes(file.path()), null));
        } catch (Problem failure) {
            if (failure.status() != 404) throw failure;
            result.put(id, new Attachment(id, id, "application/octet-stream", "", null, "附件已缺失或不属于此项目"));
        } catch (IOException failure) { result.put(id, new Attachment(id, id, "application/octet-stream", "", null, "附件暂时无法读取")); }
        return result;
    }
    private String html(Map<String, Object> report, Map<String, Attachment> attachments) {
        Map<String, Object> summary = Values.map(report.get("summary")), counts = Values.map(summary.get("counts"));
        StringBuilder html = start(Values.text(report, "name", "测试运行报告"), "运行 ID " + report.get("id") + " · 报告时间 " + report.get("reportedAt"));
        html.append("<p>运行状态："); status(html, report.get("status")); html.append("</p><div class=\"metrics\">");
        for (var metric : List.of(Map.entry("运行项", "total"), Map.entry("独立用例", "caseCount"), Map.entry("数据行", "dataRows"))) html.append("<div class=\"metric\">").append(metric.getKey()).append(' ').append(escape(summary.get(metric.getValue()))).append("</div>");
        html.append("</div><p class=\"note\">运行项包含数据驱动展开后的每一行。未执行、待人工、阻塞、错误与取消均保留原状态，不计为通过。</p><table><thead><tr><th>结果</th><th>数量</th></tr></thead><tbody>");
        counts.forEach((state, count) -> { html.append("<tr><td>"); status(html, state); html.append("</td><td>").append(escape(count)).append("</td></tr>"); });
        html.append("</tbody></table><h2>运行清单</h2><table><thead><tr><th style=\"width:46%\">用例</th><th>数据行</th><th>结果</th><th>耗时 ms</th></tr></thead><tbody>");
        for (var item : Values.objects(report.get("items"))) {
            html.append("<tr><td>").append(escape(item.get("name"))).append("</td><td>").append(row(item)).append("</td><td>"); status(html, item.get("status")); html.append("</td><td>").append(escape(item.get("durationMs"))).append("</td></tr>");
        }
        html.append("</tbody></table>");
        for (var item : Values.objects(report.get("items"))) {
            html.append("<section class=\"item\"><h2>").append(escape(item.get("name"))).append("</h2><p class=\"meta\">资产 ").append(escape(item.get("assetId"))).append(" · 数据行 ").append(row(item)).append(" · "); status(html, item.get("status")); html.append("</p>");
            field(html, "执行备注", item.get("notes")); field(html, "执行错误", item.get("error"));
            if ("MANUAL_PENDING".equals(item.get("status"))) field(html, "人工执行", "等待人工录入结果");
            structured(html, "该数据行的运行变量", item.get("variables"));
            for (var step : Values.objects(item.get("steps"))) {
                var result = Values.map(step.get("result"));
                html.append("<div class=\"step\"><h3>").append(escape(step.get("name"))).append("</h3><p>"); status(html, step.get("status")); html.append(" <span class=\"meta\">").append(escape(step.get("engine"))).append(" · ").append(escape(result.get("durationMs"))).append(" ms</span></p>");
                field(html, "失败原因", result.get("error")); structured(html, "请求 / 步骤配置", result.get("request")); structured(html, "实际结果", result.get("actual"));
                List<Map<String, Object>> assertions = Values.objects(result.get("assertions"));
                if (!assertions.isEmpty()) {
                    html.append("<h4>断言</h4><table><thead><tr><th>目标与比较</th><th>预期</th><th>实际</th><th>结论</th></tr></thead><tbody>");
                    for (var assertion : assertions) html.append("<tr><td>").append(escape(assertion.get("type"))).append(' ').append(escape(assertion.get("path"))).append(' ').append(escape(assertion.get("operator")))
                            .append("</td><td>").append(escape(render(assertion.get("expected")))).append("</td><td>").append(escape(render(assertion.get("actual")))).append("</td><td>").append(Boolean.TRUE.equals(assertion.get("passed")) ? "通过" : "失败").append("</td></tr>");
                    html.append("</tbody></table>");
                }
                structured(html, "提取变量", result.get("exports"));
                if (result.get("artifactIds") instanceof List<?> ids) for (Object id : ids) artifact(html, attachments.get(id.toString()));
                html.append("</div>");
            }
            html.append("</section>");
        }
        html.append("<p class=\"note\">资产名称、请求和配置来自本次运行快照；后续编辑不会修改这些事实。人工结果反映报告导出时已保存的记录。</p>");
        return finish(html);
    }
    private void artifact(StringBuilder html, Attachment file) {
        if (file == null) return;
        html.append("<figure class=\"evidence\"><figcaption>").append(escape(file.name())).append("</figcaption><p class=\"meta\">附件 ").append(escape(file.id())).append(" · SHA-256 ").append(escape(file.checksum())).append("</p>");
        if (file.error() != null) field(html, "证据说明", file.error());
        else if (file.mediaType().equals("image/png") && file.bytes().length >= 8 && file.bytes()[0] == (byte) 137 && file.bytes()[1] == 80 && file.bytes()[2] == 78 && file.bytes()[3] == 71) {
            if (file.bytes().length <= 4 * 1024 * 1024) html.append("<img alt=\"步骤截图\" src=\"data:image/png;base64,").append(Base64.getEncoder().encodeToString(file.bytes())).append("\">");
            else field(html, "截图", "原图超过 4 MB，保留在证据包中以免缩放丢失内容");
        } else field(html, "附件", "原始文件包含在 ZIP 证据包中，也可从运行详情下载");
        html.append("</figure>");
    }
    private void structured(StringBuilder html, String label, Object value) {
        if (value == null || value.equals(Map.of()) || value.equals(List.of())) return;
        html.append("<div class=\"field-label\">").append(escape(label)).append("</div><pre>").append(escape(render(value))).append("</pre>");
    }
    private String render(Object value) { return value instanceof Map<?, ?> || value instanceof List<?> ? json.write(value) : Objects.toString(value, ""); }
    private String row(Map<String, Object> item) { return item.get("rowIndex") instanceof Number number ? Long.toString(number.longValue() + 1) : "-"; }
    private ExportFile matrix(Map<String, Object> report, String format) {
        String[] headers = {"运行项 ID", "用例 ID", "用例名称", "数据行", "结果", "耗时 ms", "变量", "备注", "错误"};
        List<List<Object>> rows = new ArrayList<>();
        for (var item : Values.objects(report.get("items"))) rows.add(Arrays.asList(item.get("id"), item.get("assetId"), item.get("name"), row(item), item.get("status"), item.get("durationMs"), render(item.get("variables")), item.get("notes"), item.get("error")));
        try {
            if (format.equals("csv")) {
                StringWriter out = new StringWriter(); out.write('\uFEFF');
                try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader(headers).get())) {
                    for (var row : rows) csv.printRecord(row.stream().map(value -> {
                        String text = Objects.toString(value, ""); return text.matches("(?s)^[=+\\-@\\t\\r\\n].*") || text.startsWith("'") ? "'" + text : text;
                    }).toList());
                }
                return new ExportFile("run-matrix.csv", "text/csv; charset=utf-8", bytes(out.toString()));
            }
            try (SXSSFWorkbook book = new SXSSFWorkbook(100); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                var sheet = book.createSheet("执行结果"); sheet.createFreezePane(0, 1);
                var header = sheet.createRow(0); for (int column = 0; column < headers.length; column++) { header.createCell(column).setCellValue(headers[column]); sheet.setColumnWidth(column, (column == 2 || column == 6 ? 40 : 24) * 256); }
                int index = 1;
                for (var values : rows) {
                    var row = sheet.createRow(index++);
                    for (int column = 0; column < values.size(); column++) {
                        Object value = values.get(column); var cell = row.createCell(column);
                        if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                        else { String text = Objects.toString(value, ""); if (text.length() > 32767) throw new Problem(422, "EXCEL_CELL_TOO_LONG", "单元格超过 Excel 的 32767 字符限制，请改用 JSON/CSV"); cell.setCellValue(text); }
                    }
                }
                book.write(output); return new ExportFile("run-matrix.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
            }
        } catch (IOException failure) { throw new Problem(500, "REPORT_WRITE_FAILED", "运行矩阵文件生成失败"); }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
