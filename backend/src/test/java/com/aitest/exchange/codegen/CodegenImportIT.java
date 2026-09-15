package com.aitest.exchange.codegen;

import com.aitest.asset.AssetType;
import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CodegenImportIT extends ExchangeHttpTest {
    @TempDir Path temporary;

    @Test void importsRealJavaRecorderStructureIncludingFramesPopupsAndAssertions() throws Exception {
        String project = project().id();
        var result = preview(project, "UI_SCENARIO", "java", """
                import com.microsoft.playwright.*;
                import com.microsoft.playwright.options.*;
                import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
                public class Example {
                  public static void main(String[] args) {
                    try (Playwright playwright = Playwright.create()) {
                      Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
                      BrowserContext context = browser.newContext();
                      Page page = context.newPage();
                      page.navigate("https://example.test/login");
                      page.getByLabel("账号").fill("tester");
                      page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录").setExact(true)).click();
                      assertThat(page.getByTestId("status")).hasText("欢迎");
                      page.frameLocator("#inner").getByText("Inside", new FrameLocator.GetByTextOptions().setExact(true)).click();
                      Page page1 = page.waitForPopup(() -> {
                        page.getByText("详情").click();
                      });
                      assertThat(page1).hasURL("https://example.test/details");
                      page1.close();
                      context.close();
                      browser.close();
                    }
                  }
                }
                """);
        assertThat(result.get("status")).as(json.write(result)).isEqualTo("READY");
        var steps = objects(result.get("nodes")).stream().filter(node -> node.get("type").equals("UI_STEP")).toList();
        assertThat(steps).extracting(node -> map(node.get("data")).get("action")).containsExactly("navigate", "fill", "click", "assertText", "click", "popup", "assertUrl", "closePage");
        assertThat(map(steps.get(1).get("data"))).containsEntry("selector", "label=账号").containsEntry("exactMatch", false);
        assertThat(map(steps.get(2).get("data"))).containsEntry("selector", "role=button[name=登录]").containsEntry("exactMatch", true);
        assertThat(map(steps.get(4).get("data"))).containsEntry("frame", "#inner");
        assertThat(map(steps.get(5).get("data"))).containsEntry("saveAs", "page1");
        assertThat(map(steps.get(6).get("data"))).containsEntry("pageAlias", "page1");
        var applied = apply(project, result);
        assertThat(((Number) applied.get("createdCount")).intValue()).isEqualTo(9);
        assertThat(assets.all(project).stream().filter(asset -> asset.type() == AssetType.UI_STEP)).hasSize(8);
    }

    @Test void javascriptAndTypescriptRecorderPromisePatternsKeepTheirOrder() throws Exception {
        String source = """
                import { test, expect } from '@playwright/test';
                test('录制操作', async ({ page }) => {
                  await page.goto('https://example.test/');
                  await page.getByPlaceholder('账号', { exact: true }).fill('测试\\n第二行');
                  const page1Promise = page.waitForEvent('popup');
                  await page.getByRole('link', { name: '详情' }).click();
                  const page1 = await page1Promise;
                  await expect(page1.getByTestId('title')).toHaveText('订单详情');
                  await page1.close();
                });
                """;
        for (String format : List.of("js", "ts")) {
            var result = preview(project().id(), "UI_SCENARIO", format, source);
            assertThat(result.get("status")).as(json.write(result)).isEqualTo("READY");
            var steps = objects(result.get("nodes")).stream().filter(node -> node.get("type").equals("UI_STEP")).toList();
            assertThat(steps).extracting(node -> map(node.get("data")).get("action")).containsExactly("navigate", "fill", "popup", "assertText", "closePage");
            assertThat(map(steps.get(1).get("data"))).containsEntry("value", "测试\n第二行").containsEntry("exactMatch", true);
            assertThat(map(steps.get(2).get("data"))).containsEntry("saveAs", "page1");
            assertThat(map(steps.get(3).get("data"))).containsEntry("pageAlias", "page1");
        }
    }

    @Test void unsupportedSourceIsReportedAtItsLineAndNeverExecuted() throws Exception {
        Path marker = temporary.resolve("must-not-exist.txt");
        String source = "page.goto('https://example.test/');\nrequire('node:fs').writeFileSync(" + json.write(marker.toString()) + ", 'should never execute');\npage.getByText(/dynamic.+/).click();";
        var result = preview(project().id(), "UI_SCENARIO", "js", source);
        assertThat(result.get("status")).isEqualTo("INVALID");
        assertThat(objects(result.get("errors"))).anySatisfy(error -> assertThat(((Number) error.get("row")).intValue()).isEqualTo(2));
        assertThat(Files.exists(marker)).isFalse();
        assertThat(objects(result.get("nodes"))).anySatisfy(node -> assertThat(map(node.get("data"))).containsEntry("action", "navigate"));
    }
}
