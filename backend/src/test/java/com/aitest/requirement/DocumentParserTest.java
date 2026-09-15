package com.aitest.requirement;

import com.aitest.common.Problem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser();
    @Test void docxPreservesParagraphTableOrderAndBusinessVariables() throws Exception {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("支付规则 ${orderNo}");
            doc.createTable(1, 2).getRow(0).getCell(0).setText("退款 ≥ 100");
            doc.createParagraph().createRun().setText("结束");
            doc.write(bytes);
            String content = parser.parse("中文需求.docx", bytes.toByteArray()).content();
            assertThat(content).contains("支付规则 ${orderNo}", "退款 ≥ 100", "结束");
            assertThat(content.indexOf("支付")).isLessThan(content.indexOf("退款"));
            assertThat(content.indexOf("退款")).isLessThan(content.indexOf("结束"));
        }
    }
    @Test void workbookIncludesSheetNamesAndDoesNotExecuteFormulaText() throws Exception {
        try (var workbook = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("验收规则");
            sheet.createRow(0).createCell(0).setCellValue("订单金额");
            sheet.createRow(1).createCell(0).setCellValue(100);
            workbook.write(bytes);
            assertThat(parser.parse("需求.xlsx", bytes.toByteArray()).content()).contains("验收规则", "订单金额", "100");
        }
    }
    @Test void emptyUnsupportedAndLargeDocumentsFailExplicitlyAndSectionsCoverAllText() {
        assertThatThrownBy(() -> parser.parse("a.md", new byte[0])).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> parser.parse("a.exe", new byte[]{1})).isInstanceOf(Problem.class);
        String content = "# 规则\n" + "中文需求 ${token}\n".repeat(2000);
        var parsed = parser.parse("a.md", content.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.sections()).hasSizeGreaterThan(1);
        assertThat(parsed.sections().stream().map(DocumentParser.Section::text).reduce("", String::concat)).isEqualTo(content);
    }
}
