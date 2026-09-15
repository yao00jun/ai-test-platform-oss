import com.google.gson.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.microsoft.playwright.assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.*;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Generated versioned snapshot; edit or run with Java 21 and official Playwright. */
public final class ExportedTest {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)}");
    private static final String[] DATA = {
/*__RECORDED_SCENARIOS__*/
    };
    private static final class InputProblem extends RuntimeException { InputProblem(String message) { super(message); } }

    public static void main(String[] args) throws Exception {
        String specification = new String(Base64.getDecoder().decode(String.join("", DATA)), StandardCharsets.UTF_8);
        List<?> scenarios = JSON.fromJson(specification, List.class);
        Map<String, Object> inputs = new LinkedHashMap<>(readObject(System.getProperty("aitest.variables")));
        for (String name : System.getProperties().stringPropertyNames()) if (name.startsWith("aitest.var.")) inputs.put(name.substring(11), System.getProperty(name));
        Map<String, String> headers = new LinkedHashMap<>();
        readObject(System.getProperty("aitest.headers")).forEach((key, value) -> headers.put(key, Objects.toString(value, "")));
        Path results = Path.of(System.getProperty("aitest.results", "test-results")).toAbsolutePath(); Files.createDirectories(results);
        List<Map<String, Object>> outcomes = new ArrayList<>(); int failures = 0;
        try (Playwright playwright = Playwright.create()) {
            for (Object value : scenarios) {
                Map<String, Object> scenario = map(value), config = map(scenario.get("data"));
                String scenarioId = string(scenario, "id", "scenario");
                Map<String, Object> variables = new LinkedHashMap<>(inputs);
                long duration = number(config, "timeoutMs", 120000);
                AtomicBoolean armed = new AtomicBoolean(true);
                Thread watchdog = Thread.ofPlatform().daemon(true).name("scenario-deadline").start(() -> {
                    try { Thread.sleep(duration); }
                    catch (InterruptedException done) { return; }
                    if (armed.compareAndSet(true, false)) {
                        System.err.println("Scenario exceeded its configured deadline: " + scenarioId);
                        ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
                        Runtime.getRuntime().halt(124);
                    }
                });
                long deadline = System.nanoTime() + duration * 1_000_000;
                BrowserType browserType = switch (string(config, "browser", "CHROMIUM")) {
                    case "FIREFOX" -> playwright.firefox(); case "WEBKIT" -> playwright.webkit(); default -> playwright.chromium();
                };
                boolean headless = Boolean.parseBoolean(System.getProperty("aitest.headless", Boolean.toString(bool(config, "headless", true))));
                String baseUrl = System.getProperty("aitest.baseUrl", string(config, "baseUrl", ""));
                try (Browser browser = browserType.launch(new BrowserType.LaunchOptions().setHeadless(headless).setTimeout(Math.min(30000, duration)));
                     BrowserContext context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true)
                             .setIgnoreHTTPSErrors(bool(config, "ignoreHttpsErrors", false)).setExtraHTTPHeaders(headers)
                             .setViewportSize((int) number(config, "viewportWidth", 1440), (int) number(config, "viewportHeight", 900)))) {
                    Map<String, Page> pages = new LinkedHashMap<>(); pages.put("main", context.newPage());
                    List<Locator> masks = new ArrayList<>(); boolean stopped = false;
                    for (Object item : list(scenario.get("steps"))) {
                        Map<String, Object> step = map(item), original = map(step.get("data"));
                        String id = string(step, "id", "step"); long started = System.nanoTime();
                        Map<String, Object> outcome = new LinkedHashMap<>(); outcome.put("scenarioId", scenarioId); outcome.put("stepId", id); outcome.put("name", step.get("name"));
                        List<String> artifacts = new ArrayList<>(); String status = "PASSED";
                        if (stopped) status = "SKIPPED";
                        else try {
                            Map<String, Object> spec = map(resolve(original, variables, new HashSet<>(), 0));
                            String alias = string(spec, "pageAlias", "main"); if (alias.isBlank()) alias = "main";
                            Page page = pages.get(alias); if (page == null || page.isClosed()) throw new InputProblem("Missing or closed page alias: " + alias);
                            double remaining = (deadline - System.nanoTime()) / 1_000_000.0;
                            if (remaining <= 0) throw new InputProblem("Scenario deadline reached");
                            double timeout = Math.min(number(spec, "timeoutMs", 15000), remaining);
                            page.setDefaultTimeout(timeout); page.setDefaultNavigationTimeout(timeout);
                            String selector = string(spec, "selector", ""), frame = string(spec, "frame", "");
                            boolean exact = bool(spec, "exactMatch", true);
                            Locator locator = selector.isBlank() ? null : locate(page, frame, selector, exact);
                            String action = string(spec, "action", ""), text = string(spec, "value", ""), expected = string(spec, "expected", "");
                            switch (action) {
                                case "navigate" -> page.navigate(url(baseUrl, string(spec, "url", "")));
                                case "click" -> locator.click();
                                case "dblclick" -> locator.dblclick();
                                case "fill" -> {
                                    if (sensitive(string(original, "value", "")) || "password".equalsIgnoreCase(locator.getAttribute("type"))) masks.add(locator);
                                    locator.fill(text);
                                }
                                case "press" -> locator.press(text);
                                case "select", "selectOption" -> locator.selectOption(text);
                                case "check" -> locator.check(); case "uncheck" -> locator.uncheck(); case "hover" -> locator.hover();
                                case "dragAndDrop" -> locator.dragTo(locate(page, frame, string(spec, "targetSelector", ""), exact));
                                case "upload" -> {
                                    List<FilePayload> uploads = new ArrayList<>();
                                    for (Object upload : list(spec.get("uploads"))) {
                                        Map<String, Object> file = map(upload); String fileId = string(file, "id", "");
                                        String path = System.getProperty("aitest.file." + fileId, string(file, "path", ""));
                                        if (path.isBlank() || !Files.isRegularFile(Path.of(path))) throw new InputProblem("Upload requires -Daitest.file." + fileId + "=path");
                                        if (Files.size(Path.of(path)) > 32 * 1024 * 1024) throw new InputProblem("Upload exceeds 32 MB");
                                        uploads.add(new FilePayload(string(file, "name", "attachment"), string(file, "mediaType", "application/octet-stream"), Files.readAllBytes(Path.of(path))));
                                    }
                                    if (uploads.isEmpty()) throw new InputProblem("Upload step has no files");
                                    locator.setInputFiles(uploads.toArray(FilePayload[]::new));
                                }
                                case "wait", "waitFor" -> {
                                    if (locator == null) page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeout));
                                    else locator.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.valueOf(string(spec, "waitState", "visible").toUpperCase(Locale.ROOT))).setTimeout(timeout));
                                }
                                case "assertText" -> assertThat(locator).hasText(expected, new LocatorAssertions.HasTextOptions().setTimeout(timeout));
                                case "assertVisible" -> assertThat(locator).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeout));
                                case "assertHidden" -> assertThat(locator).isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(timeout));
                                case "assertValue" -> assertThat(locator).hasValue(expected, new LocatorAssertions.HasValueOptions().setTimeout(timeout));
                                case "assertCount" -> assertThat(locator).hasCount(Integer.parseInt(expected), new LocatorAssertions.HasCountOptions().setTimeout(timeout));
                                case "assertUrl" -> assertThat(page).hasURL(url(baseUrl, expected), new PageAssertions.HasURLOptions().setTimeout(timeout));
                                case "extract" -> {
                                    String attribute = string(spec, "attribute", ""), target = string(spec, "saveAs", "");
                                    if (target.isBlank()) throw new InputProblem("Extract requires a variable name");
                                    variables.put(target, attribute.isBlank() ? locator.innerText() : attribute.equals("value") ? locator.inputValue() : locator.getAttribute(attribute));
                                    if (sensitive(target)) masks.add(locator);
                                }
                                case "screenshot" -> artifacts.add(screenshot(page, masks, results, id + ".png"));
                                case "popup" -> {
                                    String target = string(spec, "saveAs", "popup");
                                    if (pages.containsKey(target) && !pages.get(target).isClosed()) throw new InputProblem("Duplicate popup alias: " + target);
                                    Page popup = page.waitForPopup(new Page.WaitForPopupOptions().setTimeout(timeout), locator::click); pages.put(target, popup);
                                    double left = Math.min(timeout - (System.nanoTime() - started) / 1_000_000.0, (deadline - System.nanoTime()) / 1_000_000.0);
                                    if (left <= 0) throw new InputProblem("Popup step exceeded its deadline");
                                    popup.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(left));
                                }
                                case "download" -> {
                                    Download download = page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(timeout), locator::click);
                                    String filename = id + "-" + download.suggestedFilename().replaceAll("[^\\p{L}\\p{N}._-]", "_");
                                    download.saveAs(results.resolve(filename)); artifacts.add(filename);
                                }
                                case "closePage" -> page.close();
                                default -> throw new InputProblem("Unknown action: " + action);
                            }
                        } catch (Exception | AssertionError failure) {
                            failures++; status = failure instanceof AssertionError || failure instanceof TimeoutError ? "FAILED" : "ERROR";
                            outcome.put("error", failure instanceof InputProblem ? failure.getMessage() : failure.getClass().getSimpleName());
                            if (!bool(config, "continueOnFailure", false)) stopped = true;
                            Page page = pages.get(string(original, "pageAlias", "main")); if (page == null) page = pages.get("main");
                            if (page != null && !page.isClosed()) try { artifacts.add(screenshot(page, masks, results, id + "-failure.png")); }
                            catch (RuntimeException evidenceFailure) { outcome.put("evidenceWarning", "Failure screenshot unavailable"); }
                        }
                        outcome.put("status", status); outcome.put("durationMs", (System.nanoTime() - started) / 1_000_000); outcome.put("artifacts", artifacts);
                        outcomes.add(outcome); System.out.println(id + " " + status);
                        Files.writeString(results.resolve("results.json"), JSON.toJson(outcomes), StandardCharsets.UTF_8);
                    }
                } finally { armed.set(false); watchdog.interrupt(); }
            }
        }
        if (failures > 0) System.exit(1);
    }
    private static Locator locate(Page page, String frame, String source, boolean exact) {
        String kind = "selector", value = source, name = null;
        for (String prefix : List.of("role", "label", "text", "placeholder", "testId", "title", "alt")) if (source.startsWith(prefix + "=")) { kind = prefix; value = source.substring(prefix.length() + 1); break; }
        if (kind.equals("role")) {
            int start = value.indexOf("[name="); if (start >= 0) { name = unquote(value.substring(start + 6, value.length() - 1)); value = value.substring(0, start); }
        } else if (!kind.equals("selector")) value = unquote(value);
        AriaRole role = kind.equals("role") ? AriaRole.valueOf(value.replace("-", "").toUpperCase(Locale.ROOT)) : null;
        if (!frame.isBlank()) {
            FrameLocator target = page.frameLocator(frame);
            return switch (kind) {
                case "role" -> target.getByRole(role, new FrameLocator.GetByRoleOptions().setName(name).setExact(exact));
                case "label" -> target.getByLabel(value, new FrameLocator.GetByLabelOptions().setExact(exact));
                case "text" -> target.getByText(value, new FrameLocator.GetByTextOptions().setExact(exact));
                case "placeholder" -> target.getByPlaceholder(value, new FrameLocator.GetByPlaceholderOptions().setExact(exact));
                case "testId" -> target.getByTestId(value);
                case "title" -> target.getByTitle(value, new FrameLocator.GetByTitleOptions().setExact(exact));
                case "alt" -> target.getByAltText(value, new FrameLocator.GetByAltTextOptions().setExact(exact));
                default -> target.locator(value);
            };
        }
        return switch (kind) {
            case "role" -> page.getByRole(role, new Page.GetByRoleOptions().setName(name).setExact(exact));
            case "label" -> page.getByLabel(value, new Page.GetByLabelOptions().setExact(exact));
            case "text" -> page.getByText(value, new Page.GetByTextOptions().setExact(exact));
            case "placeholder" -> page.getByPlaceholder(value, new Page.GetByPlaceholderOptions().setExact(exact));
            case "testId" -> page.getByTestId(value);
            case "title" -> page.getByTitle(value, new Page.GetByTitleOptions().setExact(exact));
            case "alt" -> page.getByAltText(value, new Page.GetByAltTextOptions().setExact(exact));
            default -> page.locator(value);
        };
    }
    private static String screenshot(Page page, List<Locator> sensitive, Path directory, String filename) {
        List<Locator> masks = new ArrayList<>(); masks.add(page.locator("input[type=password],input[name*=token i],input[name*=secret i]"));
        sensitive.stream().filter(locator -> locator.page() == page).forEach(masks::add);
        page.screenshot(new Page.ScreenshotOptions().setPath(directory.resolve(filename)).setFullPage(true).setMask(masks).setTimeout(3000)); return filename;
    }
    private static Object resolve(Object value, Map<String, Object> variables, Set<String> path, int depth) {
        if (depth > 32) throw new InputProblem("Variable nesting exceeds 32 levels");
        if (value instanceof Map<?, ?> input) { Map<String, Object> result = new LinkedHashMap<>(); input.forEach((key, item) -> result.put(key.toString(), resolve(item, variables, path, depth + 1))); return result; }
        if (value instanceof List<?> input) return input.stream().map(item -> resolve(item, variables, path, depth + 1)).toList();
        if (!(value instanceof String text)) return value;
        Matcher matcher = VARIABLE.matcher(text);
        if (matcher.matches()) return variable(matcher.group(1), variables, path, depth);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) { Object replacement = variable(matcher.group(1), variables, path, depth); matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? "" : replacement instanceof Map<?, ?> || replacement instanceof List<?> ? JSON.toJson(replacement) : replacement.toString())); }
        return matcher.appendTail(result).toString();
    }
    private static Object variable(String name, Map<String, Object> variables, Set<String> path, int depth) {
        if (name.equals("__uuid()")) return UUID.randomUUID().toString();
        if (name.equals("__timestamp()")) return Instant.now().toEpochMilli();
        if (name.equals("__now()")) return Instant.now().toString();
        if (!path.add(name)) throw new InputProblem("Variable cycle: " + name);
        try {
            Object value;
            if (variables.containsKey(name)) value = variables.get(name);
            else {
                value = variables;
                for (String part : name.split("\\.")) {
                    if (!(value instanceof Map<?, ?> map) || !map.containsKey(part)) throw new InputProblem("Missing runtime variable: " + name);
                    value = map.get(part);
                }
            }
            return resolve(value, variables, path, depth + 1);
        } finally { path.remove(name); }
    }
    private static String url(String base, String target) {
        URI value = URI.create(target);
        if (!value.isAbsolute()) { if (base.isBlank()) throw new InputProblem("Relative navigation requires -Daitest.baseUrl=http://host"); value = URI.create(base).resolve(value); }
        if (!Set.of("http", "https").contains(value.getScheme()) || value.getHost() == null || value.getUserInfo() != null) throw new InputProblem("Navigation requires an HTTP/HTTPS URL without embedded credentials");
        return value.toString();
    }
    private static Map<String, Object> readObject(String filename) throws Exception {
        if (filename == null || filename.isBlank()) return Map.of(); Path path = Path.of(filename);
        if (Files.size(path) > 4 * 1024 * 1024) throw new InputProblem("Runtime JSON file exceeds 4 MB");
        return map(JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Object.class));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?>)) throw new InputProblem("Expected a JSON object"); return (Map<String, Object>) value; }
    private static List<?> list(Object value) { if (!(value instanceof List<?> list)) throw new InputProblem("Expected a JSON array"); return list; }
    private static String string(Map<String, Object> map, String key, String fallback) { return Objects.toString(map.get(key), fallback); }
    private static long number(Map<String, Object> map, String key, long fallback) { return map.get(key) instanceof Number number ? number.longValue() : fallback; }
    private static boolean bool(Map<String, Object> map, String key, boolean fallback) { return map.get(key) instanceof Boolean value ? value : fallback; }
    private static boolean sensitive(String name) { return name.toLowerCase(Locale.ROOT).matches(".*(password|passwd|token|secret|authorization|apikey|api.key|cookie).*"); }
    private static String unquote(String text) { return text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'")) ? text.substring(1, text.length() - 1) : text; }
}
