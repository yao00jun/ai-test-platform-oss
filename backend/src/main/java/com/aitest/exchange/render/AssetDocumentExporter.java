package com.aitest.exchange.render;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.exchange.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Component
public final class AssetDocumentExporter implements ExportCodec {
    private final JsonCodec json;
    private final PdfRenderer pdf;
    public AssetDocumentExporter(JsonCodec json, PdfRenderer pdf) { this.json = json; this.pdf = pdf; }
    public Set<AssetType> assetTypes() { return EnumSet.allOf(AssetType.class); }
    public Set<String> formats() { return Set.of("html", "pdf"); }
    public ExportFile export(ExportContext context, String format) {
        Map<String, List<Asset>> children = new LinkedHashMap<>(); Set<String> ids = new HashSet<>();
        context.assets().forEach(asset -> ids.add(asset.id()));
        context.assets().forEach(asset -> children.computeIfAbsent(asset.parentId(), ignored -> new ArrayList<>()).add(asset));
        children.values().forEach(list -> list.sort(Comparator.comparingInt(Asset::position).thenComparing(Asset::id)));
        List<Asset> roots = context.assets().stream().filter(asset -> asset.parentId() == null || !ids.contains(asset.parentId())).toList();
        String title = context.type().label() + "资产文档";
        StringBuilder html = HtmlDocument.start(title, "导出时间 " + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + " · 共 " + context.assets().size() + " 个资产与独立子项");
        Map<String, Object> project = com.aitest.execution.Values.map(context.bundle().metadata().get("project"));
        HtmlDocument.field(html, "项目", project.get("name"));
        record Entry(Asset asset, int depth) { }
        Deque<Entry> pending = new ArrayDeque<>(); for (Asset root : roots.reversed()) pending.push(new Entry(root, 0));
        while (!pending.isEmpty()) {
            Entry entry = pending.pop(); Asset asset = entry.asset(); int level = Math.min(4, entry.depth() + 2);
            html.append("<article><h").append(level).append(">").append(HtmlDocument.escape(asset.name())).append("</h").append(level).append("><p class=\"meta\">")
                    .append(HtmlDocument.escape(asset.type().label())).append(" · 版本 ").append(HtmlDocument.escape(asset.version())).append(" · ").append(asset.confirmed() ? "已人工确认" : "可编辑草稿").append("</p>");
            for (FieldDefinition field : asset.type().fields()) {
                Object value = asset.data().get(field.key());
                if (value == null || value.equals("") || value.equals(Map.of()) || value.equals(List.of())) continue;
                if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    html.append("<div class=\"field-label\">").append(HtmlDocument.escape(field.label())).append("</div><pre>").append(HtmlDocument.escape(json.write(value))).append("</pre>");
                } else HtmlDocument.field(html, field.label(), value instanceof Boolean bool ? bool ? "是" : "否" : value);
            }
            html.append("</article>");
            for (Asset child : children.getOrDefault(asset.id(), List.of()).reversed()) pending.push(new Entry(child, entry.depth() + 1));
        }
        if (!context.bundle().warnings().isEmpty()) {
            html.append("<h2>导出说明</h2>");
            context.bundle().warnings().forEach(warning -> HtmlDocument.field(html, warning.field(), warning.message()));
        }
        String document = HtmlDocument.finish(html);
        return format.equals("pdf") ? new ExportFile("assets.pdf", "application/pdf", pdf.render(document))
                : new ExportFile("assets.html", "text/html; charset=utf-8", document.getBytes(StandardCharsets.UTF_8));
    }
}
