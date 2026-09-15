package com.aitest.exchange;

import com.aitest.ai.text.FunctionalCaseAiDTO;
import com.aitest.ai.text.MdUtil;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aitest.exchange.ExchangeIO.*;

/** Editable Markdown: each visible field is the source of truth, metadata contains structure only. */
@Component
public class MarkdownCodec implements ExportCodec {
    private final JsonCodec json;
    private final PortableBundleCodec portable;
    public MarkdownCodec(JsonCodec json, PortableBundleCodec portable) { this.json = json; this.portable = portable; }
    @Override public Set<AssetType> assetTypes() { return Set.of(AssetType.FUNCTIONAL_CASE, AssetType.BUG, AssetType.QUALITY_BRIEF, AssetType.DASHBOARD); }
    @Override public Set<String> formats() { return Set.of("md"); }
    @Override public ExportFile export(ExportContext context, String format) { return encode(context.bundle(), context.type().name().toLowerCase(java.util.Locale.ROOT)); }
    public ExportFile encode(ExchangeBundle bundle, String name) {
        StringBuilder text = new StringBuilder("# AI Test Platform 资产文档\n\n版本：" + ExchangeBundle.VERSION + "\n\n每个二级标题是一条独立记录；三级标题后的文本块可直接编辑。JSON 块保留明确类型。节点注释仅保存层级、顺序与引用键。\n\n");
        var metadata = new LinkedHashMap<String, Object>(); metadata.put("formatVersion", bundle.formatVersion()); metadata.put("metadata", bundle.metadata()); metadata.put("externalReferences", bundle.externalReferences()); metadata.put("warnings", bundle.warnings());
        block(text, "aitest-bundle", json.write(metadata));
        for (var node : bundle.nodes()) {
            if (node.name().contains("\n") || node.name().contains("\r")) throw error("export", 1, "name", "Markdown 记录标题不能跨行，请使用 JSON/YAML 保留跨行名称");
            var structure = new LinkedHashMap<String, Object>(); structure.put("key", node.key()); structure.put("type", node.type()); structure.put("parentKey", node.parentKey()); structure.put("position", node.position()); structure.put("references", node.references());
            text.append("<!-- aitest-node ").append(json.write(structure)).append(" -->\n\n## ").append(node.name()).append("\n\n");
            text.append("类型：").append(node.type().label()).append("\n\n");
            for (var entry : node.data().entrySet()) {
                text.append("### ").append(entry.getKey()).append("\n\n"); Object value = entry.getValue();
                block(text, value instanceof String ? "text" : "json", value instanceof String string ? string : json.write(value));
            }
        }
        return new ExportFile(name + ".md", "text/markdown; charset=utf-8", text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void block(StringBuilder text, String language, String value) {
        int length = 3; var matcher = java.util.regex.Pattern.compile("~+").matcher(value); while (matcher.find()) length = Math.max(length, matcher.group().length() + 1);
        String fence = "~".repeat(length); text.append(fence).append(language).append('\n').append(value).append('\n').append(fence).append("\n\n");
    }
    public ParsedExchange parse(String source, byte[] bytes, AssetType type) {
        String text = utf8(bytes, source);
        if (!text.contains("<!-- aitest-node ")) {
            if (type == AssetType.QUALITY_BRIEF) return ParsedExchange.valid(ExchangeBundle.of(List.of(new ExchangeNode("brief_1", type, null, ExchangeParserRegistry.stripExtension(source), 0, Map.of("content", text), Map.of()))));
            if (type == AssetType.FUNCTIONAL_CASE) return legacy(source, text);
            throw error(source, 1, "$markdown", "此 Markdown 需要版本化的 aitest-node 记录格式");
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1); Map<String, Object> manifest = new LinkedHashMap<>(); List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> current = null, data = null; String field = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("<!-- aitest-node ") && line.endsWith(" -->")) {
                if (current != null) nodes.add(current);
                current = new LinkedHashMap<>(map(ExchangeIO.json(line.substring(17, line.length() - 4), source), source, i + 1, "$node"));
                data = new LinkedHashMap<>(); current.put("data", data); field = null;
            } else if (current != null && line.startsWith("## ")) current.put("name", line.substring(3));
            else if (current != null && line.startsWith("### ")) field = line.substring(4);
            else if (line.matches("~{3,}(aitest-bundle|text|json)")) {
                int prefix = 0; while (line.charAt(prefix) == '~') prefix++; String fence = line.substring(0, prefix), language = line.substring(prefix); int end = i + 1;
                while (end < lines.length && !lines[end].equals(fence)) end++;
                if (end == lines.length) throw error(source, i + 1, field == null ? "$markdown" : field, "Markdown 文本块没有结束围栏");
                String value = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, end));
                if (language.equals("aitest-bundle")) manifest.putAll(map(ExchangeIO.json(value, source), source, i + 1, "$metadata"));
                else {
                    if (data == null || field == null) throw error(source, i + 1, "$markdown", "字段文本块缺少对应的资产或三级标题");
                    if (data.containsKey(field)) throw error(source, i + 1, field, "同一资产字段重复");
                    data.put(field, language.equals("json") ? ExchangeIO.json(value, source) : value);
                }
                i = end;
            }
        }
        if (current != null) nodes.add(current); manifest.put("nodes", nodes); return portable.parseObject(source, manifest);
    }
    private ParsedExchange legacy(String source, String text) {
        try {
            List<FunctionalCaseAiDTO> cases;
            if (text.contains(MdUtil.MD_START_TAG)) cases = MdUtil.batchTransformToCaseDTO(text);
            else {
                var chunks = text.split("(?m)(?=^## [^#])"); cases = new ArrayList<>();
                for (String chunk : chunks) if (chunk.startsWith("## ")) cases.add(MdUtil.transformToCaseDTO(chunk));
                if (cases.isEmpty()) cases = List.of(MdUtil.transformToCaseDTO(text));
            }
            List<ExchangeNode> nodes = new ArrayList<>(); int index = 0;
            for (var dto : cases) {
                String key = "case_" + (++index); var data = new LinkedHashMap<String, Object>();
                data.put("precondition", dto.getPrerequisite() == null ? "" : dto.getPrerequisite()); data.put("remark", dto.getDescription() == null ? "" : dto.getDescription());
                nodes.add(new ExchangeNode(key, AssetType.FUNCTIONAL_CASE, null, dto.getName(), index - 1, data, Map.of())); int step = 0;
                for (var item : dto.getSteps()) nodes.add(new ExchangeNode(key + "_step_" + (++step), AssetType.FUNCTIONAL_STEP, key, "步骤 " + step, step - 1, Map.of("step", item.getDesc(), "expected", item.getResult()), Map.of()));
                if (dto.getSteps().isEmpty() && dto.getTextDescription() != null) nodes.add(new ExchangeNode(key + "_step_1", AssetType.FUNCTIONAL_STEP, key, "步骤 1", 0, Map.of("step", dto.getTextDescription(), "expected", dto.getExpectedResult() == null ? "" : dto.getExpectedResult()), Map.of()));
            }
            return ParsedExchange.valid(ExchangeBundle.of(nodes));
        } catch (RuntimeException e) { throw error(source, 1, "$markdown", "Markdown 用例需要二级标题、前置条件、步骤/预期结果表格；可使用 featureCaseStart/featureCaseEnd 分隔多用例"); }
    }
}
