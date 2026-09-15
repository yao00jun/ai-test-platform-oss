package com.aitest.engine.web;

import com.aitest.common.*;
import com.aitest.execution.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.assertions.*;
import com.microsoft.playwright.options.*;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Runs on the worker's owning thread; no Page or Browser escapes this process. */
public final class WebStepExecutor {
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Path directory;
    private final String baseUrl;
    private final WebEvidence evidence;
    private final List<Locator> sensitiveLocators = new ArrayList<>();
    WebStepExecutor(Page page, Path directory, String baseUrl, WebEvidence evidence) {
        pages.put("main", page); this.directory = directory; this.baseUrl = baseUrl; this.evidence = evidence;
    }
    Page page(Map<String, Object> step) {
        String alias = Values.text(step, "pageAlias", "main"); if (alias.isBlank()) alias = "main";
        Page page = pages.get(alias); if (page == null || page.isClosed()) throw Problem.invalid("页面别名不存在或已关闭: " + alias); return page;
    }
    StepResult execute(String id, Map<String, Object> step, Map<String, Object> variables) {
        long started = System.nanoTime();
        Map<String, Object> request = new LinkedHashMap<>(step), actual = new LinkedHashMap<>(), exports = new LinkedHashMap<>();
        List<AssertionResult> assertions = new ArrayList<>(); List<String> artifacts = new ArrayList<>();
        String action = Values.text(step, "action", ""), status = "PASSED", error = null;
        try {
            Page page = page(step); int timeout = Values.integer(step, "timeoutMs", 15000, 100, 120000);
            page.setDefaultTimeout(timeout); page.setDefaultNavigationTimeout(timeout);
            String selector = Values.text(step, "selector", ""), frame = Values.text(step, "frame", "");
            boolean exactMatch = Values.bool(step, "exactMatch", true);
            Locator locator = selector.isBlank() ? null : WebLocator.parse(selector).locate(page, frame, exactMatch);
            String value = Values.text(step, "value", ""), expected = Values.text(step, "expected", "");
            switch (action) {
                case "navigate" -> page.navigate(url(Values.text(step, "url", "")));
                case "_observePage" -> actual.putAll(WebPageObservation.capture(page));
                case "click" -> locator.click();
                case "dblclick" -> locator.dblclick();
                case "fill" -> {
                    if ("password".equalsIgnoreCase(locator.getAttribute("type"))) { evidence.secret(value); sensitiveLocators.add(locator); request.put("value", SecretProtector.MASK); }
                    locator.fill(value);
                }
                case "press" -> locator.press(value);
                case "select", "selectOption" -> locator.selectOption(value);
                case "check" -> locator.check();
                case "uncheck" -> locator.uncheck();
                case "hover" -> locator.hover();
                case "dragAndDrop" -> locator.dragTo(WebLocator.parse(Values.text(step, "targetSelector", "")).locate(page, frame, exactMatch));
                case "upload" -> {
                    List<FilePayload> payloads = new ArrayList<>();
                    for (var file : Values.objects(step.get("_uploads"))) {
                        try { payloads.add(new FilePayload(file.get("name").toString(), file.get("mediaType").toString(), java.nio.file.Files.readAllBytes(Path.of(file.get("path").toString())))); }
                        catch (java.io.IOException errorReadingFile) { throw Problem.invalid("上传附件不可读"); }
                    }
                    locator.setInputFiles(payloads.toArray(FilePayload[]::new));
                    request.remove("_uploads");
                }
                case "wait", "waitFor" -> {
                    if (locator != null) locator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.valueOf(Values.text(step, "waitState", "visible").toUpperCase(Locale.ROOT))).setTimeout(timeout));
                    else page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeout));
                }
                case "assertText" -> { assertThat(locator).hasText(expected, new LocatorAssertions.HasTextOptions().setTimeout(timeout)); actual.put("text", locator.innerText()); }
                case "assertVisible" -> assertThat(locator).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeout));
                case "assertHidden" -> assertThat(locator).isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(timeout));
                case "assertValue" -> { assertThat(locator).hasValue(expected, new LocatorAssertions.HasValueOptions().setTimeout(timeout)); actual.put("value", locator.inputValue()); }
                case "assertCount" -> { assertThat(locator).hasCount(Integer.parseInt(expected), new LocatorAssertions.HasCountOptions().setTimeout(timeout)); actual.put("count", locator.count()); }
                case "assertUrl" -> assertThat(page).hasURL(url(expected), new PageAssertions.HasURLOptions().setTimeout(timeout));
                case "extract" -> {
                    String attribute = Values.text(step, "attribute", ""), name = Values.text(step, "saveAs", "");
                    Object extracted = attribute.isBlank() ? locator.innerText() : attribute.equals("value") ? locator.inputValue() : locator.getAttribute(attribute);
                    exports.put(name, extracted); variables.put(name, extracted);
                    evidence.register(exports);
                    if (evidence.sensitive(name)) sensitiveLocators.add(locator);
                }
                case "screenshot" -> artifacts.add(screenshot(page, id + ".png"));
                case "popup" -> {
                    String alias = Values.text(step, "saveAs", "popup");
                    if (pages.containsKey(alias) && !pages.get(alias).isClosed()) throw Problem.invalid("页面别名已存在: " + alias);
                    Page popup = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(timeout), locator::click);
                    pages.put(alias, popup);
                    double remaining = timeout - (System.nanoTime() - started) / 1_000_000.0;
                    if (remaining <= 0) throw new TimeoutError("弹窗步骤超过配置的超时");
                    popup.setDefaultTimeout(remaining); popup.setDefaultNavigationTimeout(remaining);
                    popup.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(remaining));
                }
                case "download" -> {
                    Download download = page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeout), locator::click);
                    String filename = id + "-" + download.suggestedFilename().replaceAll("[^\\p{L}\\p{N}._-]", "_");
                    download.saveAs(directory.resolve(filename)); artifacts.add(filename); actual.put("filename", download.suggestedFilename());
                }
                case "closePage" -> page.close();
                default -> throw Problem.invalid("不支持的 UI 动作: " + action);
            }
            if (!page.isClosed()) actual.put("url", page.url());
            if (action.startsWith("assert")) assertions.add(new AssertionResult(action, selector, "equals", expected, actual, true, null));
        } catch (AssertionError failure) {
            status = "FAILED"; error = failure.getMessage(); assertions.add(new AssertionResult(action, Values.text(step, "selector", ""), "equals", step.get("expected"), actual, false, error));
        } catch (RuntimeException failure) {
            status = failure instanceof TimeoutError ? "FAILED" : "ERROR";
            error = failure instanceof PlaywrightException || failure instanceof Problem ? failure.getMessage() : "UI 执行错误（" + failure.getClass().getSimpleName() + "）";
        }
        return new StepResult(status, (System.nanoTime() - started) / 1000000, request, actual, assertions, exports, artifacts, error);
    }
    String screenshot(Page page, String filename) {
        List<Locator> masks = new ArrayList<>(); masks.add(page.locator("input[type=password],input[name*=token i],input[name*=secret i]"));
        masks.addAll(sensitiveLocators.stream().filter(locator -> locator.page() == page).toList());
        try { page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve(filename)).setFullPage(true).setMask(masks).setTimeout(5000)); }
        catch (TimeoutError fontOrLayoutTimeout) {
            if (!page.context().browser().browserType().name().equals("chromium")) throw fontOrLayoutTimeout;
            captureChromiumWithoutFontWait(page, masks, directory.resolve(filename));
        }
        return filename;
    }
    private void captureChromiumWithoutFontWait(Page page, List<Locator> masks, Path destination) {
        List<Map<String, Object>> boxes = new ArrayList<>();
        for (Locator group : masks) for (Locator locator : group.all()) {
            var box = locator.boundingBox();
            if (box != null) boxes.add(Map.of("x", box.x, "y", box.y, "width", box.width, "height", box.height));
        }
        // CDP capture does not wait for unresolved web fonts. Mask overlays are fixed code;
        // neither uploaded scripts nor user-supplied JavaScript are evaluated.
        String marker = "aitest-mask-" + UUID.randomUUID();
        page.evaluate("""
                input => { for (const b of input.boxes) { const mask=document.createElement('div');
                  mask.dataset.aitestMask=input.marker;
                  Object.assign(mask.style,{position:'absolute',left:(b.x+scrollX)+'px',top:(b.y+scrollY)+'px',
                    width:b.width+'px',height:b.height+'px',background:'#ff00ff',zIndex:'2147483647',pointerEvents:'none'});
                  document.documentElement.append(mask); } }
                """, Map.of("boxes", boxes, "marker", marker));
        CDPSession session = page.context().newCDPSession(page);
        try {
            var metrics = session.send("Page.getLayoutMetrics").getAsJsonObject("cssContentSize");
            var clip = new com.google.gson.JsonObject(); clip.addProperty("x", 0); clip.addProperty("y", 0);
            clip.addProperty("width", Math.min(16384, metrics.get("width").getAsDouble()));
            clip.addProperty("height", Math.min(16384, metrics.get("height").getAsDouble())); clip.addProperty("scale", 1);
            var options = new com.google.gson.JsonObject(); options.addProperty("format", "png"); options.addProperty("captureBeyondViewport", true); options.add("clip", clip);
            byte[] png = Base64.getDecoder().decode(session.send("Page.captureScreenshot", options).get("data").getAsString());
            java.nio.file.Files.write(destination, png);
        } catch (java.io.IOException error) { throw Problem.invalid("失败截图无法保存"); }
        finally {
            session.detach();
            if (!page.isClosed()) page.evaluate("marker => document.querySelectorAll('[data-aitest-mask]').forEach(e => { if(e.dataset.aitestMask === marker) e.remove(); })", marker);
        }
    }
    private String url(String target) {
        URI uri = URI.create(target); if (!uri.isAbsolute()) uri = URI.create(baseUrl).resolve(uri);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw Problem.invalid("浏览器 URL 必须为不含账号密码的 HTTP/HTTPS 地址"); return uri.toString();
    }
}
