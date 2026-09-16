package com.aitest.exchange;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.storage.FileStorageService;
import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ExchangePreflight {
    private final AssetService assets;
    private final FileStorageService files;
    public ExchangePreflight(AssetService assets, FileStorageService files) { this.assets = assets; this.files = files; }
    public record Checked(List<ExchangeNode> order, List<ExchangeIssue> errors) { }
    public Checked validate(String projectId, String source, ExchangeBundle bundle, String parentId, Map<String, String> mappings) {
        List<ExchangeIssue> errors = new ArrayList<>(); Map<String, ExchangeNode> nodes = new LinkedHashMap<>(); Map<String, AssetType> localTypes = new LinkedHashMap<>();
        if (!ExchangeBundle.VERSION.equals(bundle.formatVersion())) errors.add(issue(source, 1, "formatVersion", "不支持的文件版本"));
        if (bundle.nodes().isEmpty() || bundle.nodes().size() > ExchangeIO.MAX_NODES) errors.add(issue(source, 1, "nodes", "导入要求 1–20000 个资产节点"));
        int row = 0;
        for (var node : bundle.nodes()) {
            row++;
            if (node.key() == null || !node.key().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) errors.add(issue(source, row, "key", "本地键需要 1–128 位字母/数字/下划线/点/连字符"));
            if (nodes.putIfAbsent(node.key(), node) != null) errors.add(issue(source, row, "key", "本地键重复"));
            localTypes.put(local(node.key()), node.type());
            if (node.type() == AssetType.PROJECT) errors.add(issue(source, row, "type", "项目导入将元信息保存在 metadata.project；不会覆盖目标项目，请将子资产作为 nodes"));
        }
        for (String key : mappings.keySet()) if (!bundle.externalReferences().containsKey(key)) errors.add(issue(source, 1, "referenceMappings." + key, "映射必须对应显式声明的 externalReferences 键"));
        for (var ref : bundle.externalReferences().entrySet()) {
            if (nodes.containsKey(ref.getKey())) errors.add(issue(source, 1, "externalReferences." + ref.getKey(), "外部引用键与本地键重复"));
            String id = mappings.get(ref.getKey());
            if (id == null || id.isBlank()) { errors.add(issue(source, 1, "referenceMappings." + ref.getKey(), "外部引用尚未绑定到当前项目资产")); continue; }
            try {
                Asset bound = assets.getInternal(projectId, id);
                if (!bound.type().name().equals(ref.getValue().type())) errors.add(issue(source, 1, "referenceMappings." + ref.getKey(), "绑定的资产类型与声明不符"));
            } catch (Problem e) { errors.add(issue(source, 1, "referenceMappings." + ref.getKey(), "绑定的资产不存在或不属于当前项目")); }
        }
        Map<String, String> localIds = new LinkedHashMap<>(); nodes.keySet().forEach(key -> localIds.put(key, local(key)));
        // Reused only for this preflight. Apply constructs a fresh scope while
        // holding the project lock, so an earlier preview cannot authorize writes.
        var validation = assets.creationValidation(projectId, localTypes);
        Map<String, Set<String>> dependencies = new LinkedHashMap<>(); row = 0;
        for (var node : bundle.nodes()) {
            row++; Set<String> deps = new HashSet<>(); dependencies.put(node.key(), deps); int before = errors.size();
            if (node.parentKey() != null && !node.parentKey().isBlank()) {
                if (!nodes.containsKey(node.parentKey())) errors.add(issue(source, row, "parentKey", "父节点本地键不存在")); else deps.add(node.parentKey());
            }
            for (String field : node.data().keySet()) if (AssetReferences.FIELDS.contains(field) && node.data().get(field) != null && !node.data().get(field).toString().isBlank())
                errors.add(issue(source, row, field, "资产引用必须通过 references 显式声明，不能直接导入真实 ID"));
            for (var reference : node.references().entrySet()) {
                String field = reference.getKey(), target = reference.getValue();
                if (!AssetReferences.FIELDS.contains(field) || node.type().fields().stream().noneMatch(f -> f.key().equals(field))) errors.add(issue(source, row, "references." + field, "此字段不是当前类型允许的资产引用"));
                if (nodes.containsKey(target)) deps.add(target);
                else if (!bundle.externalReferences().containsKey(target)) errors.add(issue(source, row, "references." + field, "引用的本地节点缺失，且没有 externalReferences 声明"));
            }
            if (node.position() < 0) errors.add(issue(source, row, "position", "顺序必须为非负整数"));
            if (errors.size() != before || node.type() == AssetType.PROJECT) continue;
            try {
                var data = resolveData(node, localIds, mappings);
                String parent = node.parentKey() == null || node.parentKey().isBlank() ? parentId : local(node.parentKey());
                validateFiles(projectId, data, source, row);
                validation.validate(node.type(), parent, node.name(), data);
            } catch (ExchangeException e) { errors.add(e.issue()); }
            catch (Problem e) {
                String field = "data";
                if (e.getMessage().contains("父")) field = "parentKey";
                else if (e.getMessage().contains("名称")) field = "name";
                else for (var f : node.type().fields()) if (e.getMessage().contains(f.label()) || e.getMessage().contains(f.key())) { field = f.key(); break; }
                errors.add(issue(source, row, field, safeValidationMessage(e)));
            }
        }
        // Keep imported sibling order without ever reordering or changing existing siblings.
        Map<String, List<ExchangeNode>> siblings = new LinkedHashMap<>();
        for (var node : bundle.nodes()) siblings.computeIfAbsent(node.type() + ":" + node.parentKey(), ignored -> new ArrayList<>()).add(node);
        for (var group : siblings.values()) {
            group.sort(Comparator.comparingInt(ExchangeNode::position));
            for (int i = 1; i < group.size(); i++) dependencies.get(group.get(i).key()).add(group.get(i - 1).key());
        }
        List<ExchangeNode> ordered = new ArrayList<>(); Map<String, Integer> remaining = new LinkedHashMap<>(); Map<String, List<String>> dependents = new HashMap<>();
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (String key : nodes.keySet()) {
            var deps = dependencies.get(key); remaining.put(key, deps.size()); if (deps.isEmpty()) ready.add(key);
            for (String dependency : deps) dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(key);
        }
        while (!ready.isEmpty()) {
            String key = ready.removeFirst(); ordered.add(nodes.get(key));
            for (String dependent : dependents.getOrDefault(key, List.of())) if (remaining.compute(dependent, (ignored, count) -> count - 1) == 0) ready.add(dependent);
        }
        if (ordered.size() != nodes.size()) errors.add(issue(source, 1, "references", "父节点、引用或同级顺序存在循环，无法原子创建"));
        return new Checked(ordered, errors);
    }
    private void validateFiles(String projectId, Map<String, Object> data, String source, int row) {
        for (String field : List.of("fileId", "fileIds", "attachments")) {
            Object value = data.get(field); List<?> ids = value instanceof List<?> list ? list : value instanceof String text && !text.isBlank() ? List.of(text) : List.of();
            for (Object id : ids) try { files.get(projectId, id.toString()); }
            catch (Problem e) { throw ExchangeIO.error(source, row, field, "受管文件不存在或不属于当前项目；附件不能引用其他项目的文件"); }
        }
        if (data.get("runId") instanceof String id && !id.isBlank()) throw ExchangeIO.error(source, row, "runId", "运行记录不能跨项目移植，请清空 runId 后导入");
    }
    public static Map<String, Object> resolveData(ExchangeNode node, Map<String, String> ids, Map<String, String> mappings) {
        Map<String, Object> data = new LinkedHashMap<>(node.data());
        node.references().forEach((field, key) -> data.put(field, ids.containsKey(key) ? ids.get(key) : mappings.get(key)));
        return data;
    }
    private static String local(String key) { return "import-local:" + key; }
    private static ExchangeIssue issue(String source, int row, String field, String message) { return new ExchangeIssue(source, row, field, message); }
    private static String safeValidationMessage(Problem problem) {
        // Domain errors contain labels/option descriptions only; unknown user-supplied field names are not echoed.
        if (problem.getMessage().contains("不允许字段")) return "资产数据包含该类型未声明的字段";
        if (problem.status() == 404) return "引用或父记录不存在，或不属于当前项目";
        return problem.getMessage();
    }
}
