package com.aitest.execution;

import com.aitest.asset.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

/** Static data-flow analysis; it performs no HTTP, SQL, browser or model calls. */
@Service
public class ScenarioDependencyValidator {
    private static final Object UNKNOWN = new Object();
    private final SnapshotService snapshots;
    private final VariableResolver variables;
    public ScenarioDependencyValidator(SnapshotService snapshots, VariableResolver variables) { this.snapshots = snapshots; this.variables = variables; }

    @Transactional
    public Map<String, Object> validate(String project, String assetId, String environmentId, String datasetId) {
        return validate(snapshots.capture(project, assetId, environmentId, datasetId));
    }
    public Map<String, Object> validate(RunDefinition definition) {
        List<Map<String, Object>> errors = new ArrayList<>(ExecutionConfiguration.errors(definition)); Set<String> available = new TreeSet<>();
        for (RunDefinition.Item item : definition.items()) {
            if (item.mode().equals("MANUAL") || item.type() == AssetType.FUNCTIONAL_CASE) continue;
            Asset root = definition.graph().get(item.assetId());
            Scope scope = new Scope(definition.graph().environmentVariables(), produced(root, definition.graph())); scope.values.putAll(item.variables());
            // Global login publishes ${token} before every HTTP step (HttpAssetExecutor); it is not an environment variable.
            if (globalAuth(definition.graph())) scope.values.putIfAbsent("token", UNKNOWN);
            Object overrides = scope.values.remove("__planOverrides");
            check(root, "plan.variables", overrides, scope, item.rowIndex(), errors);
            scope.bind(Values.map(overrides));
            walk(root, definition.graph(), scope, item.rowIndex(), errors);
            available.addAll(scope.values.keySet());
            if (errors.size() >= 1000) break;
        }
        return Map.of("valid", errors.isEmpty(), "errors", errors, "availableVariables", available, "checkedItems", definition.items().size(), "errorsTruncated", errors.size() >= 1000);
    }
    private void walk(Asset asset, AssetGraph graph, Scope scope, Integer row, List<Map<String, Object>> errors) {
        if (asset.type() == AssetType.SCENARIO) {
            check(asset, "variables", asset.data().get("variables"), scope, row, errors); scope.bind(Values.map(asset.data().get("variables")));
            for (Asset step : graph.children(asset.id())) {
                if (step.type() == AssetType.SQL_VALIDATION) { walk(step, graph, scope, row, errors); continue; }
                if (step.type() != AssetType.SCENARIO_STEP) continue;
                Scope local = new Scope(scope.values, scope.produced);
                check(step, "variables", step.data().get("variables"), local, row, errors); local.bind(Values.map(step.data().get("variables")));
                if (!Values.text(step.data(), "stepType", "HTTP").equals("WAIT")) {
                    walk(graph.get(Values.text(step.data(), "targetId", "")), graph, local, row, errors);
                    for (Asset sql : graph.children(step.id())) if (sql.type() == AssetType.SQL_VALIDATION) walk(sql, graph, local, row, errors);
                }
                for (String exported : local.published) scope.publish(exported, local.values.get(exported));
            }
            return;
        }
        if (asset.type() == AssetType.UI_SCENARIO) {
            check(asset, "baseUrl", asset.data().get("baseUrl"), scope, row, errors);
            Set<String> pages = new HashSet<>(List.of("main"));
            for (Asset step : graph.children(asset.id())) if (step.type() == AssetType.UI_STEP) {
                check(step, "data", ExecutionConfiguration.data(step), scope, row, errors);
                String alias = Values.text(step.data(), "pageAlias", ""); if (alias.isBlank()) alias = "main";
                if (!alias.contains("${") && !pages.contains(alias)) issue(step, "pageAlias", alias, "页面别名尚未创建或已关闭", row, errors);
                String action = Values.text(step.data(), "action", "");
                if (action.equals("popup")) pages.add(Values.text(step.data(), "saveAs", ""));
                if (action.equals("closePage")) pages.remove(alias);
                ExecutionAssetPolicy.exported(step).forEach(name -> scope.publish(name, UNKNOWN));
            }
            return;
        }
        if (asset.type() == AssetType.SQL_VALIDATION) {
            Scope sql = new Scope(scope.values, scope.produced);
            check(asset, "parameters", asset.data().get("parameters"), scope, row, errors); sql.bind(Values.map(asset.data().get("parameters")));
            for (String field : List.of("sql", "assertions")) check(asset, field, asset.data().get(field), sql, row, errors);
        } else {
            check(asset, "data", ExecutionConfiguration.data(asset), scope, row, errors);
            if (graph.environment() != null) check(asset, "environment.headers", graph.environment().data().get("headers"), scope, row, errors);
        }
        ExecutionAssetPolicy.exported(asset).forEach(name -> scope.publish(name, UNKNOWN));
        for (Asset sql : graph.children(asset.id())) if (sql.type() == AssetType.SQL_VALIDATION) walk(sql, graph, scope, row, errors);
    }
    private void check(Asset asset, String field, Object input, Scope scope, Integer row, List<Map<String, Object>> errors) {
        for (String reference : variables.references(input)) {
            try { scope.reference(reference, new LinkedHashSet<>()); }
            catch (DependencyFailure failure) {
                // A variable no step of this item produces has to come from outside: a gap to fill before running, not a broken order.
                if (failure.missing && !scope.produced.contains(failure.variable.split("\\.", 2)[0])) {
                    if (errors.size() >= 1000) continue;
                    Map<String, Object> gap = new LinkedHashMap<>(); gap.put("assetId", asset.id()); gap.put("name", asset.name()); gap.put("field", field); gap.put("variable", failure.variable); gap.put("code", "RUNTIME_VARIABLE_REQUIRED");
                    gap.put("message", failure.variable.equals("token") ? "运行前需要为环境启用全局鉴权（登录后提供 ${token}），或在环境变量中提供 token" : "运行前需要在环境变量、数据集或计划变量中提供 ${" + failure.variable + "}");
                    if (row != null) gap.put("rowIndex", row + 1); errors.add(gap);
                } else issue(asset, field, failure.variable, failure.getMessage(), row, errors);
            }
        }
    }
    /** Everything any part of this run item can publish: extractors, SQL exports, UI extracts and declared variables. */
    private static Set<String> produced(Asset root, AssetGraph graph) {
        Set<String> names = new HashSet<>(); Set<String> seen = new HashSet<>(); ArrayDeque<Asset> pending = new ArrayDeque<>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            Asset asset = pending.removeFirst(); if (asset == null || !seen.add(asset.id())) continue;
            names.addAll(ExecutionAssetPolicy.exported(asset));
            if (Set.of(AssetType.SCENARIO, AssetType.SCENARIO_STEP).contains(asset.type())) names.addAll(Values.map(asset.data().get("variables")).keySet());
            pending.addAll(graph.children(asset.id()));
            if (asset.type() == AssetType.SCENARIO_STEP) {
                String target = Values.text(asset.data(), "targetId", "");
                if (!target.isBlank() && graph.assets().containsKey(target)) pending.add(graph.get(target));
            }
        }
        return names;
    }
    private static boolean globalAuth(AssetGraph graph) {
        Asset environment = graph.environment();
        return environment != null && graph.assets().values().stream().anyMatch(item -> item.type() == AssetType.AUTH_CONFIG && Values.bool(item.data(), "enabled", true) && environment.id().equals(item.data().get("environmentId")));
    }
    private void issue(Asset asset, String field, String variable, String message, Integer row, List<Map<String, Object>> errors) {
        if (errors.size() >= 1000) return;
        Map<String, Object> error = new LinkedHashMap<>(); error.put("assetId", asset.id()); error.put("name", asset.name()); error.put("field", field); error.put("variable", variable); error.put("message", message);
        if (row != null) error.put("rowIndex", row + 1); errors.add(error);
    }
    private static final class DependencyFailure extends RuntimeException {
        final String variable; final boolean missing;
        DependencyFailure(String variable, String message) { this(variable, message, false); }
        DependencyFailure(String variable, String message, boolean missing) { super(message); this.variable = variable; this.missing = missing; }
    }
    private static final class Scope {
        private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)}");
        final Map<String, Object> values = new LinkedHashMap<>();
        final Set<String> published = new LinkedHashSet<>();
        final Set<String> produced;
        Scope(Map<String, Object> initial, Set<String> produced) { values.putAll(initial); this.produced = produced; }
        void publish(String name, Object value) { values.put(name, value); published.add(name); }
        void bind(Map<String, Object> additions) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            additions.forEach((key, value) -> { try { resolved.put(key, resolve(value, new LinkedHashSet<>())); } catch (DependencyFailure invalid) { resolved.put(key, UNKNOWN); } });
            values.putAll(resolved);
        }
        Object reference(String name, Set<String> path) {
            if (Set.of("__uuid()", "__timestamp()", "__now()").contains(name)) return UNKNOWN;
            if (!path.add(name) || path.size() > 32) throw new DependencyFailure(name, "变量循环引用或嵌套超过 32 层：" + name);
            try {
                Object value;
                if (values.containsKey(name)) value = values.get(name);
                else {
                    value = values;
                    for (String part : name.split("\\.")) {
                        if (value == UNKNOWN) return UNKNOWN;
                        if (!(value instanceof Map<?, ?> map) || !map.containsKey(part)) throw new DependencyFailure(name, "此步骤之前没有可用变量：" + name, true);
                        value = map.get(part);
                    }
                }
                return resolve(value, path);
            } finally { path.remove(name); }
        }
        Object resolve(Object value, Set<String> path) {
            if (value instanceof Map<?, ?> map) { Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> result.put(key.toString(), resolve(item, path))); return result; }
            if (value instanceof List<?> list) return list.stream().map(item -> resolve(item, path)).toList();
            if (!(value instanceof String text)) return value;
            var matcher = VARIABLE.matcher(text);
            if (matcher.matches()) return reference(matcher.group(1), path);
            StringBuilder result = new StringBuilder(); boolean unknown = false;
            while (matcher.find()) {
                Object item = reference(matcher.group(1), path); unknown |= item == UNKNOWN;
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(Objects.toString(item, "")));
            }
            return unknown ? UNKNOWN : matcher.appendTail(result).toString();
        }
    }
}
