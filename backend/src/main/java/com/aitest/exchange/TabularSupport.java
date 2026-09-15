package com.aitest.exchange;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TabularSupport {
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String CSV_TEXT_ENCODING = "apostrophe-v1";
    private TabularSupport() { }
    static List<List<String>> csv(String text, String source) {
        try (CSVParser parser = CSVFormat.RFC4180.parse(new StringReader(text))) {
            List<List<String>> rows = new ArrayList<>();
            for (var record : parser) {
                if (rows.size() > ExchangeIO.MAX_ROWS + 5 || record.size() > 256) throw ExchangeIO.error(source, rows.size() + 1, "$csv", "表格超过 100000 行或 256 列");
                List<String> row = new ArrayList<>(); record.forEach(row::add); rows.add(row);
            }
            return rows;
        } catch (IOException | java.io.UncheckedIOException e) { throw ExchangeIO.error(source, 1, "$csv", "CSV 引号或记录结构不完整"); }
    }
    static byte[] csv(List<List<String>> rows) {
        try (StringWriter writer = new StringWriter(); CSVPrinter printer = new CSVPrinter(writer, CSVFormat.RFC4180)) {
            writer.write('\uFEFF'); for (var row : rows) printer.printRecord(row.stream().map(TabularSupport::safeCsvText).toList()); printer.flush(); return writer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot write CSV", e); }
    }
    private static String safeCsvText(String value) {
        String stripped = value.stripLeading();
        return value.startsWith("'") || !value.isEmpty() && Character.isISOControl(value.charAt(0))
                || !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0 ? "'" + value : value;
    }
    static List<List<String>> decodeCsvText(List<List<String>> rows, Object encoding, String source) {
        if (encoding == null) return rows;
        if (!CSV_TEXT_ENCODING.equals(encoding)) throw ExchangeIO.error(source, 1, "csvTextEncoding", "不支持的 CSV 文本编码");
        return rows.stream().map(row -> row.stream().map(value -> value.startsWith("'") ? value.substring(1) : value).toList()).toList();
    }
    static XSSFWorkbook workbook(byte[] bytes, String source) {
        var archive = ExchangeIO.unzip(bytes, source);
        if (!archive.containsKey("[Content_Types].xml") || archive.keySet().stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin"))) throw ExchangeIO.error(source, 1, "$xlsx", "只支持无宏的 XLSX 工作簿");
        try {
            XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
            if (workbook.getNumberOfSheets() > 64) { workbook.close(); throw ExchangeIO.error(source, 1, "$xlsx", "工作表不能超过 64 个"); }
            return workbook;
        } catch (IOException | RuntimeException e) { if (e instanceof ExchangeException exception) throw exception; throw ExchangeIO.error(source, 1, "$xlsx", "XLSX 损坏、加密或格式不受支持"); }
    }
    static Object cell(Cell cell, String source, int row, String field) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case BLANK -> null;
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime date = cell.getLocalDateTimeCellValue();
                    yield date.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? date.toLocalDate().toString() : date.toString();
                }
                String format = cell.getCellStyle().getDataFormatString();
                if (format != null && (format.matches("0{2,}") || format.equals("@"))) yield new DataFormatter(Locale.CHINA).formatCellValue(cell);
                yield BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
            }
            case FORMULA -> throw ExchangeIO.error(source, row, field, "不会计算公式；请粘贴明确的常量值后导入");
            case ERROR -> throw ExchangeIO.error(source, row, field, "单元格包含 Excel 错误值");
            default -> throw ExchangeIO.error(source, row, field, "不支持的单元格类型");
        };
    }
    static String text(Cell cell, String source, int row, String field) {
        Object value = cell(cell, source, row, field); return value == null ? "" : value.toString();
    }
    static void put(Row row, int column, Object value, CellStyle style) {
        Cell cell = row.createCell(column); if (style != null) cell.setCellStyle(style);
        if (value == null) return;
        if (value instanceof Boolean booleanValue) cell.setCellValue(booleanValue);
        else if (value instanceof Number n && new BigDecimal(n.toString()).precision() <= 15) cell.setCellValue(n.doubleValue());
        else {
            String text = value.toString();
            if (text.length() > 32767) throw ExchangeIO.error("export", row.getRowNum() + 1, row.getSheet().getSheetName(), "单元格超过 Excel 32767 字符限制；请使用 JSON/YAML/ZIP");
            cell.setCellValue(text);
        }
    }
    static CellStyle bodyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setWrapText(true); style.setVerticalAlignment(VerticalAlignment.TOP);
        Font font = workbook.createFont(); font.setFontName("微软雅黑"); font.setFontHeightInPoints((short) 11); style.setFont(font); return style;
    }
    static void header(Sheet sheet, List<String> titles, Workbook workbook) {
        CellStyle style = workbook.createCellStyle(); style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex()); style.setFillPattern(FillPatternType.SOLID_FOREGROUND); style.setWrapText(true);
        Font font = workbook.createFont(); font.setFontName("微软雅黑"); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); style.setFont(font);
        Row row = sheet.createRow(0); row.setHeightInPoints(32);
        for (int i = 0; i < titles.size(); i++) { put(row, i, titles.get(i), style); sheet.setColumnWidth(i, Math.min(48, Math.max(18, titles.get(i).length() + 8)) * 256); }
        sheet.createFreezePane(0, 1);
    }
}
