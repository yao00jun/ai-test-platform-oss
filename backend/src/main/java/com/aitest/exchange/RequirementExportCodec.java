package com.aitest.exchange;

import com.aitest.asset.AssetType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class RequirementExportCodec implements ExportCodec {
    @Override public Set<AssetType> assetTypes() { return Set.of(AssetType.REQUIREMENT); }
    @Override public Set<String> formats() { return Set.of("md", "docx"); }
    @Override public ExportFile export(ExportContext context, String format) {
        var nodes = context.bundle().nodes().stream().filter(n -> n.type() == AssetType.REQUIREMENT).toList();
        if (nodes.isEmpty()) throw ExchangeIO.error("export", 1, "nodes", "没有可导出的需求文档");
        String content = String.join("\n\n", nodes.stream().map(n -> (nodes.size() > 1 ? "# " + n.name() + "\n\n" : "") + n.data().getOrDefault("content", "")).toList());
        return encode(content, "requirement", format);
    }
    public ExportFile encode(String content, String name, String format) {
        if (format.equals("md")) return new ExportFile(name + ".md", "text/markdown; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (String line : content.split("\n", -1)) {
                var paragraph = document.createParagraph(); paragraph.setSpacingAfter(100);
                var run = paragraph.createRun(); run.setFontFamily("微软雅黑"); run.setFontSize(11); run.setText(line);
                if (line.startsWith("# ")) { run.setBold(true); run.setFontSize(20); }
                else if (line.startsWith("## ")) { run.setBold(true); run.setFontSize(15); }
            }
            document.write(out); return new ExportFile(name + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("Cannot write requirement document", e); }
    }
}
