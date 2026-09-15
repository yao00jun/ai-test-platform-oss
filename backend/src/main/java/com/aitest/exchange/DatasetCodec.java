package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.aitest.exchange.ExchangeIO.*;

@Component
public class DatasetCodec implements ExportCodec {
    public static final String VERSION = "aitest.dataset/v1";
    private final JsonCodec json;
    public DatasetCodec(JsonCodec json) { this.json = json; }
    @Override public Set<AssetType> assetTypes() { return Set.of(AssetType.DATASET); }
    @Override public Set<String> formats() { return Set.of("csv", "xlsx"); }
    @Override public ExportFile export(ExportContext context, String format) { return encode(context.bundle(), "dataset", format); }
    public ExportFile encode(ExchangeBundle bundle, String name, String format) {
        List<ExchangeNode> nodes = bundle.nodes().stream().filter(n -> n.type() == AssetType.DATASET).toList();
        if (nodes.isEmpty()) throw error(name, 1, "nodes", "未选择数据集");
        if (format.equals("csv")) {
            if (nodes.size() != 1) throw error(name, 1, "nodes", "CSV 一次导出一个数据集；多数据集请使用 XLSX/JSON/ZIP");
            var node = nodes.getFirst(); List<String> columns = columns(node); List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("#" + VERSION, json.write(Map.of("node", descriptor(node), "metadata", bundle.metadata(), "cellEncoding", "json", "csvTextEncoding", TabularSupport.CSV_TEXT_ENCODING)))); rows.add(columns);
            for (Object value : list(node.data().get("rows"), name, 1, "rows")) {
                var row = map(value, name, 1, "rows"); List<String> cells = new ArrayList<>();
                for (String column : columns) cells.add(row.containsKey(column) ? json.write(row.get(column)) : "@missing");
                rows.add(cells);
            }
            return new ExportFile(name + ".csv", "text/csv; charset=utf-8", TabularSupport.csv(rows));
        }
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var manifest = book.createSheet("__dataset"); TabularSupport.header(manifest, List.of("key", "value (JSON)"), book);
            var body = TabularSupport.bodyStyle(book); int manifestRow = 1;
            for (var entry : Map.of("formatVersion", VERSION, "metadata", bundle.metadata()).entrySet()) { var row = manifest.createRow(manifestRow++); TabularSupport.put(row, 0, entry.getKey(), body); TabularSupport.put(row, 1, json.write(entry.getValue()), body); }
            var types = book.createSheet("__types"); TabularSupport.header(types, List.of("sheet", "row", "cellTypes (JSON)"), book); int typeRow = 1, index = 1;
            for (var node : nodes) {
                String sheetName = "数据_" + index++; var sheet = book.createSheet(sheetName); List<String> columns = columns(node); TabularSupport.header(sheet, columns, book);
                Map<String, Object> description = new LinkedHashMap<>(descriptor(node)); description.put("sheet", sheetName);
                var entry = manifest.createRow(manifestRow++); TabularSupport.put(entry, 0, "node", body); TabularSupport.put(entry, 1, json.write(description), body);
                int rowIndex = 1;
                for (Object value : list(node.data().get("rows"), name, 1, "rows")) {
                    var values = map(value, name, rowIndex + 1, "rows"); Row row = sheet.createRow(rowIndex); row.setHeightInPoints(32); List<String> kinds = new ArrayList<>();
                    for (int c = 0; c < columns.size(); c++) {
                        String column = columns.get(c); Object item = values.get(column); String kind = !values.containsKey(column) ? "MISSING" : item == null ? "NULL" : item instanceof String ? "STRING" : item instanceof Number ? "NUMBER" : item instanceof Boolean ? "BOOLEAN" : "JSON";
                        kinds.add(kind); TabularSupport.put(row, c, kind.equals("JSON") ? json.write(item) : item, body);
                    }
                    Row kindRow = types.createRow(typeRow++); TabularSupport.put(kindRow, 0, sheetName, body); TabularSupport.put(kindRow, 1, rowIndex + 1, body); TabularSupport.put(kindRow, 2, json.write(kinds), body); rowIndex++;
                }
            }
            book.write(out); return new ExportFile(name + ".xlsx", TabularSupport.XLSX, out.toByteArray());
        } catch (IOException e) { throw new IllegalStateException("Cannot write dataset workbook", e); }
    }
    public ParsedExchange parse(String source, String format, byte[] bytes, Map<String, String> mappings, Map<String, String> types) {
        if (format.equals("csv")) return csv(source, bytes, mappings, types);
        try (var book = TabularSupport.workbook(bytes, source)) {
            Sheet manifest = book.getSheet("__dataset"); Map<String, Object> metadata = new LinkedHashMap<>(); List<Map<String, Object>> descriptors = new ArrayList<>();
            if (manifest != null && !types.isEmpty()) throw error(source, 1, "columnTypes", "版本化数据集已经保存单元格类型，不支持导入时再次转换类型");
            if (manifest != null) {
                for (int r = 1; r <= manifest.getLastRowNum(); r++) {
                    Row row = manifest.getRow(r); if (row == null) continue; String key = TabularSupport.text(row.getCell(0), source, r + 1, "__dataset.key");
                    Object value = ExchangeIO.json(TabularSupport.text(row.getCell(1), source, r + 1, "__dataset.value"), source);
                    if (key.equals("formatVersion") && !VERSION.equals(value)) throw error(source, r + 1, "formatVersion", "不支持的数据集版本");
                    if (key.equals("metadata")) metadata.putAll(map(value, source, r + 1, "metadata"));
                    if (key.equals("node")) descriptors.add(map(value, source, r + 1, "node"));
                }
            } else {
                for (Sheet sheet : book) if (!sheet.getSheetName().equals("说明")) descriptors.add(Map.of("sheet", sheet.getSheetName(), "key", "dataset_" + (descriptors.size() + 1), "name", book.getNumberOfSheets() == 1 ? ExchangeParserRegistry.stripExtension(source) : sheet.getSheetName(), "position", descriptors.size()));
            }
            Map<String, List<?>> rowTypes = new LinkedHashMap<>(); Sheet typeSheet = book.getSheet("__types");
            if (typeSheet != null) for (int r = 1; r <= typeSheet.getLastRowNum(); r++) {
                Row row = typeSheet.getRow(r); if (row == null) continue;
                String key = TabularSupport.text(row.getCell(0), source, r + 1, "sheet") + ":" + TabularSupport.text(row.getCell(1), source, r + 1, "row");
                rowTypes.put(key, list(ExchangeIO.json(TabularSupport.text(row.getCell(2), source, r + 1, "types"), source), source, r + 1, "types"));
            }
            List<ExchangeNode> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>();
            var originalColumns = new java.util.LinkedHashSet<String>();
            Map<String, List<String>> sheetColumns = new LinkedHashMap<>();
            for (var description : descriptors) {
                String sheetName = description.get("sheet").toString(); Sheet sheet = book.getSheet(sheetName);
                if (sheet == null) throw error(source, 1, "sheet", "版本元信息引用的工作表不存在");
                if (sheet.getLastRowNum() > MAX_ROWS) throw error(source, 1, sheetName, "数据集最多 100000 行");
                Row heading = sheet.getRow(0); if (heading == null || heading.getLastCellNum() < 1) throw error(source, 1, "columns", "数据集缺少表头");
                List<String> original = new ArrayList<>(); for (int c = 0; c < heading.getLastCellNum(); c++) original.add(TabularSupport.text(heading.getCell(c), source, 1, "columns"));
                originalColumns.addAll(original);
                sheetColumns.put(sheetName, original);
            }
            validateMappingKeys(originalColumns, mappings, types, source);
            for (var description : descriptors) {
                String sheetName = description.get("sheet").toString(); Sheet sheet = book.getSheet(sheetName);
                List<String> original = sheetColumns.get(sheetName);
                Map<String, String> sheetMappings = new LinkedHashMap<>(), sheetTypes = new LinkedHashMap<>();
                mappings.forEach((key, value) -> { if (original.contains(key)) sheetMappings.put(key, value); });
                var mappedNames = original.stream().map(column -> sheetMappings.getOrDefault(column, column)).toList();
                types.forEach((key, value) -> { if (original.contains(key) || mappedNames.contains(key)) sheetTypes.put(key, value); });
                List<String> columns = mappedColumns(original, sheetMappings, sheetTypes, source); List<Map<String, Object>> rows = new ArrayList<>();
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r); if (row == null) { rows.add(new LinkedHashMap<>()); continue; }
                    try {
                        Map<String, Object> values = new LinkedHashMap<>(); List<?> kinds = rowTypes.get(sheetName + ":" + (r + 1));
                        if (manifest != null && (kinds == null || kinds.size() != columns.size())) throw error(source, r + 1, "__types", "版本化数据集缺少对应行的单元格类型");
                        for (int c = 0; c < columns.size(); c++) {
                            Object value = TabularSupport.cell(row.getCell(c), source + "!" + sheetName, r + 1, columns.get(c));
                            String kind = kinds == null ? sheetTypes.getOrDefault(original.get(c), sheetTypes.get(columns.get(c))) : String.valueOf(kinds.get(c));
                            if ("MISSING".equals(kind)) continue;
                            values.put(columns.get(c), kinds == null && kind == null ? value : convert(value, kind, source, r + 1, columns.get(c)));
                        }
                        rows.add(values);
                    } catch (ExchangeException e) { errors.add(e.issue()); }
                }
                nodes.add(new ExchangeNode(String.valueOf(description.get("key")), AssetType.DATASET, null, String.valueOf(description.get("name")), nodes.size(), Map.of("columns", columns, "rows", rows), Map.of()));
            }
            metadata.put("columnMappings", mappings); metadata.put("columnTypes", types); metadata.put("originalColumns", List.copyOf(originalColumns));
            metadata.put("mappingCapabilities", Map.of("renameColumns", true, "convertTypes", manifest == null));
            return new ParsedExchange(new ExchangeBundle(ExchangeBundle.VERSION, metadata, nodes, Map.of(), List.of()), errors);
        } catch (IOException e) { throw error(source, 1, "$xlsx", "读取数据集工作簿失败"); }
    }
    private ParsedExchange csv(String source, byte[] bytes, Map<String, String> mappings, Map<String, String> types) {
        List<List<String>> input = TabularSupport.csv(utf8(bytes, source), source); int header = 0; boolean typed = false; Map<String, Object> description = Map.of(); Map<String, Object> metadata = new LinkedHashMap<>();
        if (!input.isEmpty() && input.getFirst().getFirst().equals("#" + VERSION)) {
            if (input.getFirst().size() != 2) throw error(source, 1, "metadata", "CSV 版本头缺少 JSON 元信息");
            var manifest = map(ExchangeIO.json(input.getFirst().get(1), source), source, 1, "metadata");
            if (!"json".equals(manifest.get("cellEncoding"))) throw error(source, 1, "cellEncoding", "不支持的数据单元格编码");
            input = TabularSupport.decodeCsvText(input, manifest.get("csvTextEncoding"), source);
            description = map(manifest.get("node"), source, 1, "node"); metadata.putAll(map(manifest.getOrDefault("metadata", Map.of()), source, 1, "metadata")); typed = true; header++;
        }
        if (typed && !types.isEmpty()) throw error(source, 1, "columnTypes", "版本化数据集已经保存单元格类型，不支持导入时再次转换类型");
        if (input.size() <= header) throw error(source, 1, "columns", "数据集缺少表头");
        List<String> original = input.get(header), columns = mappedColumns(original, mappings, types, source); List<Map<String, Object>> rows = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>();
        for (int r = header + 1; r < input.size(); r++) try {
            List<String> record = input.get(r); if (record.size() != columns.size()) throw error(source, r + 1, "$row", "行列数与表头不一致");
            var row = new LinkedHashMap<String, Object>();
            for (int c = 0; c < columns.size(); c++) {
                String value = record.get(c); if (typed && value.equals("@missing")) continue;
                Object parsed;
                if (typed) parsed = ExchangeIO.json(value, source);
                else if (value.equals("\\N")) parsed = null;
                else if (value.equals("\\\\N")) parsed = "\\N";
                else parsed = convert(value, types.getOrDefault(original.get(c), types.getOrDefault(columns.get(c), "STRING")), source, r + 1, columns.get(c));
                row.put(columns.get(c), parsed);
            }
            rows.add(row);
        } catch (ExchangeException e) { errors.add(e.issue()); }
        metadata.put("columnMappings", mappings); metadata.put("columnTypes", types); metadata.put("originalColumns", original); metadata.put("nullEncoding", "\\N (empty cells remain strings)");
        metadata.put("mappingCapabilities", Map.of("renameColumns", true, "convertTypes", !typed));
        var node = new ExchangeNode(String.valueOf(description.getOrDefault("key", "dataset_1")), AssetType.DATASET, null, String.valueOf(description.getOrDefault("name", ExchangeParserRegistry.stripExtension(source))), 0, Map.of("columns", columns, "rows", rows), Map.of());
        return new ParsedExchange(new ExchangeBundle(ExchangeBundle.VERSION, metadata, List.of(node), Map.of(), List.of()), errors);
    }
    private static List<String> mappedColumns(List<String> original, Map<String, String> mappings, Map<String, String> types, String source) {
        if (original.isEmpty() || original.size() > 200 || original.stream().anyMatch(String::isBlank) || original.stream().distinct().count() != original.size()) throw error(source, 1, "columns", "数据集需要 1–200 个非空且唯一的列名");
        validateMappingKeys(new java.util.LinkedHashSet<>(original), mappings, types, source);
        List<String> columns = original.stream().map(c -> mappings.getOrDefault(c, c)).toList();
        if (columns.stream().anyMatch(String::isBlank) || columns.stream().distinct().count() != columns.size()) throw error(source, 1, "columnMappings", "列映射产生空白或重复列名");
        return columns;
    }
    private static void validateMappingKeys(Set<String> original, Map<String, String> mappings, Map<String, String> types, String source) {
        for (String key : mappings.keySet()) if (!original.contains(key)) throw error(source, 1, "columnMappings." + key, "映射的原始列不存在");
        Set<String> mapped = new java.util.HashSet<>(); original.forEach(column -> mapped.add(mappings.getOrDefault(column, column)));
        for (var entry : types.entrySet()) if ((!original.contains(entry.getKey()) && !mapped.contains(entry.getKey())) || entry.getValue() == null || !Set.of("STRING", "NUMBER", "INTEGER", "BOOLEAN", "DATE", "JSON", "NULL").contains(entry.getValue().toUpperCase(Locale.ROOT))) throw error(source, 1, "columnTypes", "类型声明包含未知列或类型");
    }
    public Map<String, Boolean> mappingCapabilities(String source, String format, byte[] bytes) {
        if (format.equals("csv")) {
            var rows = TabularSupport.csv(utf8(bytes, source), source);
            boolean typed = !rows.isEmpty() && !rows.getFirst().isEmpty() && rows.getFirst().getFirst().equals("#" + VERSION);
            return Map.of("renameColumns", true, "convertTypes", !typed);
        }
        try (var book = TabularSupport.workbook(bytes, source)) {
            return Map.of("renameColumns", true, "convertTypes", book.getSheet("__dataset") == null);
        } catch (IOException e) { throw error(source, 1, "$xlsx", "读取数据集工作簿失败"); }
    }
    private static Object convert(Object value, String type, String source, int row, String field) {
        if (type == null) return value;
        type = type.toUpperCase(Locale.ROOT); if (value == null && !type.equals("STRING")) return null;
        String text = value == null ? "" : value.toString();
        try {
            return switch (type) {
                case "STRING" -> text;
                case "NULL" -> { if (value != null && !text.isEmpty() && !text.equals("null") && !text.equals("\\N")) throw new IllegalArgumentException(); yield null; }
                case "NUMBER" -> { if (text.length() > 200) throw new IllegalArgumentException(); yield new BigDecimal(text); }
                case "INTEGER" -> new BigDecimal(text).toBigIntegerExact();
                case "BOOLEAN" -> { if (value instanceof Boolean b) yield b; if (text.equals("true")) yield true; if (text.equals("false")) yield false; throw new IllegalArgumentException(); }
                case "DATE" -> LocalDate.parse(text).toString();
                case "JSON" -> ExchangeIO.json(text, source);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException e) { throw error(source, row, field, "单元格与声明类型不符；日期请使用 YYYY-MM-DD，数字/布尔/JSON 不接受隐式猜测"); }
    }
    private static List<?> list(Object value, String source, int row, String field) { if (!(value instanceof List<?> list)) throw error(source, row, field, "需要数组"); return list; }
    private static List<String> columns(ExchangeNode node) { return list(node.data().get("columns"), "export", 1, "columns").stream().map(Object::toString).toList(); }
    private static Map<String, Object> descriptor(ExchangeNode node) { return Map.of("key", node.key(), "name", node.name(), "position", node.position(), "columns", columns(node)); }
}
