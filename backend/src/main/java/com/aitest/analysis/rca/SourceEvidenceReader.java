package com.aitest.analysis.rca;

import com.aitest.analysis.*;
import com.aitest.analysis.diff.GitDiffAnalyzer;
import com.aitest.analysis.impact.SourceImpactRepository;
import com.aitest.analysis.source.SourceFile;
import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Reads captured bytes only. The saved, redacted result is also the transaction-safe adoption proof. */
@Component
public final class SourceEvidenceReader {
    private static final String VERSION = "aitest.failure-source-evidence/v1";
    private static final Pattern FRAME = Pattern.compile("(?m)^\\s*at\\s+(?:[\\w.$@-]+/(?:[\\w.$@-]*/)?)?([\\w.$]+)\\.([\\w$<>]+)\\(([^():\\r\\n]+\\.java):(\\d{1,9})\\)(?:[^\\r\\n]*)$");
    private static final int MAX_FRAMES = 12, MAX_TEXT = 80_000;
    private final SourceSnapshotRepository sources;
    private final SourceImpactRepository impacts;
    private final AssetRepository assets;
    private final JsonCodec json;
    private final GitDiffAnalyzer diff;
    public SourceEvidenceReader(SourceSnapshotRepository sources, SourceImpactRepository impacts, AssetRepository assets, JsonCodec json, GitDiffAnalyzer diff) {
        this.sources = sources; this.impacts = impacts; this.assets = assets; this.json = json; this.diff = diff;
    }
    public Map<String, Object> forBug(String project, String id) {
        assets.project(project);
        if (assets.find(project, id).type() != AssetType.BUG) throw Problem.invalid("目标不是缺陷");
        var rows = assets.jdbc().queryForList("SELECT evidence FROM bug_failure_occurrence WHERE project_id=? AND bug_id=? AND status='CREATED' ORDER BY created_at,id LIMIT 1", project, id);
        if (rows.isEmpty()) return empty("NO_FAILURE_SOURCE", "此人工缺陷没有关联已保存的失败源码证据");
        var value = Values.map(json.map(rows.getFirst().get("evidence").toString()).get("sourceEvidence"));
        return VERSION.equals(value.get("formatVersion")) ? value : empty("LEGACY_FAILURE_SOURCE", "此历史发生记录尚未保存源码定位证据");
    }
    public static Map<String, Object> empty(String code, String message) {
        return Map.of("formatVersion", VERSION, "bindings", List.of(), "locations", List.of(), "diffs", List.of(), "diagnostics", List.of(Map.of("code", code, "message", message)), "complete", false);
    }
    public Map<String, Object> read(String project, RunDefinition run, Map<String, Object> failure) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String id : List.of(Values.text(failure, "stepId", ""), Values.text(failure, "caseId", ""), run.graph().rootId())) collect(run.graph(), id, selected, new HashSet<>());
        if (selected.isEmpty()) return empty("SOURCE_NOT_BOUND", "该失败项的运行快照没有绑定源码");
        List<Map<String, Object>> diagnostics = new ArrayList<>(), bindings = new ArrayList<>(), locations = new ArrayList<>(), diffs = new ArrayList<>();
        Map<String, Map<String, Object>> snapshots = new LinkedHashMap<>();
        for (String source : selected) {
            if (snapshots.size() >= 8) { diagnostic(diagnostics, "SOURCE_BINDINGS_TRUNCATED", "失败关联的源码超过 8 份，部分未载入"); break; }
            try {
                var binding = sources.binding(project, source);
                boolean captured = run.graph().sourceEvidence().stream().anyMatch(item -> source.equals(item.get("sourceSnapshotId")) && binding.get("manifestHash").equals(item.get("manifestHash")));
                if (!captured) { diagnostic(diagnostics, "SOURCE_BINDING_MISMATCH", "运行时源码摘要与存档不匹配：" + source); continue; }
                bindings.add(binding); snapshots.put(source, sources.result(project, source));
                if (!"READY".equals(binding.get("status"))) diagnostic(diagnostics, "SOURCE_PARTIAL", "源码快照包含采集或解析缺口：" + source);
            } catch (Problem problem) { diagnostic(diagnostics, "SOURCE_UNAVAILABLE", "无法读取运行绑定的源码：" + source); }
        }
        StringBuilder trace = new StringBuilder(); traceText(Values.map(failure.get("result")).get("actual"), trace); traceText(Values.map(failure.get("result")).get("error"), trace);
        var matcher = FRAME.matcher(trace); int frames = 0, remaining = MAX_TEXT; Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            if (++frames > MAX_FRAMES) { diagnostic(diagnostics, "STACK_TRUNCATED", "仅解析前 12 条 Java 堆栈位置"); break; }
            String className = matcher.group(1), method = matcher.group(2), filename = matcher.group(3); int line = Integer.parseInt(matcher.group(4));
            List<Match> candidates = new ArrayList<>();
            for (var entry : snapshots.entrySet()) for (var callable : Values.objects(Values.map(entry.getValue().get("backend")).get("methods"))) {
                String owner = Values.text(callable, "owner", ""), path = Values.text(callable, "sourcePath", "");
                boolean constructor = "<init>".equals(method) && Boolean.TRUE.equals(callable.get("constructor"));
                if (owner.replace('$', '.').equals(className.replace('$', '.')) && (constructor || method.equals(callable.get("name"))) && path.substring(path.lastIndexOf('/') + 1).equals(filename)) candidates.add(new Match(entry.getKey(), path, callable));
            }
            String label = className + "." + method + "(" + filename + ":" + line + ")";
            if (candidates.isEmpty()) { diagnostic(diagnostics, "SOURCE_METHOD_NOT_FOUND", "源码中未找到精确类名、方法和文件：" + label); continue; }
            List<Match> inRange = candidates.stream().filter(candidate -> number(candidate.callable(), "startLine") <= line && line <= number(candidate.callable(), "endLine")).toList();
            if (inRange.isEmpty()) { diagnostic(diagnostics, "SOURCE_LINE_OUT_OF_RANGE", "堆栈行号不在捕获的方法范围内：" + label); continue; }
            if (inRange.size() != 1) { diagnostic(diagnostics, "SOURCE_LOCATION_AMBIGUOUS", "多份捕获源码匹配堆栈，未自动选择：" + label); continue; }
            Match match = inRange.getFirst(); String key = SourceFile.hash(match.source() + ":" + match.path() + ":" + line);
            if (!seen.add(key)) continue;
            try {
                var excerpt = sources.excerpt(project, match.source(), "BACKEND", match.path(), Math.max(1, line - 6), line + 6);
                String content = excerpt.get("content").toString();
                if (content.length() > remaining) { diagnostic(diagnostics, "SOURCE_TEXT_TRUNCATED", "片段超过本次诊断的 80000 字符预算：" + match.path()); continue; }
                remaining -= content.length();
                Map<String, Object> location = new LinkedHashMap<>(Map.of("id", key, "sourceSnapshotId", match.source(), "path", match.path(), "sha256", excerpt.get("sha256"), "className", className, "method", method, "line", line, "from", excerpt.get("from"), "to", excerpt.get("to"), "content", content));
                locations.add(location);
            } catch (Problem problem) { diagnostic(diagnostics, "SOURCE_EXCERPT_UNAVAILABLE", "固定源码片段不可用：" + label); }
        }
        if (frames == 0) diagnostic(diagnostics, "JAVA_STACK_NOT_FOUND", "实际失败结果没有可解析的 Java 文件行号堆栈");
        for (var entry : snapshots.entrySet()) {
            String source = entry.getKey(); Set<String> paths = new LinkedHashSet<>();
            for (var location : locations) if (source.equals(location.get("sourceSnapshotId"))) paths.add(location.get("path").toString());
            if (paths.isEmpty()) continue;
            try {
                List<Map<String, Object>> fileChanges = new ArrayList<>();
                for (var binding : run.graph().sourceEvidence()) if (source.equals(binding.get("sourceSnapshotId")) && !Values.text(binding, "impactId", "").isBlank()) {
                    var impact = impacts.result(project, binding.get("impactId").toString());
                    fileChanges.addAll(Values.objects(impact.get("files")));
                }
                if (fileChanges.isEmpty() && !Values.text(Values.map(Values.map(entry.getValue().get("origins")).get("BACKEND")), "baselineRevision", "").isBlank()) {
                    List<SourceFile> before = new ArrayList<>(), after = new ArrayList<>();
                    for (String path : paths) {
                        after.add(sources.file(project, source, "BACKEND", path));
                        try { before.add(sources.file(project, source, "BASELINE", path)); } catch (Problem missing) { if (missing.status() != 404) throw missing; }
                    }
                    fileChanges = Values.objects(diff.compare(before, after, Values.map(entry.getValue().get("baselineBackend")), Values.map(entry.getValue().get("backend")), () -> { if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException(); }).get("files"));
                }
                for (var changed : fileChanges) {
                    String path = Values.text(changed, "newPath", Values.text(changed, "oldPath", "")); if (!paths.contains(path)) continue;
                    String patch = sources.redact(project, source, Values.text(changed, "patch", "")).toString();
                    if (patch.length() > remaining) { diagnostic(diagnostics, "SOURCE_DIFF_TRUNCATED", "基线差异超过本次诊断预算：" + path); continue; }
                    remaining -= patch.length(); diffs.add(Map.of("sourceSnapshotId", source, "path", path, "diff", patch));
                }
            } catch (Problem unavailable) { diagnostic(diagnostics, "SOURCE_DIFF_UNAVAILABLE", "固定基线差异读取失败：" + source); }
        }
        return Map.of("formatVersion", VERSION, "bindings", bindings, "locations", locations, "diffs", diffs, "diagnostics", diagnostics, "complete", diagnostics.isEmpty());
    }
    private record Match(String source, String path, Map<String, Object> callable) { }
    private void collect(AssetGraph graph, String id, Set<String> sources, Set<String> seen) {
        if (id.isBlank() || !seen.add(id)) return;
        Asset asset = graph.assets().get(id); if (asset == null) return;
        String source = Values.text(asset.data(), "sourceSnapshotId", ""); if (!source.isBlank()) sources.add(source);
        if (asset.parentId() != null) collect(graph, asset.parentId(), sources, seen);
        for (String reference : List.of("apiCaseId", "apiDefinitionId")) collect(graph, Values.text(asset.data(), reference, ""), sources, seen);
    }
    private void traceText(Object value, StringBuilder output) {
        if (output.length() >= 200_000) return;
        if (value instanceof String text) output.append(text, 0, Math.min(text.length(), 200_000 - output.length())).append('\n');
        else if (value instanceof Map<?, ?> map) for (Object item : map.values()) traceText(item, output);
        else if (value instanceof Collection<?> list) for (Object item : list) traceText(item, output);
    }
    private static int number(Map<String, Object> value, String key) { return value.get(key) instanceof Number number ? number.intValue() : 0; }
    private static void diagnostic(List<Map<String, Object>> values, String code, String message) { if (values.size() < 64) values.add(Map.of("code", code, "message", message)); }
}
