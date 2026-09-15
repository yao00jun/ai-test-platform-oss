package com.aitest.analysis.diff;

import com.aitest.analysis.source.*;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Compares captured bytes, never the current checkout. Git receives only an isolated pair of directories. */
@Component
public final class GitDiffAnalyzer {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    public Map<String, Object> compare(List<SourceFile> before, List<SourceFile> after, Map<String, Object> oldAst, Map<String, Object> newAst, Runnable checkpoint) {
        Path temporary = null;
        try {
            temporary = Files.createTempDirectory("aitest-source-diff-");
            Map<String, String> paths = new HashMap<>();
            materialize(temporary.resolve("old"), before, paths, checkpoint);
            materialize(temporary.resolve("new"), after, paths, checkpoint);
            String patch = new String(GitCommands.run(temporary, List.of("-c", "core.quotePath=false", "-c", "diff.algorithm=myers", "diff", "--no-index", "--no-ext-diff", "--no-textconv", "--text", "--find-renames", "--unified=0", "--", "old", "new"),
                    new byte[0], 32 * 1024 * 1024, Duration.ofSeconds(45), checkpoint, Set.of(0, 1)), StandardCharsets.UTF_8);
            List<Map<String, Object>> files = parse(patch, paths);
            List<Map<String, Object>> changed = new ArrayList<>(), classChanges = new ArrayList<>();
            List<Map<String, Object>> oldMethods = Values.objects(oldAst.get("methods")), newMethods = Values.objects(newAst.get("methods"));
            for (Map<String, Object> file : files) {
                checkpoint.run();
                String oldPath = Values.text(file, "oldPath", ""), newPath = Values.text(file, "newPath", "");
                List<Map<String, Object>> oldInFile = oldMethods.stream().filter(m -> oldPath.equals(m.get("sourcePath"))).toList();
                List<Map<String, Object>> newInFile = newMethods.stream().filter(m -> newPath.equals(m.get("sourcePath"))).toList();
                List<Map<String, Object>> oldTouched = touched(oldInFile, file, "old"), newTouched = touched(newInFile, file, "new");
                Set<String> usedNew = new HashSet<>();
                for (var previous : oldTouched) {
                    var current = newInFile.stream().filter(m -> m.get("id").equals(previous.get("id"))).findFirst().orElse(null);
                    String action = current == null ? "DELETED" : "MODIFIED";
                    if (current == null) {
                        String bodyHash = Values.text(previous, "bodyHash", "");
                        var matches = newTouched.stream().filter(m -> !usedNew.contains(nodeId(m)) && !bodyHash.isBlank() && bodyHash.equals(m.get("bodyHash")) && sameRenameShape(previous, m)
                                && oldInFile.stream().noneMatch(old -> old.get("id").equals(m.get("id")))).toList();
                        if (matches.size() == 1 && oldTouched.stream().filter(m -> bodyHash.equals(m.get("bodyHash"))).count() == 1) { current = matches.getFirst(); action = "RENAMED"; }
                    }
                    if (current != null) usedNew.add(nodeId(current));
                    changed.add(change(previous, current, action));
                }
                for (var current : newTouched) if (!usedNew.contains(nodeId(current))) {
                    var previous = oldInFile.stream().filter(m -> m.get("id").equals(current.get("id"))).findFirst().orElse(null);
                    changed.add(change(previous, current, previous == null ? "ADDED" : "MODIFIED"));
                }
                structural(file, "old", oldPath, oldAst, oldInFile, classChanges);
                structural(file, "new", newPath, newAst, newInFile, classChanges);
            }
            return Map.of("files", files, "changedMethods", changed, "classChanges", classChanges);
        } catch (IOException failure) { throw new Problem(422, "SOURCE_DIFF_IO", "无法创建或读取源码差异临时目录"); }
        finally { if (temporary != null) remove(temporary); }
    }
    private void materialize(Path directory, List<SourceFile> files, Map<String, String> paths, Runnable checkpoint) throws IOException {
        Files.createDirectory(directory);
        for (SourceFile file : files) {
            checkpoint.run();
            // Hash names also preserve case-distinct Git paths on a case-insensitive host filesystem.
            String name = SourceFile.hash(file.path()); paths.put(name, file.path());
            Files.writeString(directory.resolve(name), file.content(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }
    private List<Map<String, Object>> parse(String patch, Map<String, String> paths) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object> current = null; StringBuilder body = new StringBuilder(); List<Map<String, Object>> hunks = new ArrayList<>();
        for (String line : patch.split("\n")) {
            if (line.startsWith("diff --git ")) {
                if (current != null) current.put("patch", display(body.toString(), paths));
                String[] parts = line.split(" ");
                current = new LinkedHashMap<>(); current.put("oldPath", path(parts[2], paths)); current.put("newPath", path(parts[3], paths));
                current.put("status", "MODIFIED"); hunks = new ArrayList<>(); current.put("hunks", hunks); result.add(current); body = new StringBuilder();
            }
            if (current == null) continue;
            body.append(line).append('\n');
            if (line.startsWith("new file mode")) { current.put("oldPath", ""); current.put("status", "ADDED"); }
            if (line.startsWith("deleted file mode")) { current.put("newPath", ""); current.put("status", "DELETED"); }
            if (line.startsWith("rename from ")) { current.put("oldPath", path(line.substring(12), paths)); current.put("status", "RENAMED"); }
            if (line.startsWith("rename to ")) current.put("newPath", path(line.substring(10), paths));
            var matcher = HUNK.matcher(line);
            if (matcher.matches()) hunks.add(Map.of("oldStart", Integer.parseInt(matcher.group(1)), "oldCount", count(matcher.group(2)), "newStart", Integer.parseInt(matcher.group(3)), "newCount", count(matcher.group(4))));
        }
        if (current != null) current.put("patch", display(body.toString(), paths));
        return result;
    }
    private String display(String patch, Map<String, String> paths) {
        // Only the header names are replaced; source lines that happen to contain a hash stay intact.
        StringBuilder result = new StringBuilder();
        for (String line : patch.split("\n")) {
            if (line.startsWith("diff --git ") || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("rename ")) {
                var matcher = Pattern.compile("(?:a/|b/)?(?:old|new)/([0-9a-f]{64})").matcher(line);
                StringBuilder header = new StringBuilder();
                while (matcher.find()) matcher.appendReplacement(header, java.util.regex.Matcher.quoteReplacement(paths.getOrDefault(matcher.group(1), matcher.group())));
                matcher.appendTail(header); line = header.toString();
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }
    private static int count(String value) { return value == null ? 1 : Integer.parseInt(value); }
    private static String path(String name, Map<String, String> paths) { return paths.getOrDefault(name.substring(name.lastIndexOf('/') + 1), ""); }
    private List<Map<String, Object>> touched(List<Map<String, Object>> methods, Map<String, Object> file, String side) {
        return methods.stream().filter(method -> Values.objects(file.get("hunks")).stream().anyMatch(hunk -> overlaps(method, hunk, side))).toList();
    }
    private boolean overlaps(Map<String, Object> method, Map<String, Object> hunk, String side) {
        int start = number(hunk, side + "Start"), count = number(hunk, side + "Count");
        return count > 0 && start <= number(method, "endLine") && start + count - 1 >= number(method, "startLine");
    }
    private void structural(Map<String, Object> file, String side, String path, Map<String, Object> ast, List<Map<String, Object>> methods, List<Map<String, Object>> output) {
        List<Map<String, Object>> ranges = Values.objects(file.get("hunks"));
        for (var range : ranges) {
            int start = number(range, side + "Start"), length = number(range, side + "Count");
            boolean outside = false;
            for (int line = start; line < start + length; line++) {
                int current = line;
                if (methods.stream().noneMatch(m -> number(m, "startLine") <= current && number(m, "endLine") >= current)) { outside = true; break; }
            }
            if (!outside) continue;
            var classes = Values.objects(ast.get("classes")).stream().filter(c -> path.equals(c.get("sourcePath")) && overlaps(c, range, side)).toList();
            if (classes.isEmpty()) classes = Values.objects(ast.get("classes")).stream().filter(c -> path.equals(c.get("sourcePath"))).toList();
            for (var owner : classes) {
                var change = Map.<String, Object>of("side", side, "sourcePath", path, "owner", owner.get("id"), "reason", "CLASS_OR_FILE_DECLARATION", "range", range);
                if (!output.contains(change)) output.add(change);
            }
        }
    }
    private Map<String, Object> change(Map<String, Object> before, Map<String, Object> after, String action) {
        var selected = after == null ? before : after;
        Map<String, Object> value = new LinkedHashMap<>(selected);
        value.remove("variables"); value.remove("variableTypes"); value.remove("annotations");
        value.put("action", action); value.put("beforeNodeId", before == null ? "" : nodeId(before)); value.put("afterNodeId", after == null ? "" : nodeId(after));
        value.put("before", before == null ? Map.of() : location(before)); value.put("after", after == null ? Map.of() : location(after));
        return value;
    }
    private Map<String, Object> location(Map<String, Object> method) { return Map.of("sourcePath", method.get("sourcePath"), "startLine", method.get("startLine"), "endLine", method.get("endLine"), "signature", method.get("signature")); }
    private boolean sameRenameShape(Map<String, Object> before, Map<String, Object> after) {
        if (!Objects.equals(before.get("owner"), after.get("owner")) || !Objects.equals(before.get("constructor"), after.get("constructor")) || !Objects.equals(before.get("returnType"), after.get("returnType"))) return false;
        var oldParameters = Values.objects(before.get("parameters")); var newParameters = Values.objects(after.get("parameters"));
        if (oldParameters.size() != newParameters.size()) return false;
        for (int i = 0; i < oldParameters.size(); i++) for (String field : List.of("type", "varArgs"))
            if (!Objects.equals(oldParameters.get(i).get(field), newParameters.get(i).get(field))) return false;
        return true;
    }
    public static String nodeId(Map<String, Object> method) { return method.get("sourcePath") + "::" + method.get("id"); }
    private static int number(Map<String, Object> map, String key) { return ((Number) map.get(key)).intValue(); }
    private static void remove(Path temporary) {
        try (var paths = Files.walk(temporary)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException failure) { throw new Problem(500, "SOURCE_DIFF_CLEANUP", "源码差异临时文件清理失败"); }
    }
}
