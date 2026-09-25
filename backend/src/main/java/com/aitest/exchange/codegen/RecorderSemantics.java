package com.aitest.exchange.codegen;

import com.aitest.asset.AssetType;
import com.aitest.exchange.*;
import java.util.*;
import static com.aitest.exchange.codegen.RecorderSyntax.*;

/** Converts a deliberately closed recorder vocabulary to the same editable UI-step assets. */
final class RecorderSemantics {
    private record Located(String page, String selector, String frame, boolean exact) { }
    private record PageFrame(String page, String frame) { }
    private record PendingPopup(String page, int position, int line) { }
    private static final class UnsupportedSyntax extends RuntimeException { UnsupportedSyntax(String text) { super(text); } }
    private static final class NonFatalSyntax extends RuntimeException { NonFatalSyntax(String text) { super(text); } }
    private final String source;
    private final List<ExchangeIssue> errors;
    private final List<ExchangeIssue> warnings;
    private final Map<String, String> pages = new LinkedHashMap<>(Map.of("page", "main"));
    private final Map<String, String> resources = new LinkedHashMap<>();
    private final Map<String, Expr> bindings = new LinkedHashMap<>();
    private final Map<String, Located> locators = new LinkedHashMap<>();
    private final Set<String> declared = new HashSet<>();
    private final Map<String, PendingPopup> pending = new LinkedHashMap<>();
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final List<Integer> lines = new ArrayList<>();
    private final Map<String, Object> scenario = new LinkedHashMap<>();
    private String name;
    private int wrappers;
    private boolean pageCreated;

