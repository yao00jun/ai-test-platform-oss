package com.aitest.requirement;

import com.aitest.ai.text.TextCleaner;
import com.aitest.common.Problem;
import com.aitest.storage.FileStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class DocumentParser {
    public ParsedDocument parse(String name, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > FileStorageService.MAX_BYTES) throw Problem.invalid("文件不能为空且不能超过 32 MB");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String content;
        try {
            content = switch (extension) {
                case "md", "txt", "csv" -> text(bytes);
                case "docx" -> word(bytes);
                case "doc" -> legacyWord(bytes);
                case "pdf" -> pdf(bytes);
                case "xlsx", "xls" -> excel(bytes);
                default -> throw Problem.invalid("支持 Word、PDF、Excel、Markdown、TXT 和 CSV 文件");
            };
        } catch (IOException | org.apache.poi.EncryptedDocumentException e) { throw Problem.invalid("无法解析文档，文件可能损坏、加密或与扩展名不符"); }
        catch (RuntimeException e) {
            if (e instanceof Problem problem) throw problem;
            if (e.getClass().getName().startsWith("org.apache.poi.")) throw Problem.invalid("无法解析文档，文件可能损坏、加密或与扩展名不符");
            throw e;
        }
        content = TextCleaner.normalizeDocument(content);
        if (content.isBlank()) throw Problem.invalid("文档没有可提取文本；扫描 PDF 需要先进行 OCR");
        if (content.length() > 2_000_000) throw Problem.invalid("文档正文超过 200 万字符，请拆分文档");
        return new ParsedDocument(content, sections(content));
    }
    private String text(byte[] bytes) {
        try { return decode(bytes, StandardCharsets.UTF_8); }
        catch (CharacterCodingException invalidUtf8) {
            try { return decode(bytes, Charset.forName("GBK")); }
            catch (CharacterCodingException invalidGbk) { throw Problem.invalid("文本编码不是有效的 UTF-8 或 GBK，请另存为 UTF-8 后重试"); }
        }
    }
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        String result = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return result.startsWith("\uFEFF") ? result.substring(1) : result;
    }
    private String word(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder result = new StringBuilder();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) result.append(paragraph.getText()).append('\n');
                if (element instanceof XWPFTable table) for (XWPFTableRow row : table.getRows()) {
                    result.append(String.join(" | ", row.getTableCells().stream().map(XWPFTableCell::getText).toList())).append('\n');
                }
            }
            return result.toString();
        }
    }
    private String legacyWord(byte[] bytes) throws IOException {
        try (var document = new HWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new WordExtractor(document)) { return extractor.getText(); }
    }
    private String pdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > 1000) throw Problem.invalid("PDF 超过 1000 页，请拆分后导入");
            PDFTextStripper stripper = new PDFTextStripper(); stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
    private String excel(byte[] bytes) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            StringBuilder result = new StringBuilder();
            for (var sheet : workbook) {
                result.append("## ").append(sheet.getSheetName()).append('\n');
                for (var row : sheet) {
                    for (int col = 0; col < row.getLastCellNum(); col++) {
                        if (col > 0) result.append(" | ");
                        result.append(formatter.formatCellValue(row.getCell(col)));
                    }
                    result.append('\n');
                    if (result.length() > 2_000_000) throw Problem.invalid("Excel 文本超过 200 万字符");
                }
            }
            return result.toString();
        }
    }
    private List<Section> sections(String content) {
        List<Section> result = new ArrayList<>(); int offset = 0;
        while (offset < content.length()) {
            int end = Math.min(offset + 6000, content.length());
            if (end < content.length()) {
                int newline = content.lastIndexOf('\n', end);
                if (newline > offset + 3000) end = newline + 1;
                else if (Character.isHighSurrogate(content.charAt(end - 1))) end--;
            }
            String text = content.substring(offset, end);
            String title = text.lines().filter(line -> line.startsWith("#")).findFirst().orElse("片段 " + (result.size() + 1));
            result.add(new Section(result.size(), title.substring(0, Math.min(255, title.length())), offset, text)); offset = end;
        }
        return result;
    }
    public record Section(int index, String title, int offset, String text) { }
    public record ParsedDocument(String content, List<Section> sections) { }
}
