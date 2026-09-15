package com.aitest.exchange;

import com.aitest.asset.AssetReferences;
import com.aitest.asset.AssetType;
import com.aitest.asset.FieldDefinition;
import com.aitest.common.JsonCodec;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.aitest.exchange.ExchangeIO.*;

/** One column per domain field and one row per independently versioned node. */
@Component
public class LedgerCodec implements ExportCodec {
    private static final List<String> STRUCTURE = List.of("_key", "_type", "_parentKey", "_position", "name", "_references");
    private final JsonCodec json;
    private final PortableBundleCodec portable;
    public LedgerCodec(JsonCodec json, PortableBundleCodec portable) { this.json = json; this.portable = portable; }
    @Override public Set<AssetType> assetTypes() { var types = EnumSet.allOf(AssetType.class); types.remove(AssetType.DATASET); return types; }
    @Override public Set<String> formats() { return Set.of("xlsx", "csv"); }
    @Override public ExportFile export(ExportContext context, String format) { return encode(context.bundle(), context.type().name().toLowerCase(java.util.Locale.ROOT), format); }
    public ExportFile encode(ExchangeBundle bundle, String name, String format) {
        if (format.equals("csv")) {
            var headers = new ArrayList<>(STRUCTURE); var fields = new LinkedHashSet<String>(); bundle.nodes().forEach(n -> n.type().fields().forEach(f -> fields.add(f.key()))); headers.addAll(fields);
            Map<String, Object> manifest = manifest(bundle); manifest.put("csvTextEncoding", TabularSupport.CSV_TEXT_ENCODING);
            List<List<String>> rows = new ArrayList<>(); rows.add(List.of("#" + ExchangeBundle.VERSION, json.write(manifest))); rows.add(headers);
            for (var node : bundle.nodes()) {
                var row = new ArrayList<String>();
                for (String field : headers) {
                    Object value = value(node, field);
                    if (field.equals("_references") || node.type().fields().stream().anyMatch(f -> f.key().equals(field) && f.structured())) row.add(value == null && !node.data().containsKey(field) ? "" : json.write(value));
                    else row.add(value == null ? "" : value.toString());
                }
                rows.add(row);
            }
            return new ExportFile(name + ".csv", "text/csv; charset=utf-8", TabularSupport.csv(rows));
        }
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var instructions = book.createSheet("说明"); TabularSupport.header(instructions, List.of("字段", "使用说明"), book);
            String[][] notes = {{"版本", ExchangeBundle.VERSION}, {"导入模式", "APPEND：预览后一次性创建全新记录，不覆盖现有资产。"}, {"独立记录", "每个资产/步骤一行，各类型独立工作表。按 _parentKey 建层级，_position 控制同级顺序。"}, {"引用", "_references 是字段到本地键的 JSON 对象。外部依赖在 __bundle 声明并于预览时映射。"}, {"JSON 字段", "headers、body、tags 等需要合法 JSON。正文/中文/换行使用原文本；禁止公式。"}};
            var body = TabularSupport.bodyStyle(book);
            for (int i = 0; i < notes.length; i++) { Row row = instructions.createRow(i + 1); TabularSupport.put(row, 0, notes[i][0], body); TabularSupport.put(row, 1, notes[i][1], body); row.setHeightInPoints(36); }
            instructions.setColumnWidth(1, 96 * 256);
            var metadata = book.createSheet("__bundle"); TabularSupport.header(metadata, List.of("key", "value (JSON)"), book); int i = 1;
            for (var entry : manifest(bundle).entrySet()) { var row = metadata.createRow(i++); TabularSupport.put(row, 0, entry.getKey(), body); TabularSupport.put(row, 1, json.write(entry.getValue()), body); }
            for (AssetType type : bundle.nodes().stream().map(ExchangeNode::type).distinct().toList()) {
                var sheet = book.createSheet(type.name()); var headers = new ArrayList<>(STRUCTURE); type.fields().forEach(f -> headers.add(f.key())); TabularSupport.header(sheet, headers, book);
                int rowNumber = 1;
                for (var node : bundle.nodes()) if (node.type() == type) {
                    var row = sheet.createRow(rowNumber++); row.setHeightInPoints(45);
                    for (int c = 0; c < headers.size(); c++) {
                        String field = headers.get(c); Object value = value(node, field);
                        if (field.equals("_references") || type.fields().stream().anyMatch(f -> f.key().equals(field) && f.structured())) value = value == null && !node.data().containsKey(field) ? null : json.write(value);
                        TabularSupport.put(row, c, value, body);
                    }
                }
                if (rowNumber > 1) sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rowNumber - 1, 0, headers.size() - 1));
            }
            book.write(out); return new ExportFile(name + ".xlsx", TabularSupport.XLSX, out.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("Cannot write workbook", e); }
    }
    public ParsedExchange parse(String source, String format, byte[] bytes, AssetType type) {
        if (format.equals("csv")) {
            List<List<String>> rows = TabularSupport.csv(utf8(bytes, source), source); Map<String, Object> metadata = new LinkedHashMap<>(); int header = 0;
            if (!rows.isEmpty() && rows.getFirst().getFirst().equals("#" + ExchangeBundle.VERSION)) {
                if (rows.getFirst().size() != 2) throw error(source, 1, "$csv", "版本头需要 JSON 元信息");
                metadata.putAll(map(ExchangeIO.json(rows.getFirst().get(1), source), source, 1, "$metadata"));
                rows = TabularSupport.decodeCsvText(rows, metadata.remove("csvTextEncoding"), source); header++;
            }
            if (rows.size() <= header) throw error(source, 1, "$csv", "CSV 缺少表头");
            List<String> headers = rows.get(header).stream().map(value -> header(value, type)).toList();
            if (headers.stream().anyMatch(String::isBlank) || headers.stream().distinct().count() != headers.size()) throw error(source, header + 1, "header", "表头必须非空且不能重复，包括映射到相同字段的中文别名");
            List<Map<String, Object>> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>();
            for (int r = header + 1; r < rows.size(); r++) try {
                if (rows.get(r).size() != headers.size()) throw error(source, r + 1, "$row", "列数与表头不一致");
                Map<String, Object> cells = new LinkedHashMap<>(); for (int c = 0; c < headers.size(); c++) cells.put(header(headers.get(c), type), rows.get(r).get(c));
                nodes.add(node(cells, type, source, r + 1));
            } catch (ExchangeException e) { errors.add(e.issue()); }
            return parsed(metadata, nodes, errors, source);
        }
        try (var book = TabularSupport.workbook(bytes, source)) {
            if (book.getSheet("__bundle") == null && type == AssetType.FUNCTIONAL_CASE) return functional(book, source);
            Map<String, Object> metadata = new LinkedHashMap<>(); Sheet manifest = book.getSheet("__bundle");
            if (manifest != null) for (int r = 1; r <= manifest.getLastRowNum(); r++) {
                Row row = manifest.getRow(r); if (row == null) continue;
                String key = TabularSupport.text(row.getCell(0), source, r + 1, "__bundle.key"), value = TabularSupport.text(row.getCell(1), source, r + 1, "__bundle.value");
                if (!key.isEmpty()) metadata.put(key, ExchangeIO.json(value, source));
            }
            List<Map<String, Object>> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>(); int total = 0;
            for (Sheet sheet : book) {
                if (Set.of("__bundle", "说明").contains(sheet.getSheetName())) continue;
                AssetType sheetType = type;
                if (manifest != null) try { sheetType = AssetType.valueOf(sheet.getSheetName()); } catch (IllegalArgumentException e) { throw error(source, 1, sheet.getSheetName(), "版本化工作簿含未知资产工作表"); }
                Row first = sheet.getRow(0); if (first == null) continue;
                if (first.getLastCellNum() > 256 || sheet.getLastRowNum() > MAX_ROWS) throw error(source, 1, sheet.getSheetName(), "工作表超过 100000 行或 256 列");
                List<String> headers = new ArrayList<>(); for (int c = 0; c < first.getLastCellNum(); c++) headers.add(header(TabularSupport.text(first.getCell(c), source, 1, "header"), sheetType));
                if (headers.stream().distinct().count() != headers.size()) throw error(source, 1, "header", "表头重复");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r); if (row == null) continue;
                    if (++total > MAX_NODES) throw error(source, r + 1, "$rows", "资产台账最多 20000 行");
                    try {
                        Map<String, Object> cells = new LinkedHashMap<>(); boolean any = false;
                        for (int c = 0; c < headers.size(); c++) { Object value = TabularSupport.cell(row.getCell(c), source + "!" + sheet.getSheetName(), r + 1, headers.get(c)); cells.put(headers.get(c), value); any |= value != null && !value.toString().isEmpty(); }
                        if (any) nodes.add(node(cells, sheetType, source + "!" + sheet.getSheetName(), r + 1));
                    } catch (ExchangeException e) { errors.add(e.issue()); }
                }
            }
            return parsed(metadata, nodes, errors, source);
        } catch (IOException e) { throw error(source, 1, "$xlsx", "工作簿读取失败"); }
    }
    private ParsedExchange parsed(Map<String, Object> manifest, List<Map<String, Object>> nodes, List<ExchangeIssue> errors, String source) {
        var root = new LinkedHashMap<>(manifest); root.putIfAbsent("formatVersion", ExchangeBundle.VERSION); root.put("nodes", nodes);
        var parsed = portable.parseObject(source, root); errors.addAll(parsed.errors()); return new ParsedExchange(parsed.bundle(), errors);
    }
    private Map<String, Object> node(Map<String, Object> cells, AssetType fallbackType, String source, int row) {
        AssetType type = fallbackType;
        if (cells.get("_type") != null && !cells.get("_type").toString().isBlank()) try { type = AssetType.valueOf(cells.get("_type").toString()); } catch (IllegalArgumentException e) { throw error(source, row, "_type", "未知资产类型"); }
        Map<String, Object> data = new LinkedHashMap<>();
        for (var entry : cells.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue(); if (STRUCTURE.contains(key)) continue;
            FieldDefinition field = type.fields().stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
            if (field == null) { if (value != null && !value.toString().isEmpty()) throw error(source, row, key, "此资产类型没有此字段"); continue; }
            if (value == null || value instanceof String text && text.isEmpty()) continue;
            data.put(key, typed(value, field, source, row));
        }
        var result = new LinkedHashMap<String, Object>(); result.put("key", nonblank(cells.get("_key"), "row_" + row)); result.put("type", type.name());
        result.put("parentKey", blankNull(cells.get("_parentKey"))); result.put("position", number(cells.getOrDefault("_position", row - 2), source, row, "_position"));
        result.put("name", nonblank(cells.get("name"), "")); result.put("data", data);
        Object refs = cells.get("_references"); result.put("references", refs == null || refs.toString().isBlank() ? Map.of() : ExchangeIO.json(refs.toString(), source)); return result;
    }
    private static Object typed(Object value, FieldDefinition field, String source, int row) {
        return switch (field.kind()) {
            case "json" -> {
                if (!(value instanceof String text)) throw error(source, row, field.key(), "JSON 字段必须提供 JSON 文本，不能用 Excel 数值/布尔单元格代替");
                try { yield ExchangeIO.json(text, source); } catch (ExchangeException e) { throw error(source, row, field.key(), "JSON 字段格式无效；对象/数组/字符串/null 必须明确编码"); }
            }
            case "number" -> number(value, source, row, field.key());
            case "boolean" -> {
                if (value instanceof Boolean b) yield b;
                if (value.equals("true")) yield true; if (value.equals("false")) yield false;
                throw error(source, row, field.key(), "布尔字段只接受 true/false");
            }
            default -> value.toString();
        };
    }
    private static long number(Object value, String source, int row, String field) {
        if (value == null || value.toString().isBlank()) return 0;
        try { return new BigDecimal(value.toString()).longValueExact(); } catch (NumberFormatException | ArithmeticException e) { throw error(source, row, field, "必须是整数"); }
    }
    private static String header(String header, AssetType type) {
        String key = header.strip(); if (STRUCTURE.contains(key)) return key;
        if (Set.of("名称", "用例名称", "缺陷名称", "缺陷标题", "项目名称", "计划名称").contains(key)) return "name";
        for (var field : type.fields()) if (key.equals(field.key()) || key.equals(field.label())) return field.key();
        return key;
    }
    private static Map<String, Object> manifest(ExchangeBundle bundle) {
        var map = new LinkedHashMap<String, Object>(); map.put("formatVersion", bundle.formatVersion()); map.put("metadata", bundle.metadata()); map.put("externalReferences", bundle.externalReferences()); map.put("warnings", bundle.warnings()); return map;
    }
    private static Object value(ExchangeNode node, String field) {
        return switch (field) { case "_key" -> node.key(); case "_type" -> node.type(); case "_parentKey" -> node.parentKey(); case "_position" -> node.position(); case "name" -> node.name(); case "_references" -> node.references(); default -> node.data().get(field); };
    }
    private static String nonblank(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString(); }
    private static String blankNull(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }

    private ParsedExchange functional(XSSFWorkbook book, String source) {
        List<ExchangeNode> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>(); Map<String, String> modules = new LinkedHashMap<>(); int cases = 0;
        for (Sheet sheet : book) {
            if (sheet.getSheetName().equals("说明")) continue; Row header = sheet.getRow(0); if (header == null) continue;
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int c = 0; c < header.getLastCellNum(); c++) {
                String text = TabularSupport.text(header.getCell(c), source, 1, "header");
                for (String field : List.of("所属模块", "用例名称", "优先级", "前置条件", "步骤描述", "预期结果", "备注")) if (text.startsWith(field)) columns.put(field, c);
            }
            if (!columns.keySet().containsAll(List.of("所属模块", "用例名称", "步骤描述", "预期结果"))) throw error(source, 1, "header", "功能用例表需要所属模块、用例名称、步骤描述、预期结果列");
            if (sheet.getLastRowNum() > MAX_NODES) throw error(source, 1, "$rows", "功能台账超过 20000 行");
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r); if (row == null) continue;
                try {
                    Map<String, String> values = new LinkedHashMap<>(); for (var field : columns.entrySet()) values.put(field.getKey(), TabularSupport.text(row.getCell(field.getValue()), source, r + 1, field.getKey()));
                    if (values.values().stream().allMatch(String::isBlank)) continue;
                    if (values.get("用例名称").isBlank() || values.get("所属模块").isBlank()) throw error(source, r + 1, "name", "所属模块和用例名称必填");
                    List<String> steps = numbered(values.get("步骤描述"), source, r + 1, "step"), expected = numbered(values.get("预期结果"), source, r + 1, "expected");
                    if (steps.size() != expected.size()) throw error(source, r + 1, "expected", "步骤数与预期结果数量不一致；请使用 1.、2. 连续编号");
                    String parent = null, path = "";
                    for (String name : values.get("所属模块").split("/", -1)) {
                        if (name.isBlank()) throw error(source, r + 1, "module", "模块路径包含空层级"); path += "/" + name;
                        String key = modules.get(path);
                        if (key == null) { key = "module_" + (modules.size() + 1); modules.put(path, key); nodes.add(new ExchangeNode(key, AssetType.MODULE, parent, name, modules.size() - 1, Map.of(), Map.of())); }
                        parent = key;
                    }
                    String key = "case_" + (++cases); Map<String, Object> data = new LinkedHashMap<>(); data.put("priority", values.getOrDefault("优先级", "").isBlank() ? "P1" : values.get("优先级"));
                    data.put("precondition", values.getOrDefault("前置条件", "")); data.put("remark", values.getOrDefault("备注", ""));
                    nodes.add(new ExchangeNode(key, AssetType.FUNCTIONAL_CASE, parent, values.get("用例名称"), cases - 1, data, Map.of()));
                    for (int s = 0; s < steps.size(); s++) nodes.add(new ExchangeNode(key + "_step_" + (s + 1), AssetType.FUNCTIONAL_STEP, key, "步骤 " + (s + 1), s, Map.of("step", steps.get(s), "expected", expected.get(s)), Map.of()));
                } catch (ExchangeException e) { errors.add(e.issue()); }
            }
        }
        return new ParsedExchange(ExchangeBundle.of(nodes), errors);
    }
    static List<String> numbered(String text, String source, int row, String field) {
        if (text == null || text.isBlank()) throw error(source, row, field, "步骤与预期结果不能为空");
        var matcher = Pattern.compile("(?m)^\\h*(\\d+)[.、．)]\\h*").matcher(text); List<String> result = new ArrayList<>(); int start = -1, expected = 1;
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) != expected++) throw error(source, row, field, "步骤编号必须从 1 开始连续递增");
            if (start >= 0) result.add(text.substring(start, matcher.start()).stripTrailing());
            else if (!text.substring(0, matcher.start()).isBlank()) throw error(source, row, field, "首个编号前有无法归属的内容");
            start = matcher.end();
        }
        if (start >= 0) result.add(text.substring(start).stripTrailing()); else result.addAll(text.lines().toList());
        if (result.stream().anyMatch(String::isBlank)) throw error(source, row, field, "步骤或预期结果包含空项"); return result;
    }
}
