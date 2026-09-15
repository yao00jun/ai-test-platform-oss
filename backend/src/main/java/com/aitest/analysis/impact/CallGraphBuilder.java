package com.aitest.analysis.impact;

import com.aitest.analysis.diff.GitDiffAnalyzer;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import java.util.*;

/** Conservative source-only dispatch. Edges describe possibilities, not proof that a runtime call occurs. */
@Component
public final class CallGraphBuilder {
    public Map<String, Object> build(Map<String, Object> ast, Runnable checkpoint) {
        List<Map<String, Object>> methods = Values.objects(ast.get("methods"));
        if (methods.size() > 60_000 || Values.objects(ast.get("calls")).size() > 200_000) throw new Problem(422, "CALL_GRAPH_LIMIT", "方法或调用数量超过分析上限，请缩小源码范围");
        Types types = new Types(Values.objects(ast.get("classes")));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>(); Map<String, List<Map<String, Object>>> byName = new HashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>(), edges = new ArrayList<>(), unresolved = new ArrayList<>();
        for (var method : methods) {
            String id = GitDiffAnalyzer.nodeId(method); byId.put(id, method);
            String name = Boolean.TRUE.equals(method.get("constructor")) ? "<init>" : method.get("name").toString();
            byName.computeIfAbsent(name, key -> new ArrayList<>()).add(method);
            Map<String, Object> node = new LinkedHashMap<>(method); node.put("methodId", method.get("id")); node.put("id", id);
            node.remove("variables"); node.remove("variableTypes"); node.remove("annotations"); nodes.add(node);
        }
        int number = 0;
        for (var call : Values.objects(ast.get("calls"))) {
            if (++number % 100 == 0) checkpoint.run();
            String from = call.get("sourcePath") + "::" + call.get("from");
            var caller = byId.get(from);
            if (caller == null) { unresolved.add(unresolved(call, from, "CALLER_NOT_PARSED")); continue; }
            var owner = types.owner(caller);
            Set<String> declared = receivers(call, caller, owner, types);
            boolean special = "super".equals(call.get("receiver")), constructor = "<init>".equals(call.get("name"));
            Set<String> dispatch = new LinkedHashSet<>();
            for (String type : declared) {
                if (constructor) dispatch.add(type);
                else { dispatch.addAll(types.ancestors(type)); if (!special) dispatch.addAll(types.descendants(type)); }
            }
            List<Map<String, Object>> candidates = byName.getOrDefault(call.get("name").toString(), List.of()).stream()
                    .filter(method -> dispatch.contains(types.key(method)))
                    .filter(method -> compatible(call, method, types, owner)).toList();
            if (candidates.isEmpty()) { unresolved.add(unresolved(call, from, declared.isEmpty() ? "RECEIVER_TYPE_UNRESOLVED" : "TARGET_OUTSIDE_SNAPSHOT_OR_UNRESOLVED_OVERLOAD")); continue; }
            if (edges.size() + candidates.size() > 250_000) throw new Problem(422, "CALL_GRAPH_LIMIT", "调用候选边超过分析上限，请缩小源码范围");
            for (var target : candidates) edges.add(Map.of("from", from, "to", GitDiffAnalyzer.nodeId(target), "line", call.get("line"), "sourcePath", call.get("sourcePath"),
                    "receiver", call.get("receiver"), "basis", "METHOD_REFERENCE".equals(call.get("kind")) ? "POTENTIAL_CALLBACK" : declared.contains(types.key(target)) ? "DECLARED_RECEIVER" : "INHERITANCE_DISPATCH",
                    "ambiguous", candidates.size() > 1, "candidateCount", candidates.size()));
        }
        return Map.of("nodes", nodes, "edges", edges, "unresolvedCalls", unresolved, "evidenceLevel", "STATIC", "runtimeDispatchVerified", false);
    }
    private Map<String, Object> unresolved(Map<String, Object> call, String from, String reason) {
        return Map.of("from", from, "name", call.get("name"), "receiver", call.get("receiver"), "sourcePath", call.get("sourcePath"), "line", call.get("line"), "reason", reason);
    }
    private Set<String> receivers(Map<String, Object> call, Map<String, Object> caller, Map<String, Object> owner, Types types) {
        String receiver = Values.text(call, "receiver", "this"); Set<String> result = new LinkedHashSet<>();
        if (receiver.equals("this")) result.add(types.key(caller));
        else if (receiver.equals("super")) for (String parent : strings(owner.get("parents"))) result.addAll(types.resolve(parent, owner));
        else {
            String variable = receiver.startsWith("this.") ? receiver.substring(5) : receiver;
            Map<String, Object> evidence = Values.map(caller.get("variableTypes"));
            List<String> declared = strings(evidence.get(variable));
            if (declared.isEmpty() && !evidence.containsKey(variable)) {
                String old = Values.text(Values.map(caller.get("variables")), variable, "");
                if (!old.isBlank()) declared = List.of(old);
            }
            if (declared.isEmpty()) {
                for (String ancestor : types.ancestors(types.key(caller))) {
                    String field = Values.text(Values.map(types.classes.get(ancestor).get("fields")), variable, "");
                    if (!field.isBlank()) result.addAll(types.resolve(field, owner));
                }
            }
            for (String type : declared) result.addAll(types.resolve(type, owner));
            if (result.isEmpty() && variable.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) result.addAll(types.resolve(variable, owner));
            if (result.isEmpty() && receiver.startsWith("new ")) {
                int end = receiver.indexOf('('); if (end > 4) result.addAll(types.resolve(receiver.substring(4, end), owner));
            }
        }
        if (receiver.equals("this")) for (String imported : strings(owner.get("imports"))) {
            if (imported.startsWith("static ") && (imported.endsWith("." + call.get("name")) || imported.endsWith(".*")))
                result.addAll(types.resolve(imported.substring(7, imported.lastIndexOf('.')), owner));
        }
        return result;
    }
    private boolean compatible(Map<String, Object> call, Map<String, Object> method, Types types, Map<String, Object> callerOwner) {
        int arity = ((Number) call.get("arity")).intValue(); List<Map<String, Object>> parameters = Values.objects(method.get("parameters"));
        if (arity == -1 && "METHOD_REFERENCE".equals(call.get("kind"))) return true;
        boolean varargs = !parameters.isEmpty() && Boolean.TRUE.equals(parameters.getLast().get("varArgs"));
        if ((!varargs && arity != parameters.size()) || (varargs && arity < parameters.size() - 1)) return false;
        List<String> arguments = strings(call.get("argumentTypes"));
        for (int i = 0; i < Math.min(arguments.size(), arity); i++) {
            var parameter = parameters.get(Math.min(i, parameters.size() - 1));
            if (!types.assignable(arguments.get(i), parameter.get("type").toString(), callerOwner, types.owner(method))) return false;
        }
        return true;
    }
    static List<String> strings(Object value) { return value instanceof Collection<?> items ? items.stream().map(Object::toString).toList() : List.of(); }

