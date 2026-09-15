package com.aitest.analysis.rca;

import com.aitest.asset.*;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import static com.aitest.analysis.rca.FailureAnalysisResult.invalid;

/** Shared code-hypothesis boundary for diagnosis, generic/local/global drafts and persisted adoption. */
@Component
public final class FailureDiagnosisAgent {
    private static final Pattern LOCATION = Pattern.compile("^(.+):(\\d{1,9})$");
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d{1,9})(?:,(\\d{1,9}))? \\+(\\d{1,9})(?:,(\\d{1,9}))? @@(?:.*)$");
    private final SourceEvidenceReader sources;
    public FailureDiagnosisAgent(SourceEvidenceReader sources) { this.sources = sources; }
    public Map<String, Object> contexts(String project, Collection<Asset> targets) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Asset asset : targets) if (asset.type() == AssetType.BUG && result.size() < 8) result.put(asset.id(), sources.forBug(project, asset.id()));
        return result;
    }
    public void validateAsset(Asset previous, Asset candidate) {
        if (candidate.type() != AssetType.BUG || Objects.equals(previous == null ? Map.of() : previous.data().getOrDefault("codeDiagnosis", Map.of()), candidate.data().getOrDefault("codeDiagnosis", Map.of()))) return;
        validate(candidate.data().getOrDefault("codeDiagnosis", Map.of()), previous == null ? SourceEvidenceReader.empty("NO_FAILURE_SOURCE", "新候选没有失败现场") : sources.forBug(candidate.projectId(), previous.id()));
    }
    public Map<String, Object> validate(Object value, Map<String, Object> evidence) {
        Map<String, Object> code = FailureAnalysisResult.validate(value); if (code.isEmpty()) return code;
        String location = code.get("affected_code_path").toString(), patch = code.get("suggested_fix").toString();
        if (location.isBlank() && patch.isBlank()) return code;
        if (location.isBlank()) throw invalid("有补丁时需要明确的捕获源码位置");
        var match = LOCATION.matcher(location); if (!match.matches()) throw invalid("代码位置需要为捕获的相对路径:行号");
        String path = safePath(match.group(1)); int line = Integer.parseInt(match.group(2));
        Map<String, SortedMap<Integer, String>> files = capturedLines(evidence);
        if (!files.containsKey(path) || !files.get(path).containsKey(line)) throw invalid("代码位置没有对应的固定失败源码证据");
        if (!patch.isBlank() && !validatePatch(patch, files).contains(path)) throw invalid("补丁没有修改所选的关联代码文件");
        return code;
    }
    private Map<String, SortedMap<Integer, String>> capturedLines(Map<String, Object> evidence) {
        Map<String, SortedMap<Integer, String>> files = new LinkedHashMap<>(); Map<String, String> hashes = new HashMap<>();
        for (var location : Values.objects(evidence.get("locations"))) {
            String path = safePath(Values.text(location, "path", "")), hash = Values.text(location, "sha256", "");
            if (hash.isBlank() || hashes.putIfAbsent(path, hash) != null && !hashes.get(path).equals(hash)) throw invalid("同一路径有多份不同源码，不能自动选择补丁依据");
            int from = number(location.get("from")), to = number(location.get("to")); String[] lines = Values.text(location, "content", "").split("\\n", -1);
            if (from < 1 || to < from || to - from >= 200 || lines.length != to - from + 1) throw invalid("保存的源码窗口结构无效");
            var target = files.computeIfAbsent(path, ignored -> new TreeMap<>());
            for (int i = 0; i < lines.length; i++) target.put(from + i, lines[i]);
        }
        return files;
    }
    private Set<String> validatePatch(String patch, Map<String, SortedMap<Integer, String>> files) {
        String[] lines = patch.replace("\r\n", "\n").split("\n", -1);
        if (lines.length > 2000 || patch.replace("\r\n", "").indexOf('\r') >= 0) throw invalid("补丁过长或包含不支持的控制符");
        int index = 0; Set<String> changed = new LinkedHashSet<>();
        while (index < lines.length) {
            if (index == lines.length - 1 && lines[index].isEmpty()) break;
            String gitHeader = null;
            if (lines[index].startsWith("diff --git ")) gitHeader = lines[index++];
            if (index < lines.length && lines[index].matches("index [0-9a-f]+\\.\\.[0-9a-f]+(?: 100(?:644|755))?")) index++;
            if (index + 1 >= lines.length || !lines[index].startsWith("--- ") || !lines[index + 1].startsWith("+++ ")) throw invalid("补丁必须包含完整的 unified diff 文件头");
            String path = header(lines[index++], "--- a/"), next = header(lines[index++], "+++ b/");
            if (!path.equals(next) || !files.containsKey(path) || !changed.add(path)) throw invalid("补丁只能修改有失败证据的现有文件，每个文件只出现一次");
            if (gitHeader != null && !gitHeader.equals("diff --git a/" + path + " b/" + path)) throw invalid("补丁 Git 文件头与源码路径不一致");
            int lastEnd = 0, delta = 0, hunks = 0;
            while (index < lines.length && lines[index].startsWith("@@ ")) {
                var hunk = HUNK.matcher(lines[index++]); if (!hunk.matches()) throw invalid("补丁区间格式无效");
                int oldStart = Integer.parseInt(hunk.group(1)), oldCount = count(hunk.group(2)), newStart = Integer.parseInt(hunk.group(3)), newCount = count(hunk.group(4));
                if (oldCount > 2000 || newCount > 2000 || oldStart < lastEnd || oldStart < 1 && oldCount != 0 || oldCount == 0 && newCount == 0
                        || (long) newStart != (long) oldStart + delta + (oldCount == 0 ? 1 : 0) - (newCount == 0 ? 1 : 0)) throw invalid("补丁区间越界、重叠或新旧行号不一致");
                var original = files.get(path);
                if (oldCount == 0 && !original.containsKey(oldStart) && !original.containsKey(oldStart + 1)) throw invalid("插入位置不在固定源码窗口内");
                int oldLines = 0, newLines = 0; boolean edited = false;
                while (index < lines.length && (oldLines < oldCount || newLines < newCount)) {
                    String row = lines[index++];
                    if (row.equals("\\ No newline at end of file")) continue;
                    if (row.isEmpty() || " +-".indexOf(row.charAt(0)) < 0) throw invalid("补丁行缺少上下文、添加或删除标记");
                    char operation = row.charAt(0); String text = row.substring(1);
                    if (operation != '+') {
                        if (!Objects.equals(original.get(oldStart + oldLines), text)) throw invalid("补丁上下文不匹配固定源码，或超出可见源码窗口");
                        oldLines++;
                    }
                    if (operation != '-') newLines++;
                    edited |= operation != ' ';
                    if (oldLines > oldCount || newLines > newCount) throw invalid("补丁实际行数与声明不符");
                }
                if (oldLines != oldCount || newLines != newCount || !edited) throw invalid("补丁区间未完整结束或没有修改");
                if (index < lines.length && lines[index].equals("\\ No newline at end of file")) index++;
                lastEnd = oldStart + oldCount; delta += newCount - oldCount; hunks++;
            }
            if (hunks == 0) throw invalid("补丁缺少实际变更区间");
        }
        if (changed.isEmpty()) throw invalid("补丁没有可验证的修改"); return changed;
    }
    private static String safePath(String path) {
        if (path.isBlank() || path.length() > 2048 || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.chars().anyMatch(c -> c < 32)
                || Arrays.stream(path.split("/", -1)).anyMatch(part -> part.isEmpty() || part.equals("..") || part.equals("."))) throw invalid("源码路径必须为捕获的普通相对路径");
        return path;
    }
    private static String header(String line, String prefix) { if (!line.startsWith(prefix)) throw invalid("补丁仅支持 a/ 和 b/ 相对文件头"); return safePath(line.substring(prefix.length())); }
    private static int count(String value) { return value == null ? 1 : Integer.parseInt(value); }
    private static int number(Object value) { return value instanceof Number number ? number.intValue() : 0; }
}
