package com.aitest.analysis;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.engine.web.WebLocator;
import com.aitest.execution.Values;
import java.net.URI;
import java.util.*;

/** Checks portable UI instructions against fixed source or explicitly captured page/recording evidence. */
public final class UiSourceValidator {
    public static Map<String, Object> capture(Map<String, Object> observation, List<Asset> recordings, String configuredBase) {
        List<Map<String, Object>> locators = new ArrayList<>(); Set<String> urls = new LinkedHashSet<>();
        addUrl(urls, configuredBase, ""); collect(observation, "", locators, urls);
        Map<String, String> bases = new HashMap<>();
        for (Asset asset : recordings) if (asset.type() == AssetType.UI_SCENARIO) { String base = Values.text(asset.data(), "baseUrl", configuredBase); bases.put(asset.id(), base); addUrl(urls, base, ""); }
        for (Asset asset : recordings) if (asset.type() == AssetType.UI_STEP) {
            for (String field : List.of("selector", "targetSelector")) if (!Values.text(asset.data(), field, "").isBlank()) locators.add(Map.of("selector", asset.data().get(field), "frame", Values.text(asset.data(), "frame", ""), "evidenceLevel", "RECORDING", "assetId", asset.id(), "version", asset.version()));
            if ("navigate".equals(asset.data().get("action"))) addUrl(urls, Values.text(asset.data(), "url", ""), bases.getOrDefault(asset.parentId(), configuredBase));
        }
        return Map.of("locators", locators, "urls", List.copyOf(urls), "configuredBaseUrl", configuredBase);
    }
    public static Map<String, Object> validate(Asset candidate, Asset parent, Map<String, Object> frontend, Map<String, Object> runtime) {
        Set<String> urls = new LinkedHashSet<>(strings(runtime.get("urls")));
        String configured = Values.text(runtime, "configuredBaseUrl", "");
        for (var route : Values.objects(frontend.get("routes"))) {
            String path = Values.text(route, "path", "");
            if (!path.contains(":") && !path.contains("*") && !configured.isBlank()) addUrl(urls, path, configured);
        }
        String base = candidate.type() == AssetType.UI_SCENARIO ? Values.text(candidate.data(), "baseUrl", "") : parent == null ? "" : Values.text(parent.data(), "baseUrl", "");
        if (base.isBlank()) base = configured;
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (candidate.type() == AssetType.UI_SCENARIO) {
            if (!base.isBlank() && !urls.contains(normalizeUrl(base, ""))) throw invalid("场景地址没有已配置页面、录制或源码路由依据");
        } else {
            String frame = Values.text(candidate.data(), "frame", "");
            for (String field : List.of("selector", "targetSelector")) {
                String selector = Values.text(candidate.data(), field, ""); if (selector.isBlank()) continue;
                if (!parseable(selector)) continue;
                var matches = Values.objects(runtime.get("locators")).stream().filter(locator -> frame.equals(Values.text(locator, "frame", "")) && safeSame(selector, Values.text(locator, "selector", ""))).toList();
                if (matches.isEmpty() && frame.isBlank()) matches = Values.objects(frontend.get("selectors")).stream().filter(locator -> safeSame(selector, Values.text(locator, "selector", ""))).toList();
                if (matches.isEmpty()) throw invalid(field + " 没有固定源码、页面或录制依据：" + selector);
                for (var match : matches) { Map<String, Object> value = new LinkedHashMap<>(match); value.put("field", field); evidence.add(value); }
            }
            if (!frame.isBlank() && Values.objects(runtime.get("locators")).stream().noneMatch(locator -> frame.equals(locator.get("frame")))) throw invalid("Frame 没有页面或录制依据");
            String action = Values.text(candidate.data(), "action", "");
            if (Set.of("navigate", "assertUrl").contains(action)) {
                String url = Values.text(candidate.data(), action.equals("navigate") ? "url" : "expected", "");
                if (!urls.contains(normalizeUrl(url, base))) throw invalid("页面地址没有已配置页面、录制或源码路由依据");
            }
        }
        boolean runtimeRequired = evidence.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("requiresRuntimeVerification")));
        return Map.of("locators", evidence, "requiresRuntimeVerification", runtimeRequired, "executionState", base.isBlank() ? "BLOCKED" : "READY", "blockedReasons", base.isBlank() ? List.of("WEB_BASE_URL_REQUIRED") : List.of());
    }
    private static void collect(Map<String, Object> observation, String frame, List<Map<String, Object>> locators, Set<String> urls) {
        addUrl(urls, Values.text(observation, "url", ""), "");
        for (var element : Values.objects(observation.get("elements"))) for (String selector : strings(element.get("selectors"))) locators.add(Map.of("selector", selector, "frame", frame, "evidenceLevel", "OBSERVED"));
        for (var nested : Values.objects(observation.get("frames"))) if (!nested.containsKey("unavailableReason") && !Values.text(nested, "frame", "").isBlank()) collect(nested, nested.get("frame").toString(), locators, urls);
    }
    private static boolean same(String left, String right) { return canonical(left).equals(canonical(right)); }
    private static boolean safeSame(String left, String right) { try { return same(left, right); } catch (RuntimeException invalid) { return false; } }
    private static boolean parseable(String selector) { try { canonical(selector); return true; } catch (RuntimeException invalid) { return false; } }
    private static Object canonical(String value) {
        if (value.isBlank()) return "";
        var id = java.util.regex.Pattern.compile("^#([A-Za-z_][A-Za-z0-9_-]*)$").matcher(value);
        if (id.matches()) return "id:" + id.group(1);
        var attribute = java.util.regex.Pattern.compile("^\\[(id|data-testid|placeholder|aria-label)=['\"]([^'\"]+)['\"]]$").matcher(value);
        if (attribute.matches()) return switch (attribute.group(1)) { case "id" -> "id:" + attribute.group(2); case "data-testid" -> new WebLocator("testId", attribute.group(2), null); case "placeholder" -> new WebLocator("placeholder", attribute.group(2), null); default -> new WebLocator("label", attribute.group(2), null); };
        return WebLocator.parse(value);
    }
    private static void addUrl(Set<String> urls, String value, String base) { String url = normalizeUrl(value, base); if (url != null) urls.add(url); }
    private static String normalizeUrl(String value, String base) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()) { if (base == null || base.isBlank()) return null; uri = URI.create(base).resolve(uri); }
            if (!Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT)) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static List<String> strings(Object value) { return value instanceof Collection<?> list ? list.stream().map(Object::toString).toList() : List.of(); }
    private static Problem invalid(String message) { return new Problem(422, "SOURCE_UI_EVIDENCE_INVALID", message); }
    private UiSourceValidator() { }
}
