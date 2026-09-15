package com.aitest.ai;

import com.aitest.asset.*;
import com.aitest.analysis.GenerationEvidenceService;
import com.aitest.common.Ids;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AiChangeSetService {
    private final JdbcTemplate jdbc;
    private final AssetRepository repository;
    private final AssetService assets;
    private final AiConversationService conversations;
    private final JsonCodec json;
    private final GenerationEvidenceService evidence;
    public AiChangeSetService(JdbcTemplate jdbc, AssetRepository repository, AssetService assets, AiConversationService conversations, JsonCodec json, GenerationEvidenceService evidence) {
        this.jdbc = jdbc; this.repository = repository; this.assets = assets; this.conversations = conversations; this.json = json; this.evidence = evidence;
    }
    @Transactional
    public String create(String projectId, String conversationId, String jobId, List<Proposal> proposals) {
        repository.project(projectId);
        Map<String, Object> conversation = conversations.get(projectId, conversationId);
        if (proposals == null || proposals.isEmpty() || proposals.size() > 500) throw Problem.invalid("每个变更集需要 1–500 个显式变更");
        boolean local = "LOCAL".equals(conversation.get("scope"));
        if (local && (proposals.size() != 1 || !Objects.equals(conversation.get("targetId"), proposals.getFirst().targetId()))) throw Problem.invalid("局部变更集只能包含指定目标");
        if (local && !"MODIFY".equals(proposals.getFirst().operation())) throw Problem.invalid("局部调优只允许修改当前目标");
        Map<String, AssetType> localTypes = new HashMap<>();
        for (var proposal : proposals) if ("ADD".equals(proposal.operation()) && proposal.localKey() != null) {
            if (!proposal.localKey().matches("[A-Za-z0-9_-]{1,128}") || proposal.targetType() == null || localTypes.put("@" + proposal.localKey(), proposal.targetType()) != null) throw Problem.invalid("新增 localKey 必须合法且唯一");
        }
        validateNewDependencies(proposals);
        Map<String, Asset> proposedGraph = local ? null : proposedGraph(projectId, proposals);
        String id = Ids.newId();
        jdbc.update("INSERT INTO ai_change_set(id,project_id,conversation_id,job_id,status,created_at) VALUES(?,?,?,?,'DRAFT',?)", id, projectId, conversationId, jobId, Timestamp.from(Instant.now()));
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < proposals.size(); index++) {
            Proposal proposal = proposals.get(index);
            if (!Set.of("ADD", "MODIFY", "DELETE").contains(proposal.operation()) || proposal.targetType() == null) throw Problem.invalid("变更操作或类型无效");
            if (proposal.targetType() == AssetType.PROJECT && proposal.operation().equals("DELETE")) throw Problem.invalid("AI 变更集不能删除项目");
            validateProtectedFields(proposal.operation(), proposal.targetType(), proposal.data());
            Asset before = null;
            if (!proposal.operation().equals("ADD")) {
                if (proposal.targetId() == null || !seen.add(proposal.targetId())) throw Problem.invalid("变更集不能重复修改同一目标");
                before = assets.get(projectId, proposal.targetId());
                if (before.type() != proposal.targetType()) throw Problem.invalid("变更目标类型不匹配");
                AssetService.requireVersion(before, proposal.baseVersion());
                if (!local && before.confirmed()) throw new Problem(409, "PROTECTED_ASSET", "已确认的人工保护资产不能被全局变更覆盖", Map.of("targetId", before.id()));
                if (proposal.operation().equals("MODIFY")) {
                    Asset original = assets.getInternal(projectId, before.id()), candidate = assets.prepare(original, proposal.name(), proposal.data(), null);
                    assets.validateCandidate(original, candidate, proposedGraph); evidence.validate(original, candidate, proposedGraph);
                }
            } else {
                if (proposal.targetId() != null) throw Problem.invalid("新增资产 ID 由后端分配");
                if (proposal.localKey() != null && !seen.add("@" + proposal.localKey())) throw Problem.invalid("localKey 不唯一");
                if (proposal.targetType() == AssetType.PROJECT) throw Problem.invalid("项目资料生成应修改当前项目");
                assets.validateCreation(projectId, proposal.targetType(), proposal.parentId(), proposal.name(), proposal.data() == null ? Map.of() : proposal.data(), localTypes);
                evidence.validate(null, proposedGraph.get(proposal.localKey() == null ? "preview-" + index : "@" + proposal.localKey()), proposedGraph);
            }
            Map<String, Object> after = new LinkedHashMap<>(); after.put("name", proposal.name()); after.put("data", proposal.data() == null ? Map.of() : proposal.data());
            jdbc.update("INSERT INTO ai_change_item(id,change_set_id,operation,target_type,target_id,parent_id,local_key,base_version,before_snapshot,after_snapshot,validation,position) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", Ids.newId(), id, proposal.operation(), proposal.targetType().name(), proposal.targetId(), before == null ? proposal.parentId() : before.parentId(), proposal.localKey(), proposal.baseVersion() == null ? null : Long.parseLong(proposal.baseVersion()), before == null ? null : json.write(before), json.write(after), json.write(Map.of("valid", true)), index);
        }
        return id;
    }
    public Map<String, Object> get(String projectId, String id) {
        repository.project(projectId);
        var rows = jdbc.queryForList("SELECT * FROM ai_change_set WHERE id=? AND project_id=?", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", id); result.put("status", rows.getFirst().get("status")); result.put("items", items(id));
        return result;
    }
    private List<Map<String, Object>> items(String id) {
        return jdbc.query("SELECT * FROM ai_change_item WHERE change_set_id=? ORDER BY position", (rs, n) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getString("id")); result.put("operation", rs.getString("operation")); result.put("targetType", rs.getString("target_type"));
            result.put("targetId", rs.getString("target_id")); result.put("parentId", rs.getString("parent_id")); result.put("localKey", rs.getString("local_key")); result.put("baseVersion", rs.getString("base_version"));
            result.put("before", rs.getString("before_snapshot") == null ? null : json.tree(rs.getString("before_snapshot"))); result.put("after", json.map(rs.getString("after_snapshot"))); result.put("validation", json.tree(rs.getString("validation")));
            return result;
        }, id);
    }
    /** Evidence used by a candidate must still be current when the user adopts it. */
    @Transactional
    public void guardEvidence(String projectId, String id, Map<String, String> versions) {
        repository.lockProject(projectId); get(projectId, id); requireCurrentVersions(projectId, versions);
        versions.forEach((assetId, version) -> jdbc.update("INSERT INTO ai_change_guard(change_set_id,asset_id,base_version) VALUES(?,?,?)", id, assetId, Long.parseLong(version)));
    }
    public void requireCurrentVersions(String projectId, Map<String, String> versions) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String assetId : new TreeSet<>(versions.keySet())) {
            var current = jdbc.queryForList("SELECT version FROM asset WHERE id=? AND project_id=? AND deleted=FALSE FOR UPDATE", String.class, assetId, projectId);
            if (current.isEmpty() || !current.getFirst().equals(versions.get(assetId))) conflicts.add(Map.of("targetId", assetId, "baseVersion", versions.get(assetId), "currentVersion", current.isEmpty() ? "DELETED" : current.getFirst()));
        }
        if (!conflicts.isEmpty()) throw new Problem(409, "CHANGE_EVIDENCE_CONFLICT", "生成依据或目标已变化，未应用任何修改；请基于当前内容重新生成", conflicts);
    }
    @Transactional
    public Map<String, Object> apply(String projectId, String id, List<String> selectedIds) {
        repository.lockProject(projectId);
        var rows = jdbc.queryForList("SELECT * FROM ai_change_set WHERE id=? AND project_id=? FOR UPDATE", id, projectId);
        if (rows.isEmpty()) throw Problem.missing();
        var row = rows.getFirst();
        List<Map<String, Object>> all = items(id);
        Set<String> selection = selectedIds == null ? Set.of() : new LinkedHashSet<>(selectedIds);
        if (selection.isEmpty() || selection.size() != selectedIds.size()) throw Problem.invalid("请选择唯一的变更项");
        if (row.get("status").equals("APPLIED")) {
            if (!new HashSet<>((List<?>) json.tree(row.get("selected_items").toString())).equals(selection)) throw Problem.conflict("变更集已用不同的选择采纳");
            return json.map(row.get("result").toString());
        }
        if (!row.get("status").equals("DRAFT")) throw Problem.conflict("变更集已关闭");
        Map<String, String> guards = new LinkedHashMap<>();
        jdbc.query("SELECT asset_id,base_version FROM ai_change_guard WHERE change_set_id=?", rs -> { guards.put(rs.getString("asset_id"), rs.getString("base_version")); }, id);
        requireCurrentVersions(projectId, guards);
        List<Map<String, Object>> selected = all.stream().filter(item -> selection.contains(item.get("id"))).toList();
        if (selected.size() != selection.size()) throw Problem.invalid("变更项不属于此变更集");
        for (var item : selected) validateProtectedFields(item.get("operation").toString(), AssetType.valueOf(item.get("targetType").toString()), objectMap(objectMap(item.get("after")).get("data")));
        boolean local = "LOCAL".equals(conversations.get(projectId, row.get("conversation_id").toString()).get("scope"));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (var item : selected) if (!item.get("operation").equals("ADD")) {
            try {
                Asset current = assets.get(projectId, item.get("targetId").toString());
                if (!current.version().equals(item.get("baseVersion")) || (!local && current.confirmed())) conflicts.add(Map.of("targetId", current.id(), "currentVersion", current.version()));
            } catch (Problem missing) { conflicts.add(Map.of("targetId", item.get("targetId"), "message", "目标已删除")); }
        }
        if (!conflicts.isEmpty()) throw new Problem(409, "CHANGE_SET_CONFLICT", "选定变更存在冲突，未应用任何修改", conflicts);
        Set<String> deletedIds = selected.stream().filter(item -> item.get("operation").equals("DELETE")).map(item -> item.get("targetId").toString()).collect(java.util.stream.Collectors.toSet());
        for (String targetId : deletedIds) {
            ArrayDeque<Asset> descendants = new ArrayDeque<>(assets.children(projectId, targetId));
            while (!descendants.isEmpty()) {
                Asset child = descendants.removeFirst();
                if (!deletedIds.contains(child.id())) throw new Problem(409, "EXPLICIT_DELETE_REQUIRED", "删除父节点必须显式选中全部子节点，其他资产未被删除", Map.of("targetId", child.id()));
                if (!child.type().childTypes().isEmpty()) descendants.addAll(assets.children(projectId, child.id()));
            }
        }
        Map<String, String> localIds = new HashMap<>(); List<Asset> applied = new ArrayList<>();
        List<Map<String, Object>> pending = new ArrayList<>(selected.stream().filter(i -> i.get("operation").equals("ADD")).toList());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var item : List.copyOf(pending)) {
                Map<String, Object> after = objectMap(item.get("after"));
                if (!referencesReady(after, localIds) || !referencesReady(item.get("parentId"), localIds)) continue;
                Map<String, Object> data = objectMap(resolveValue(after.get("data"), localIds)); String parentId = resolveReference((String) item.get("parentId"), localIds);
                evidence.validate(null, new Asset("adoption", projectId, AssetType.valueOf(item.get("targetType").toString()), parentId, Objects.toString(after.get("name"), ""), "1", 0, "AI", false, Instant.EPOCH, Instant.EPOCH, data), null);
                Asset created = assets.create(projectId, AssetType.valueOf(item.get("targetType").toString()), parentId, Objects.toString(after.get("name"), ""), data, "AI");
                applied.add(created);
                if (item.get("localKey") != null) localIds.put("@" + item.get("localKey"), created.id());
                jdbc.update("UPDATE ai_change_item SET target_id=? WHERE id=?", created.id(), item.get("id"));
                pending.remove(item); progressed = true;
            }
            if (!progressed) throw new Problem(409, "MISSING_SELECTED_DEPENDENCY", "新增资产依赖了未选中的变更、循环引用或未知 localKey");
        }
        List<Proposal> selectedProposals = new ArrayList<>();
        for (var item : selected) if (!item.get("operation").equals("ADD")) {
            Map<String, Object> after = objectMap(item.get("after"));
            if (!referencesReady(after, localIds)) throw Problem.invalid("修改引用了未选中的新增资产");
            selectedProposals.add(new Proposal(item.get("operation").toString(), AssetType.valueOf(item.get("targetType").toString()), item.get("targetId").toString(), (String) item.get("parentId"), null, item.get("baseVersion").toString(), (String) after.get("name"), objectMap(resolveValue(after.get("data"), localIds))));
        }
        Map<String, Asset> selectedGraph = local ? null : proposedGraph(projectId, selectedProposals);
        if (!local) validateSelectedVariableDependencies(all, selected, selectedGraph);
        for (Proposal proposal : selectedProposals) if (proposal.operation().equals("MODIFY")) {
            Asset previous = assets.getInternal(projectId, proposal.targetId());
            Asset candidate = assets.prepare(previous, proposal.name(), proposal.data(), null);
            assets.validateCandidate(previous, candidate, selectedGraph); evidence.validate(previous, candidate, selectedGraph);
        }
        for (var item : selected) if (item.get("operation").equals("MODIFY")) {
            Map<String, Object> after = objectMap(item.get("after"));
            if (!referencesReady(after, localIds)) throw Problem.invalid("修改引用了未选中的新增资产");
            applied.add(assets.updateInGraph(projectId, item.get("targetId").toString(), item.get("baseVersion").toString(), (String) after.get("name"), objectMap(resolveValue(after.get("data"), localIds)), "AI", selectedGraph));
        }
        List<Map<String, Object>> removals = new ArrayList<>(selected.stream().filter(i -> i.get("operation").equals("DELETE")).toList());
        while (!removals.isEmpty()) {
            boolean progressed = false;
            for (var item : List.copyOf(removals)) {
                String targetId = item.get("targetId").toString();
                if (removals.stream().anyMatch(other -> !other.equals(item) && targetId.equals(other.get("parentId")))) continue;
                var dependencies = jdbc.queryForList("SELECT from_id FROM asset_relation WHERE to_id=?", String.class, targetId);
                if (removals.stream().anyMatch(other -> !other.equals(item) && dependencies.contains(other.get("targetId")))) continue;
                assets.delete(projectId, targetId, item.get("baseVersion").toString()); removals.remove(item); progressed = true;
            }
            if (!progressed) throw Problem.invalid("删除包含循环引用，未应用任何更改");
        }
        Map<String, Object> result = Map.of("status", "APPLIED", "assets", applied);
        jdbc.update("UPDATE ai_change_set SET status='APPLIED',selected_items=?,result=?,applied_at=? WHERE id=?", json.write(selection), json.write(result), Timestamp.from(Instant.now()), id);
        jdbc.update("UPDATE ai_message SET status='APPLIED' WHERE job_id=? AND role='assistant' AND status='PREVIEW'", row.get("job_id"));
        var pipelines = jdbc.queryForList("SELECT id,asset_ids FROM ai_pipeline_record WHERE project_id=? AND conversation_id=? FOR UPDATE", projectId, row.get("conversation_id"));
        for (var pipeline : pipelines) {
            Set<Object> tracked = new LinkedHashSet<>((List<?>) json.tree(pipeline.get("asset_ids").toString()));
            tracked.removeAll(deletedIds); applied.forEach(asset -> tracked.add(asset.id()));
            jdbc.update("UPDATE ai_pipeline_record SET asset_ids=?,updated_at=? WHERE id=?", json.write(tracked), Timestamp.from(Instant.now()), pipeline.get("id"));
        }
        return result;
    }
    @Transactional
    public void reject(String projectId, String id) {
        get(projectId, id);
        if (jdbc.update("UPDATE ai_change_set SET status='REJECTED' WHERE id=? AND project_id=? AND status='DRAFT'", id, projectId) != 1) throw Problem.conflict("此变更集已关闭");
        jdbc.update("UPDATE ai_message SET status='REJECTED' WHERE job_id=(SELECT job_id FROM ai_change_set WHERE id=?) AND role='assistant' AND status='PREVIEW'", id);
    }
    private static void validateProtectedFields(String operation, AssetType type, Map<String, Object> data) {
        if (data == null) return;
        String activation = type == AssetType.TEST_PLAN ? "scheduleEnabled" : type == AssetType.WEBHOOK ? "enabled" : null;
        if (activation != null && data.containsKey(activation) && (!operation.equals("ADD") || !Boolean.FALSE.equals(data.get(activation)))) throw new Problem(422, "AI_AUTOMATION_ACTIVATION_FORBIDDEN", "巡检和群通知开关需要人工设置，AI 只能生成未启用的草稿");
        if (type == AssetType.QUALITY_BRIEF && operation.equals("MODIFY") && data.keySet().stream().anyMatch(Set.of("metrics", "runId")::contains)) throw new Problem(422, "AI_REPORT_FACTS_FORBIDDEN", "报告反馈只能修改分析文字，不能改写统计快照或运行来源");
    }
    private Map<String, Asset> proposedGraph(String projectId, List<Proposal> proposals) {
        Map<String, Asset> graph = new LinkedHashMap<>(); repository.all(projectId, null, null).forEach(asset -> graph.put(asset.id(), asset));
        graph.put(projectId, assets.getInternal(projectId, projectId));
        int index = 0;
        for (Proposal proposal : proposals) {
            if (proposal.targetType() == null || proposal.operation() == null) throw Problem.invalid("变更操作或类型无效");
            switch (proposal.operation()) {
                case "DELETE" -> graph.remove(proposal.targetId());
                case "MODIFY" -> {
                    Asset current = assets.getInternal(projectId, proposal.targetId());
                    graph.put(current.id(), assets.prepare(current, proposal.name(), proposal.data(), null));
                }
                case "ADD" -> {
                    String id = proposal.localKey() == null ? "preview-" + index : "@" + proposal.localKey();
                    graph.put(id, new Asset(id, projectId, proposal.targetType(), proposal.parentId(), proposal.name(), "1", index, "AI", false, Instant.now(), Instant.now(), proposal.data() == null ? Map.of() : proposal.data()));
                }
                default -> throw Problem.invalid("变更操作无效");
            }
            index++;
        }
        return graph;
    }
    private void validateSelectedVariableDependencies(List<Map<String, Object>> all, List<Map<String, Object>> selected, Map<String, Asset> graph) {
        Set<String> promised = new HashSet<>(), available = new HashSet<>();
        for (var item : all) {
            if (item.get("operation").equals("DELETE")) continue;
            Asset before = item.get("before") == null ? null : json.convert(item.get("before"), Asset.class);
            Map<String, Object> data = new LinkedHashMap<>(before == null ? Map.of() : before.data());
            data.putAll(objectMap(objectMap(item.get("after")).get("data")));
            Asset after = new Asset("candidate", "preview", AssetType.valueOf(item.get("targetType").toString()), null, "candidate", "1", 0, "AI", false, Instant.EPOCH, Instant.EPOCH, data);
            Set<String> introduced = com.aitest.execution.ExecutionAssetPolicy.exported(after);
            if (before != null) introduced.removeAll(com.aitest.execution.ExecutionAssetPolicy.exported(before));
            promised.addAll(introduced);
        }
        for (Asset asset : graph.values()) {
            available.addAll(com.aitest.execution.ExecutionAssetPolicy.exported(asset));
            available.addAll(com.aitest.execution.Values.map(asset.data().get("variables")).keySet());
        }
        promised.removeAll(available);
        if (promised.isEmpty()) return;
        var resolver = new com.aitest.execution.VariableResolver(json);
        for (var item : selected) {
            if (item.get("operation").equals("DELETE")) continue;
            Asset candidate = graph.get(item.get("targetId"));
            Map<String, Object> data = new LinkedHashMap<>(candidate == null ? objectMap(objectMap(item.get("after")).get("data")) : candidate.data());
            data.remove("generationEvidence");
            Set<String> missing = resolver.references(data); missing.retainAll(promised);
            if (!missing.isEmpty()) throw new Problem(409, "MISSING_SELECTED_DEPENDENCY", "所选内容依赖未采纳的变量提供者，未应用任何修改", Map.of("changeItemId", item.get("id"), "variables", missing));
        }
    }
    private void validateNewDependencies(List<Proposal> proposals) {
        List<Proposal> pending = new ArrayList<>(proposals.stream().filter(p -> "ADD".equals(p.operation())).toList());
        Map<String, String> known = new HashMap<>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (Proposal proposal : List.copyOf(pending)) {
                if (!referencesReady(proposal.data(), known) || !referencesReady(proposal.parentId(), known)) continue;
                if (proposal.localKey() != null) known.put("@" + proposal.localKey(), "validated");
                pending.remove(proposal); progressed = true;
            }
            if (!progressed) throw Problem.invalid("新增资产包含循环引用或未知 localKey，无法生成可采纳的变更集");
        }
    }
    private boolean referencesReady(Object value, Map<String, String> known) {
        if (value instanceof String s) return !s.startsWith("@") || known.containsKey(s);
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .filter(e -> e.getKey().equals("data") || AssetReferences.FIELDS.contains(e.getKey()))
                .allMatch(e -> referencesReady(e.getValue(), known));
        return true;
    }
    private String resolveReference(String value, Map<String, String> known) { return value != null && value.startsWith("@") ? known.get(value) : value; }
    private Object resolveValue(Object value, Map<String, String> known) {
        if (value instanceof String s) return resolveReference(s, known);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(k.toString(), AssetReferences.FIELDS.contains(k) ? resolveValue(v, known) : v));
            return result;
        }
        return value;
    }
    @SuppressWarnings("unchecked") private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?>)) throw Problem.invalid("变更内容必须是 JSON 对象"); return (Map<String, Object>) value;
    }
    public record Proposal(String operation, AssetType targetType, String targetId, String parentId, String localKey, String baseVersion, String name, Map<String, Object> data) { }
    public record ApplyInput(String projectId, List<String> itemIds) { }
}
