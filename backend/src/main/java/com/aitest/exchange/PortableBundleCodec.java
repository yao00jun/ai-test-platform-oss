package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aitest.exchange.ExchangeIO.*;

@Component
public class PortableBundleCodec implements ExportCodec {
    private final JsonCodec json;
    public PortableBundleCodec(JsonCodec json) { this.json = json; }
    public ParsedExchange parse(String source, String format, byte[] bytes) {
        if (format.equals("zip")) {
            Map<String, byte[]> files = unzip(bytes, source);
            if (!files.containsKey("bundle.json") || files.keySet().stream().anyMatch(name -> !Set.of("bundle.json", "README.md").contains(name)))
                throw error(source, 1, "$zip", "便携 ZIP 必须包含 bundle.json，可附 README.md；其他变体或附件请单独导入");
            return parse(source + "!/bundle.json", "json", files.get("bundle.json"));
        }
        String text = utf8(bytes, source);
        return parseObject(source, format.equals("yaml") ? ExchangeIO.yaml(text, source) : ExchangeIO.json(text, source));
    }
    public ParsedExchange parseObject(String source, Object value) {
        var root = map(value, source, 1, "$bundle");
        if (!ExchangeBundle.VERSION.equals(root.get("formatVersion"))) throw error(source, 1, "formatVersion", "需要 aitest.exchange/v1 格式版本");
        for (String key : root.keySet()) if (!Set.of("formatVersion", "metadata", "nodes", "externalReferences", "warnings").contains(key)) throw error(source, 1, key, "便携文件包含未知的顶层字段");
        if (!(root.get("nodes") instanceof List<?> rows)) throw error(source, 1, "nodes", "nodes 必须为数组");
        if (rows.size() > MAX_NODES) throw error(source, 1, "nodes", "一次最多导入 20000 个资产节点");
        List<ExchangeNode> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>(); int row = 0;
        for (Object item : rows) {
            row++;
            try {
                var data = map(item, source, row, "nodes");
                for (String key : data.keySet()) if (!Set.of("key", "type", "parentKey", "name", "position", "data", "references").contains(key)) throw error(source, row, key, "资产节点包含未知字段；真实 ID/版本不能用于覆盖已有资产");
                String key = string(data.get("key"), source, row, "key");
                String typeName = string(data.get("type"), source, row, "type"); AssetType type;
                try { type = AssetType.valueOf(typeName); } catch (IllegalArgumentException e) { throw error(source, row, "type", "未知资产类型"); }
                String parent = data.get("parentKey") == null ? null : string(data.get("parentKey"), source, row, "parentKey");
                String name = string(data.get("name"), source, row, "name"); Object position = data.getOrDefault("position", row - 1);
                if (!(position instanceof Number n) || n.longValue() != n.doubleValue() || n.longValue() < 0 || n.longValue() > Integer.MAX_VALUE) throw error(source, row, "position", "position 必须是非负整数");
                Map<String, String> refs = new LinkedHashMap<>();
                for (var ref : map(data.getOrDefault("references", Map.of()), source, row, "references").entrySet()) refs.put(ref.getKey(), string(ref.getValue(), source, row, "references." + ref.getKey()));
                nodes.add(new ExchangeNode(key, type, parent, name, ((Number) position).intValue(), map(data.getOrDefault("data", Map.of()), source, row, "data"), refs));
            } catch (ExchangeException e) { errors.add(e.issue()); }
        }
        Map<String, ExchangeBundle.ExternalReference> external = new LinkedHashMap<>();
        for (var entry : map(root.getOrDefault("externalReferences", Map.of()), source, 1, "externalReferences").entrySet()) {
            var ref = map(entry.getValue(), source, 1, "externalReferences." + entry.getKey());
            if (!ref.keySet().stream().allMatch(Set.of("type", "name")::contains)) throw error(source, 1, "externalReferences", "外部引用只接受 type 和 name");
            external.put(entry.getKey(), new ExchangeBundle.ExternalReference(string(ref.get("type"), source, 1, "externalReferences.type"), string(ref.getOrDefault("name", entry.getKey()), source, 1, "externalReferences.name")));
        }
        List<ExchangeIssue> warnings = new ArrayList<>();
        if (root.get("warnings") instanceof List<?> items) for (Object warning : items) {
            try { warnings.add(json.convert(warning, ExchangeIssue.class)); }
            catch (RuntimeException e) { throw error(source, 1, "warnings", "warnings 必须使用 source/row/field/message 结构"); }
        }
        return new ParsedExchange(new ExchangeBundle(ExchangeBundle.VERSION, map(root.getOrDefault("metadata", Map.of()), source, 1, "metadata"), nodes, external, warnings), errors);
    }
    @Override public Set<AssetType> assetTypes() { return EnumSet.allOf(AssetType.class); }
    @Override public Set<String> formats() { return Set.of("json", "yaml", "zip"); }
    @Override public ExportFile export(ExportContext context, String format) { return encode(context.bundle(), context.type().name().toLowerCase(java.util.Locale.ROOT), format); }
    public ExportFile encode(ExchangeBundle bundle, String name, String format) {
        byte[] bytes; String media;
        switch (format) {
            case "json" -> { bytes = json.write(bundle).getBytes(StandardCharsets.UTF_8); media = "application/json"; }
            case "yaml" -> { bytes = ExchangeIO.yaml(bundle, json).getBytes(StandardCharsets.UTF_8); media = "application/yaml"; }
            case "zip" -> {
                bytes = ExchangeIO.zip(Map.of("bundle.json", json.write(bundle).getBytes(StandardCharsets.UTF_8), "README.md", ("# AI Test Platform portable bundle\n\nVersion: " + ExchangeBundle.VERSION + "\n\nImport bundle.json or this ZIP through Import Preview. Local keys are resolved to new IDs. Credentials and unmanaged attachments are omitted. Review warnings and bind any externalReferences before Apply.\n").getBytes(StandardCharsets.UTF_8)));
                media = "application/zip";
            }
            default -> throw new IllegalArgumentException("Unknown portable format");
        }
        if (bytes.length > MAX_BYTES) throw error(name, 1, "$file", "导出文件超过 32 MB，请缩小选择范围");
        return new ExportFile(name + "." + format, media, bytes);
    }
}