    static final class Types {
        final Map<String, Map<String, Object>> classes = new LinkedHashMap<>();
        final Map<String, Set<String>> byName = new HashMap<>(), parents = new HashMap<>(), children = new HashMap<>();
        final Map<String, Set<String>> ancestors = new HashMap<>(), descendants = new HashMap<>();
        Types(List<Map<String, Object>> values) {
            for (var type : values) { String key = type.get("sourcePath") + "::" + type.get("id"); classes.put(key, type); byName.computeIfAbsent(type.get("id").toString(), ignored -> new LinkedHashSet<>()).add(key); }
            for (var entry : classes.entrySet()) for (String parent : strings(entry.getValue().get("parents"))) for (String resolved : resolve(parent, entry.getValue())) {
                parents.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(resolved);
                children.computeIfAbsent(resolved, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        String key(Map<String, Object> method) { return method.get("sourcePath") + "::" + method.get("owner"); }
        Map<String, Object> owner(Map<String, Object> method) { return classes.getOrDefault(key(method), Map.of()); }
        Set<String> resolve(String raw, Map<String, Object> context) {
            String name = erase(raw); if (name.equals("?") || name.isBlank() || name.endsWith("[]")) return Set.of();
            if (byName.containsKey(name)) return byName.get(name);
            Set<String> imported = new LinkedHashSet<>();
            for (String item : strings(context.get("imports"))) if (!item.startsWith("static ") && item.endsWith("." + name)) imported.addAll(byName.getOrDefault(item, Set.of()));
            if (!imported.isEmpty()) return imported;
            String pkg = Values.text(context, "packageName", "");
            String local = pkg.isBlank() ? name : pkg + "." + name;
            if (byName.containsKey(local)) return byName.get(local);
            for (String item : strings(context.get("imports"))) if (!item.startsWith("static ") && item.endsWith(".*")) imported.addAll(byName.getOrDefault(item.substring(0, item.length() - 1) + name, Set.of()));
            // Unimported names are not guessed from unrelated packages.
            return imported;
        }
        Set<String> ancestors(String key) { return ancestors.computeIfAbsent(key, ignored -> reachable(key, parents)); }
        Set<String> descendants(String key) { return descendants.computeIfAbsent(key, ignored -> reachable(key, children)); }
        static Set<String> reachable(String start, Map<String, Set<String>> graph) {
            Set<String> visited = new LinkedHashSet<>(); ArrayDeque<String> pending = new ArrayDeque<>(); pending.add(start);
            while (!pending.isEmpty()) { String node = pending.removeFirst(); if (visited.add(node)) pending.addAll(graph.getOrDefault(node, Set.of())); }
            return visited;
        }
        boolean assignable(String fromRaw, String toRaw, Map<String, Object> fromContext, Map<String, Object> toContext) {
            String from = erase(fromRaw), to = erase(toRaw);
            if (from.equals("?") || from.equals("var") || to.equals("?") || to.length() == 1 && Character.isUpperCase(to.charAt(0))) return true;
            Set<String> primitives = Set.of("boolean", "byte", "short", "char", "int", "long", "float", "double");
            if (from.equals("null")) return !primitives.contains(to);
            if (from.equals(to) || to.equals("Object") || to.equals("java.lang.Object")) return true;
            String a = boxed(from), b = boxed(to);
            if (a.equals(b)) return true;
            List<String> numeric = List.of("byte", "short", "int", "long", "float", "double");
            if (a.equals("char")) a = "int";
            if (numeric.contains(a) && numeric.contains(b)) return numeric.indexOf(a) <= numeric.indexOf(b);
            Set<String> source = resolve(from, fromContext), target = resolve(to, toContext);
            if (!source.isEmpty() && !target.isEmpty()) return source.stream().anyMatch(type -> !Collections.disjoint(ancestors(type), target));
            if (primitives.contains(a) || primitives.contains(b)) return false;
            // External generic bounds and library inheritance are unknown, not a proven incompatible overload.
            return true;
        }
        private static String boxed(String value) {
            String simple = value.startsWith("java.lang.") ? value.substring(10) : value;
            return Map.of("Boolean", "boolean", "Byte", "byte", "Short", "short", "Character", "char", "Integer", "int", "Long", "long", "Float", "float", "Double", "double").getOrDefault(simple, simple);
        }
        private static String erase(String input) { return input.replaceAll("<.*>", "").replace("...", "[]").strip(); }
    }
}
