package com.aitest.exchange.codegen;

import com.aitest.asset.AssetType;
import com.aitest.exchange.ParsedExchange;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class RecorderBoundaryTest {
    @Test void rebindingARecordedLocatorCannotRetroactivelyChangeEarlierBindings() {
        ParsedExchange result = parse("js", """
                const button = page.getByRole('button', { name: '保存' });
                const alias = button;
                const button = page.getByRole('button', { name: '删除' });
                await alias.click();
                """);
        assertThat(result.errors()).as("duplicate declaration cannot silently change the target").anySatisfy(issue -> assertThat(issue.row()).isEqualTo(3));
    }
    @Test void shadowingThePageIsReportedRatherThanDiscarded() {
        assertThat(parse("js", "const page = 'not a Page';\npage.goto('https://example.test');").errors()).isNotEmpty();
    }
    @Test void nestedSetupOptionsAreReportedRatherThanDiscarded() {
        assertThat(parse("java", "Page page = Playwright.create().chromium().launch(new BrowserType.LaunchOptions().setProxy(new Proxy(\"http://example.test\"))).newContext().newPage();\npage.navigate(\"https://example.test\");").errors()).isNotEmpty();
    }
    @Test void selfReferentialLocatorCannotOverflowParserStack() {
        assertThatCode(() -> {
            ParsedExchange result = parse("js", "const loop = loop.getByText('unsafe');\nloop.click();");
            assertThat(result.errors()).isNotEmpty();
        }).doesNotThrowAnyException();
    }
    @Test void ordinaryRecorderOptionsThatChangeSemanticsAreNeverDropped() {
        ParsedExchange result = parse("java", "page.getByText(\"确认\").click(new Locator.ClickOptions().setForce(true));\npage.navigate(\"https://example.test\");");
        assertThat(result.errors()).anySatisfy(issue -> assertThat(issue.row()).isEqualTo(1));
        assertThat(result.bundle().nodes()).anySatisfy(node -> assertThat(node.data()).containsEntry("action", "navigate"));
    }
    private ParsedExchange parse(String format, String source) { return new CodegenImportCodec().parse("boundary." + format, format, source.getBytes(StandardCharsets.UTF_8), AssetType.UI_SCENARIO); }
}
