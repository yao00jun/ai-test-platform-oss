package com.aitest.analysis.impact;

import com.aitest.analysis.diff.GitDiffAnalyzer;
import com.aitest.execution.Values;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public final class BlastRadiusService {
    private final CallGraphBuilder graphs;
    public BlastRadiusService(CallGraphBuilder graphs) { this.graphs = graphs; }
    public Map<String, Object> analyze(Map<String, Object> diff, Map<String, Object> oldAst, Map<String, Object> newAst, Runnable checkpoint) {
        Map<String, Object> before = graphs.build(oldAst, checkpoint), after = graphs.build(newAst, checkpoint);
        List<Map<String, Object>> endpoints = new ArrayList<>(), tables = new ArrayList<>(), diagnostics = new ArrayList<>();
        collect("BASELINE", "old", "beforeNodeId", diff, oldAst, before, endpoints, tables, diagnostics, checkpoint);
        collect("HEAD", "new", "afterNodeId", diff, newAst, after, endpoints, tables, diagnostics, checkpoint);
        var result = new LinkedHashMap<String, Object>(diff); result.put("baselineGraph", before); result.put("headGraph", after);
        result.put("affectedEndpoints", endpoints); result.put("affectedTables", tables); result.put("diagnostics", diagnostics);
        result.put("completeRuntimeCoverage", false); result.put("evidenceLevel", "STATIC");
        return result;
    }
    private void collect(String version, String side, String changedKey, Map<String, Object> diff, Map<String, Object> ast, Map<String, Object> graph,
                         List<Map<String, Object>> endpoints, List<Map<String, Object>> tables, List<Map<String, Object>> diagnostics, Runnable checkpoint) {
        Set<String> seeds = new LinkedHashSet<>();
        for (var method : Values.objects(diff.get("changedMethods"))) { String id = Values.text(method, changedKey, ""); if (!id.isBlank()) seeds.add(id); }
        for (var change : Values.objects(diff.get("classChanges"))) if (side.equals(change.get("side"))) {
            Values.objects(ast.get("methods")).stream().filter(m -> change.get("sourcePath").equals(m.get("sourcePath")) && change.get("owner").equals(m.get("owner"))).map(GitDiffAnalyzer::nodeId).forEach(seeds::add);
        }
        Set<String> changedFiles = new HashSet<>();
        Values.objects(diff.get("files")).forEach(file -> changedFiles.add(Values.text(file, side + "Path", "")));
        for (var statement : Values.objects(ast.get("mapperStatements"))) if (Values.text(statement, "sourcePath", "").endsWith(".xml") && changedFiles.contains(statement.get("sourcePath"))) {
            Values.objects(ast.get("methods")).stream().filter(m -> statement.get("namespace").equals(m.get("owner")) && statement.get("id").equals(m.get("name"))).map(GitDiffAnalyzer::nodeId).forEach(seeds::add);
        }
        Map<String, Set<String>> reverse = new HashMap<>(), forward = new HashMap<>();
        for (var edge : Values.objects(graph.get("edges"))) {
            String from = edge.get("from").toString(), to = edge.get("to").toString();
            forward.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to); reverse.computeIfAbsent(to, key -> new LinkedHashSet<>()).add(from);
        }
        Map<String, String> callers = traverse(seeds, reverse, checkpoint), callees = traverse(seeds, forward, checkpoint);
        for (var endpoint : Values.objects(ast.get("endpoints"))) {
            String id = endpoint.get("sourcePath") + "::" + endpoint.get("methodId"); if (!callers.containsKey(id)) continue;
            var entry = new LinkedHashMap<>(endpoint); entry.put("sourceVersion", version); entry.put("nodeId", id);
            entry.put("callPath", witness(id, callers)); entry.put("reason", seeds.contains(id) ? "CHANGED_DECLARATION" : "CALLS_CHANGED_METHOD"); endpoints.add(entry);
        }
        for (var statement : Values.objects(ast.get("mapperStatements"))) {
            List<String> methods = Values.objects(ast.get("methods")).stream().filter(m -> statement.get("namespace").equals(m.get("owner")) && statement.get("id").equals(m.get("name"))).map(GitDiffAnalyzer::nodeId).filter(callees::containsKey).toList();
            if (methods.isEmpty()) continue;
            if (Boolean.TRUE.equals(statement.get("dynamic"))) { diagnostics.add(diagnostic(version, statement, "DYNAMIC_MAPPER_SQL", "动态 SQL 的表依赖可能不完整")); continue; }
            try {
                var sql = CCJSqlParserUtil.parse(Values.text(statement, "sql", "").replaceAll("#\\{[^}]+}", "?"));
                for (String table : new TablesNamesFinder<Void>().getTables(sql)) tables.add(Map.of("table", table, "sourceVersion", version, "sourcePath", statement.get("sourcePath"), "startLine", statement.get("startLine"), "methodNodes", methods, "basis", "MAPPER_SQL"));
            } catch (Exception unsupported) { diagnostics.add(diagnostic(version, statement, "MAPPER_SQL_UNRESOLVED", "无法静态解析此 Mapper 语句的表依赖")); }
        }
        var unresolved = Values.objects(graph.get("unresolvedCalls"));
        if (!unresolved.isEmpty()) diagnostics.add(Map.of("code", "UNRESOLVED_CALLS", "sourceVersion", version, "count", unresolved.size(), "message", "存在库外调用、动态接收者或未解析重载；静态影响范围可能漏选，完整调用明细可查看"));
    }
    private Map<String, Object> diagnostic(String version, Map<String, Object> source, String code, String message) {
        return Map.of("code", code, "sourceVersion", version, "sourcePath", source.get("sourcePath"), "line", source.get("startLine"), "message", message);
    }
    private Map<String, String> traverse(Set<String> seeds, Map<String, Set<String>> graph, Runnable checkpoint) {
        Map<String, String> previous = new LinkedHashMap<>(); ArrayDeque<String> pending = new ArrayDeque<>();
        for (String seed : seeds) { previous.put(seed, ""); pending.add(seed); }
        int count = 0;
        while (!pending.isEmpty()) {
            if (++count % 100 == 0) checkpoint.run();
            String current = pending.removeFirst();
            for (String next : graph.getOrDefault(current, Set.of())) if (!previous.containsKey(next)) { previous.put(next, current); pending.add(next); }
        }
        return previous;
    }
    private List<String> witness(String end, Map<String, String> parents) {
        List<String> result = new ArrayList<>(); String next = end;
        while (next != null && !next.isBlank()) { result.add(next); next = parents.get(next); }
        return result;
    }
}
