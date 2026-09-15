package com.aitest.report;

import com.aitest.asset.*;
import com.aitest.exchange.ExchangeHttpTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AssetDocumentExportIT extends ExchangeHttpTest {
    @Test void functionalPdfContainsEveryOrderedChineseStepAndLongExpectedText() throws Exception {
        String project = project().id();
        Asset functional = assets.create(project, AssetType.FUNCTIONAL_CASE, null, "退款结算功能用例", Map.of("precondition", "用户已经登录，并有一笔已完成的订单", "priority", "P0"), "MANUAL");
        for (int index = 1; index <= 18; index++) assets.create(project, AssetType.FUNCTIONAL_STEP, functional.id(), "检查项 " + index,
                Map.of("step", "步骤 " + index + "：输入含中文的退款原因并核对金额", "expected", "预期 " + index + "：订单与支付流水一致。" + "跨页文本必须完整可读，不能覆盖后续步骤或丢失字符。".repeat(4)), "MANUAL");
        var response = request("POST", "/api/projects/" + project + "/exports", Map.of("type", "FUNCTIONAL_CASE", "format", "pdf", "assetIds", List.of(functional.id())));
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        Path evidence = Path.of("../.runtime/evidence/reports"); Files.createDirectories(evidence); Files.write(evidence.resolve("functional-cases.pdf"), response.body());
        try (var document = Loader.loadPDF(response.body())) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(text).contains("退款结算功能用例", "预期 1", "预期 18", "检查项 18");
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); page++) javax.imageio.ImageIO.write(renderer.renderImageWithDPI(page, 90), "PNG", evidence.resolve("functional-page-" + (page + 1) + ".png").toFile());
        }
    }
}
