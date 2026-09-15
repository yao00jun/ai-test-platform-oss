package com.aitest.exchange;

import com.aitest.api.importer.ApiDocumentImporter;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import com.aitest.requirement.DocumentParser;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ExchangeParserRegistry {
    private final PortableBundleCodec portable;
    private final ApiDocumentImporter api;
    private final DocumentParser documents;
    private final JsonCodec json;
    private final LedgerCodec ledger;
    private final DatasetCodec datasets;
    private final MarkdownCodec markdown;
    private final XMindCodec xmind;
    private final List<ImportCodec> extensions;
    public ExchangeParserRegistry(PortableBundleCodec portable, ApiDocumentImporter api, DocumentParser documents, JsonCodec json,
                                  LedgerCodec ledger, DatasetCodec datasets, MarkdownCodec markdown, XMindCodec xmind, List<ImportCodec> extensions) {
        this.portable = portable; this.api = api; this.documents = documents; this.json = json;
        this.ledger = ledger; this.datasets = datasets; this.markdown = markdown; this.xmind = xmind;
        this.extensions = extensions;
    }
    public ParsedExchange parse(String source, String format, byte[] bytes, AssetType type, Map<String, String> columnMappings, Map<String, String> columnTypes) {
        try {
            ExchangeIO.checkSize(bytes, source);
            if (type != AssetType.DATASET || !Set.of("csv", "xlsx").contains(format)) {
                if (!columnMappings.isEmpty()) throw ExchangeIO.error(source, 1, "columnMappings", "此类型或格式不支持数据列映射");
                if (!columnTypes.isEmpty()) throw ExchangeIO.error(source, 1, "columnTypes", "此类型或格式不支持数据列类型转换");
            }
            var matched = extensions.stream().filter(codec -> codec.assetTypes().contains(type) && codec.formats().contains(format)).toList();
            if (matched.size() > 1) throw ExchangeIO.error(source, 1, "format", "同一类型/格式存在多个导入扩展，请检查插件配置");
            if (!matched.isEmpty()) return matched.getFirst().parse(source, format, bytes, type);
            if (Set.of("csv", "xlsx").contains(format)) return type == AssetType.DATASET ? datasets.parse(source, format, bytes, columnMappings, columnTypes) : ledger.parse(source, format, bytes, type);
            if (format.equals("xmind") && type == AssetType.FUNCTIONAL_CASE) return xmind.parse(source, bytes);
            if (format.equals("md") && markdown.assetTypes().contains(type)) return markdown.parse(source, bytes, type);
            if (format.equals("zip")) return portable.parse(source, format, bytes);
            if (Set.of("json", "yaml", "openapi-json", "openapi-yaml").contains(format)) {
                String syntax = format.endsWith("yaml") ? "yaml" : "json";
                Object root = syntax.equals("yaml") ? ExchangeIO.yaml(ExchangeIO.utf8(bytes, source), source) : ExchangeIO.json(ExchangeIO.utf8(bytes, source), source);
                if (root instanceof Map<?, ?> map && map.containsKey("formatVersion")) return portable.parseObject(source, root);
                if (Set.of(AssetType.API_CASE, AssetType.API_DEFINITION).contains(type)) return api.parse(source, syntax, bytes, type);
                throw ExchangeIO.error(source, 1, "formatVersion", "JSON/YAML 资产文件必须使用 aitest.exchange/v1 结构");
            }
            if (Set.of(AssetType.API_CASE, AssetType.API_DEFINITION).contains(type) && Set.of("curl", "har", "postman").contains(format)) return api.parse(source, format, bytes, type);
            if (type == AssetType.REQUIREMENT && Set.of("md", "docx").contains(format)) {
                if (format.equals("docx")) ExchangeIO.unzip(bytes, source);
                var document = documents.parse("document." + format, bytes);
                return ParsedExchange.valid(ExchangeBundle.of(List.of(new ExchangeNode("requirement_1", type, null, stripExtension(source), 0,
                        Map.of("content", document.content(), "sections", json.tree(json.write(document.sections()))), Map.of()))));
            }
            throw ExchangeIO.error(source, 1, "format", "此资产类型尚未实现该导入格式，请查看 directional capabilities");
        } catch (ExchangeException e) { return ParsedExchange.invalid(e.issue()); }
        catch (com.aitest.common.Problem e) { return ParsedExchange.invalid(new ExchangeIssue(source, 1, "$document", "文件结构无效，无法按所选格式解析")); }
        catch (RuntimeException e) { return ParsedExchange.invalid(new ExchangeIssue(source, 1, "$document", "文件结构、单元格或字段类型无效，无法安全解析")); }
    }
    public Map<String, Boolean> mappingCapabilities(String source, String format, byte[] bytes, AssetType type) {
        if (type == AssetType.DATASET && Set.of("csv", "xlsx").contains(format)) {
            try { ExchangeIO.checkSize(bytes, source); return datasets.mappingCapabilities(source, format, bytes); }
            catch (RuntimeException invalid) { /* An invalid original has no applicable transformations. */ }
        }
        return Map.of("renameColumns", false, "convertTypes", false);
    }
    public Set<String> formats(AssetType type) {
        java.util.Set<String> formats = new java.util.TreeSet<>(Set.of("json", "yaml", "zip", "csv", "xlsx"));
        if (Set.of(AssetType.API_CASE, AssetType.API_DEFINITION).contains(type)) formats.addAll(Set.of("curl", "har", "postman", "openapi-json", "openapi-yaml"));
        if (type == AssetType.REQUIREMENT) formats.addAll(Set.of("md", "docx"));
        if (markdown.assetTypes().contains(type)) formats.add("md");
        if (type == AssetType.FUNCTIONAL_CASE) formats.add("xmind");
        extensions.stream().filter(codec -> codec.assetTypes().contains(type)).forEach(codec -> formats.addAll(codec.formats()));
        return Set.copyOf(formats);
    }
    public static String stripExtension(String source) { int dot = source.lastIndexOf('.'); return dot > 0 ? source.substring(0, dot) : source; }
}