    RecorderSemantics(String source, List<ExchangeIssue> errors, List<ExchangeIssue> warnings) {
        this.source = source; this.errors = errors; this.warnings = warnings; this.name = ExchangeParserRegistry.stripExtension(source);
        resources.put("chromium", "CHROMIUM"); resources.put("firefox", "FIREFOX"); resources.put("webkit", "WEBKIT");
    }
    ParsedExchange convert(List<Statement> statements, AssetType type, String format) {
        consume(statements);
        pending.values().forEach(popup -> issue(popup.line(), "popup", "弹窗 Promise 没有被等待，或未能定位唯一的触发步骤"));
        if (steps.isEmpty()) issue(1, "$script", "没有找到可导入的 UI 操作");
        List<ExchangeNode> nodes = new ArrayList<>();
        String parent = type == AssetType.UI_SCENARIO ? "scenario" : null;
        if (parent != null) nodes.add(new ExchangeNode(parent, AssetType.UI_SCENARIO, null, name, 0, scenario, Map.of()));
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> data = steps.get(index);
            nodes.add(new ExchangeNode("step_" + (index + 1), AssetType.UI_STEP, parent, "L" + lines.get(index) + " · " + data.get("action"), index, data, Map.of()));
        }
        return new ParsedExchange(new ExchangeBundle(ExchangeBundle.VERSION, Map.of("sourceFormat", format, "sourceLines", lines), nodes, Map.of(), warnings), errors);
    }
    private void consume(List<Statement> statements) {
        for (Statement statement : statements) {
            try { statement(statement); }
            catch (NonFatalSyntax warning) { warnings.add(new ExchangeIssue(source, statement.line(), "$script", warning.getMessage())); }
            catch (UnsupportedSyntax failure) { issue(statement.line(), "$script", failure.getMessage()); }
            catch (IllegalArgumentException failure) { issue(statement.line(), "$script", "录制参数的类型或取值无效"); }
        }
    }
    private void statement(Statement statement) {
        Expr value = statement.expression(); String variable = statement.variable();
        if (value instanceof Unsupported unsupported) throw unsupported(unsupported.reason());
        if (value instanceof Call call && call.receiver() == null && call.method().equals("test")) {
            if (++wrappers > 1 || call.arguments().size() != 2 || !(call.arguments().get(1) instanceof Lambda lambda)) throw unsupported("每次只支持一个 Playwright test 录制函数");
            name = text(call.arguments().getFirst()); consume(lambda.body()); return;
        }
        if (value instanceof Call call && call.receiver() instanceof Lambda lambda && call.method().equals("<invoke>") && call.arguments().isEmpty()) {
            if (++wrappers > 1) throw unsupported("每次只支持一个录制函数"); consume(lambda.body()); return;
        }
        if (variable != null) {
            if (!declared.add(variable)) throw unsupported("重复变量声明可能改变录制语义，请使用唯一名称");
            if (pending.containsKey(variable)) throw unsupported("录制变量不能覆盖未完成的弹窗 Promise");
            if (value instanceof Name awaited && pending.containsKey(awaited.value())) {
                PendingPopup popup = pending.remove(awaited.value());
                if (steps.size() != popup.position() + 1 || !"click".equals(steps.getLast().get("action")) || !popup.page().equals(steps.getLast().getOrDefault("pageAlias", "main"))) throw unsupported("弹窗 Promise 需要紧接一个同页面的 click，再等待该 Promise");
                if (pages.containsKey(variable)) throw unsupported("弹窗页面别名不能覆盖已有页面");
                var step = steps.getLast(); step.put("action", "popup"); step.put("saveAs", variable); pages.put(variable, variable); return;
            }
            if (value instanceof Call call && call.method().equals("waitForEvent")) {
                if (!pending.isEmpty() || call.arguments().size() != 1 || !text(call.arguments().getFirst()).equals("popup")) throw unsupported("只支持串行的 popup Promise 录制模式");
                pending.put(variable, new PendingPopup(pageFrame(call.receiver()).page(), steps.size(), statement.line())); return;
            }
            if (value instanceof Call call && call.method().equals("waitForPopup")) {
                Map<String, Object> step = popup(call, variable); add(step, statement.line()); pages.put(variable, variable); return;
            }
            if (setup(variable, value)) return;
            if (pages.containsKey(variable) || resources.containsKey(variable)) throw unsupported("不能用静态值或定位器覆盖页面/浏览器变量");
            if (value instanceof Literal) { bindings.put(variable, value); return; }
            if (value instanceof Name alias && bindings.containsKey(alias.value())) { bindings.put(variable, bindings.get(alias.value())); return; }
            if (isLocator(value) || value instanceof Name alias && locators.containsKey(alias.value())) { locators.put(variable, locate(value)); return; }
            throw unsupported("变量赋值不是支持的 Playwright 初始化、静态值或定位器");
        }
        if (value instanceof Call call && call.method().equals("close") && call.receiver() instanceof Name receiver && resources.containsKey(receiver.value()) && call.arguments().isEmpty()) return;
        if (value instanceof Call call && call.receiver() instanceof Name receiver && receiver.value().equals("test") && call.method().equals("use")) {
            if (call.arguments().size() != 1) throw unsupported("test.use 需要一个静态选项对象"); contextOptions(options(call.arguments().getFirst())); return;
        }
        add(action(value), statement.line());
    }
    private boolean setup(String variable, Expr expression) {
        if (!(expression instanceof Call call)) return false;
        if (call.receiver() == null && call.method().equals("require") && call.arguments().size() == 1 && text(call.arguments().getFirst()).equals("playwright")) {
            if (!variable.matches("\\{(?:chromium|firefox|webkit)(?:,(?:chromium|firefox|webkit))*}")) throw unsupported("只支持 playwright 的浏览器类型解构"); return true;
        }
        String kind = resource(expression);
        if (kind == null) return false;
        switch (kind) {
            case "BROWSER" -> {
                Call launch = (Call) expression;
                if (!(launch.receiver() instanceof Name) && !(launch.receiver() instanceof Call browserType && browserType.receiver() instanceof Name && browserType.arguments().isEmpty())) throw unsupported("浏览器初始化需要显式的 Playwright 变量，不能丢弃嵌套初始化选项");
                String browser = resource(launch.receiver()); scenario.put("browser", browser);
                Map<String, Object> options = argumentsOptions(launch, 0); allowed(options, "headless", "timeout");
                if (options.containsKey("headless")) scenario.put("headless", bool(options.get("headless")));
                if (options.containsKey("timeout")) throw unsupported("浏览器启动超时不能转换为步骤超时，请在平台中配置");
            }
            case "CONTEXT" -> {
                if (!(call.receiver() instanceof Name)) throw unsupported("BrowserContext 初始化需要已声明的浏览器，不能丢弃嵌套选项");
                contextOptions(argumentsOptions(call, 0));
            }
            case "PAGE" -> {
                if (!(call.receiver() instanceof Name)) throw unsupported("Page 初始化需要已声明的 BrowserContext，不能丢弃嵌套选项");
                if (pageCreated) throw unsupported("额外 newPage 不属于单场景录制；弹窗请使用 waitForPopup");
                if (!call.arguments().isEmpty()) throw unsupported("newPage 参数不能转换");
                pageCreated = true; pages.put(variable, "main"); return true;
            }
            case "PLAYWRIGHT" -> { if (!call.arguments().isEmpty()) throw unsupported("Playwright.create 参数不能转换"); }
            default -> { }
        }
        resources.put(variable, kind); return true;
    }
    private String resource(Expr expression) {
        if (expression instanceof Name name) return resources.get(name.value());
        if (!(expression instanceof Call call)) return null;
        if (call.receiver() instanceof Name name && name.value().equals("Playwright") && call.method().equals("create")) return "PLAYWRIGHT";
        String owner = resource(call.receiver());
        if ("PLAYWRIGHT".equals(owner) && Set.of("chromium", "firefox", "webkit").contains(call.method()) && call.arguments().isEmpty()) return call.method().toUpperCase(Locale.ROOT);
        if (Set.of("CHROMIUM", "FIREFOX", "WEBKIT").contains(owner == null ? "" : owner) && call.method().equals("launch")) return "BROWSER";
        if ("BROWSER".equals(owner) && call.method().equals("newContext")) return "CONTEXT";
        if ("CONTEXT".equals(owner) && call.method().equals("newPage")) return "PAGE";
        return null;
    }
    private void contextOptions(Map<String, Object> options) {
        allowed(options, "viewport", "viewportSize", "ignoreHTTPSErrors", "baseURL", "baseUrl", "headless");
        Object viewport = options.getOrDefault("viewport", options.get("viewportSize"));
        if (viewport != null) {
            if (!(viewport instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("width", "height"))) throw unsupported("viewport 需要 width 和 height");
            scenario.put("viewportWidth", integer(map.get("width"))); scenario.put("viewportHeight", integer(map.get("height")));
        }
        if (options.containsKey("ignoreHTTPSErrors")) scenario.put("ignoreHttpsErrors", bool(options.get("ignoreHTTPSErrors")));
        if (options.containsKey("headless")) scenario.put("headless", bool(options.get("headless")));
        if (options.containsKey("baseURL") || options.containsKey("baseUrl")) scenario.put("baseUrl", options.getOrDefault("baseURL", options.get("baseUrl")));
    }
    private Map<String, Object> action(Expr expression) {
        if (!(expression instanceof Call call)) throw unsupported("仅支持显式的 Playwright 操作语句");
        if (call.receiver() instanceof Call assertion && assertion.receiver() == null && Set.of("assertThat", "expect").contains(assertion.method())) return assertion(call, assertion);
        if (Set.of("navigate", "goto").contains(call.method())) {
            Map<String, Object> result = pageStep("navigate", call.receiver());
            requireArguments(call, 1); result.put("url", text(call.arguments().getFirst()));
            applyTimeout(result, argumentsOptions(call, 1)); return result;
        }
        if (call.method().equals("close")) { requireExactArguments(call, 0); return pageStep("closePage", call.receiver()); }
        if (call.method().equals("screenshot")) {
            requireExactArguments(call, 0); return pageStep("screenshot", call.receiver());
        }
        String action = switch (call.method()) {
            case "click", "dblclick", "fill", "press", "check", "uncheck", "hover", "selectOption" -> call.method();
            case "dragTo" -> "dragAndDrop";
            case "waitFor" -> "waitFor";
            default -> throw unsupported("此 Playwright 操作或断言尚不能无损转换；请在预览行定位后手工编排");
        };
        Located located = locate(call.receiver()); Map<String, Object> result = locatedStep(action, located);
        int arguments = Set.of("fill", "press", "selectOption", "dragAndDrop").contains(action) ? 1 : 0;
        requireArguments(call, arguments);
        if (arguments == 1) {
            if (action.equals("dragAndDrop")) {
                Located target = locate(call.arguments().getFirst());
                if (!target.page().equals(located.page()) || !target.frame().equals(located.frame()) || target.exact() != located.exact()) throw unsupported("拖放的两端必须位于同一页面/框架且匹配方式一致");
                result.put("targetSelector", target.selector());
            } else result.put("value", text(call.arguments().getFirst()));
        }
        Map<String, Object> options = argumentsOptions(call, arguments);
        if (action.equals("waitFor")) {
            allowed(options, "timeout", "state");
            if (options.containsKey("state")) result.put("waitState", options.remove("state").toString().toLowerCase(Locale.ROOT));
        }
        applyTimeout(result, options); return result;
    }
    private Map<String, Object> assertion(Call call, Call assertion) {
        requireExactArguments(assertion, 1);
        String action = switch (call.method()) {
            case "hasText", "toHaveText" -> "assertText";
            case "isVisible", "toBeVisible" -> "assertVisible";
            case "isHidden", "toBeHidden" -> "assertHidden";
            case "hasValue", "toHaveValue" -> "assertValue";
            case "hasCount", "toHaveCount" -> "assertCount";
            case "hasURL", "toHaveURL" -> "assertUrl";
            default -> throw unsupported("此断言不在可转换集合中；不能静默替换断言含义");
        };
        Map<String, Object> result = action.equals("assertUrl") ? pageStep(action, assertion.arguments().getFirst()) : locatedStep(action, locate(assertion.arguments().getFirst()));
        int arguments = Set.of("assertVisible", "assertHidden").contains(action) ? 0 : 1;
        requireArguments(call, arguments);
        if (arguments == 1) result.put("expected", action.equals("assertCount") ? Long.toString(integer(literal(call.arguments().getFirst()))) : text(call.arguments().getFirst()));
        applyTimeout(result, argumentsOptions(call, arguments)); return result;
    }
    private Map<String, Object> popup(Call call, String alias) {
        if (pages.containsKey(alias) || !alias.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw unsupported("弹窗别名必须唯一且是普通标识符");
        if (call.arguments().isEmpty() || call.arguments().size() > 2 || !(call.arguments().getLast() instanceof Lambda lambda) || lambda.body().size() != 1 || lambda.body().getFirst().variable() != null) throw unsupported("waitForPopup 回调需要且只能包含一个 click");
        Map<String, Object> result = action(lambda.body().getFirst().expression());
        if (!result.get("action").equals("click") || !result.getOrDefault("pageAlias", "main").equals(pageFrame(call.receiver()).page())) throw unsupported("waitForPopup 必须由同页面 click 触发");
        if (call.arguments().size() == 2) applyTimeout(result, options(call.arguments().getFirst()));
        result.put("action", "popup"); result.put("saveAs", alias); return result;
    }
    private Located locate(Expr expression) {
        if (expression instanceof Name name && locators.containsKey(name.value())) return locators.get(name.value());
        if (!(expression instanceof Call call)) throw unsupported("定位器必须为支持的 Playwright 定位调用");
        if (Set.of("first", "last", "nth").contains(call.method()) && call.receiver() instanceof Call receiver && isLocator(receiver))
            throw new NonFatalSyntax("无法无损转换 first/last/nth 定位器；已跳过此行操作，其他录制步骤仍可导入");
        PageFrame root = pageFrame(call.receiver()); requireArguments(call, 1);
        Map<String, Object> options = argumentsOptions(call, 1);
        String kind = switch (call.method()) {
            case "getByRole" -> "role"; case "getByLabel" -> "label"; case "getByText" -> "text";
            case "getByPlaceholder" -> "placeholder"; case "getByTestId" -> "testId"; case "getByTitle" -> "title";
            case "getByAltText" -> "alt"; case "locator" -> "selector";
            default -> throw unsupported("嵌套/筛选/动态定位器尚不能无损转换；请保留原脚本并手工编排");
        };
        allowed(options, kind.equals("role") ? Set.of("name", "exact") : Set.of("exact"));
        if (Set.of("selector", "testId").contains(kind) && !options.isEmpty()) throw unsupported("此定位器不支持匹配选项");
        boolean exact = options.containsKey("exact") && bool(options.get("exact"));
        String value = text(call.arguments().getFirst());
        if (kind.equals("role")) value = value.toLowerCase(Locale.ROOT).replace("_", "");
        String selector = kind.equals("selector") ? value : kind + "=" + unambiguous(value);
        if (kind.equals("role") && options.containsKey("name")) {
            if (!(options.get("name") instanceof String text)) throw unsupported("角色名称必须为静态文字");
            selector += "[name=" + unambiguous(text) + "]";
        }
        return new Located(root.page(), selector, root.frame(), exact);
    }
    private PageFrame pageFrame(Expr expression) {
        if (expression instanceof Name name && pages.containsKey(name.value())) return new PageFrame(pages.get(name.value()), "");
        if (expression instanceof Call call && call.method().equals("frameLocator")) {
            requireExactArguments(call, 1); PageFrame root = pageFrame(call.receiver());
            if (!root.frame().isEmpty()) throw unsupported("多层 iframe 需要手工编排，不能折叠为单层定位器");
            return new PageFrame(root.page(), text(call.arguments().getFirst()));
        }
        if (expression instanceof Call call && call.method().equals("contentFrame")) {
            requireExactArguments(call, 0); Located frame = locate(call.receiver());
            if (!(call.receiver() instanceof Call locator) || !locator.method().equals("locator") || !frame.frame().isEmpty()) throw unsupported("contentFrame 需要一个直接的 iframe CSS/XPath 定位器");
            return new PageFrame(frame.page(), frame.selector());
        }
        throw unsupported("页面变量没有定义，或不支持此页面/框架表达式");
    }
    private boolean isLocator(Expr value) { if (!(value instanceof Call call)) return false; return Set.of("locator", "getByRole", "getByLabel", "getByText", "getByPlaceholder", "getByTestId", "getByTitle", "getByAltText").contains(call.method()); }
    private Map<String, Object> pageStep(String action, Expr receiver) { PageFrame page = pageFrame(receiver); if (!page.frame().isEmpty()) throw unsupported("此操作需要页面而不是 iframe"); return new LinkedHashMap<>(Map.of("action", action, "pageAlias", page.page())); }
    private Map<String, Object> locatedStep(String action, Located located) { return new LinkedHashMap<>(Map.of("action", action, "selector", located.selector(), "frame", located.frame(), "pageAlias", located.page(), "exactMatch", located.exact())); }
    private void add(Map<String, Object> step, int row) { if (steps.size() >= 10_000) throw unsupported("每个录制文件最多 10000 个步骤"); steps.add(step); lines.add(row); }
    private Map<String, Object> argumentsOptions(Call call, int positional) { if (call.arguments().size() == positional) return new LinkedHashMap<>(); if (call.arguments().size() != positional + 1) throw unsupported("调用参数数量不支持"); return options(call.arguments().get(positional)); }
    private void requireArguments(Call call, int minimum) { if (call.arguments().size() < minimum) throw unsupported("调用缺少必需参数"); }
    private void requireExactArguments(Call call, int count) { if (call.arguments().size() != count) throw unsupported("调用参数数量不支持"); }
    private void applyTimeout(Map<String, Object> step, Map<String, Object> options) {
        allowed(options, "timeout");
        if (options.containsKey("timeout")) {
            long timeout = integer(options.get("timeout"));
            if (timeout < 100 || timeout > 120000) throw unsupported("步骤超时需要为 100–120000 毫秒；无限超时不能直接导入");
            step.put("timeoutMs", timeout);
        }
    }
    private Map<String, Object> options(Expr expression) {
        if (expression instanceof ObjectValue object) {
            Map<String, Object> result = new LinkedHashMap<>(); object.fields().forEach((key, value) -> result.put(key, literal(value))); return result;
        }
        if (expression instanceof Construct constructor && constructor.type().endsWith("Options") && constructor.arguments().isEmpty()) return new LinkedHashMap<>();
        if (expression instanceof Call call && call.method().startsWith("set") && call.method().length() > 3) {
            Map<String, Object> result = options(call.receiver()); String key = Character.toLowerCase(call.method().charAt(3)) + call.method().substring(4);
            if (key.equals("viewportSize") && call.arguments().size() == 2) result.put(key, Map.of("width", literal(call.arguments().getFirst()), "height", literal(call.arguments().get(1))));
            else { requireExactArguments(call, 1); result.put(key, literal(call.arguments().getFirst())); }
            return result;
        }
        throw unsupported("选项必须为静态对象或标准 Options 链");
    }
    private Object literal(Expr expression) {
        if (expression instanceof Literal literal) return literal.value();
        if (expression instanceof Name name && bindings.get(name.value()) instanceof Literal value) return value.value();
        if (expression instanceof Member member && member.receiver() instanceof Name owner && Set.of("AriaRole", "WaitForSelectorState").contains(owner.value())) return member.name();
        if (expression instanceof ObjectValue) return options(expression);
        throw unsupported("只接受静态文字、数字、布尔值与标准枚举；不会执行上传的表达式");
    }
    private String text(Expr expression) { Object value = literal(expression); if (!(value instanceof String text)) throw unsupported("此参数需要静态文字"); return text; }
    private static String unambiguous(String text) {
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"") || text.startsWith("'") && text.endsWith("'"))) throw unsupported("定位文本包含成对外层引号，请改用明确的 CSS 定位器"); return text;
    }
    private static boolean bool(Object value) { if (!(value instanceof Boolean result)) throw unsupported("此选项需要布尔值"); return result; }
    private static long integer(Object value) { if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) throw unsupported("此选项需要整数"); return number.longValue(); }
    private static void allowed(Map<String, Object> options, String... keys) { allowed(options, Set.of(keys)); }
    private static void allowed(Map<String, Object> options, Set<String> keys) { if (!keys.containsAll(options.keySet())) throw unsupported("存在不能无损转换的 Playwright 选项，请在出错行手工调整"); }
    private static UnsupportedSyntax unsupported(String text) { return new UnsupportedSyntax(text); }
    private void issue(int row, String field, String text) { errors.add(new ExchangeIssue(source, row, field, text)); }
}
