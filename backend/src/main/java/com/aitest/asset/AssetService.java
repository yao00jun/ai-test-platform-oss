package com.aitest.asset;

import com.aitest.common.Ids;
import com.aitest.common.Problem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssetService {
    private final AssetRepository repository;
    private final AssetValidator validator;
    private final AssetSecrets secrets;
    private final List<AssetPolicy> policies;
    private final List<AssetObserver> observers;
    public AssetService(AssetRepository repository, AssetValidator validator, AssetSecrets secrets, List<AssetPolicy> policies, List<AssetObserver> observers) {
        this.repository = repository; this.validator = validator; this.secrets = secrets; this.policies = policies; this.observers = observers;
    }

    @Transactional
    public Asset createProject(String name, Map<String, Object> data) {
        Map<String, Object> validated = validator.validate(AssetType.PROJECT, name, data);
        Instant now = now(); String id = Ids.newId();
        Asset project = new Asset(id, id, AssetType.PROJECT, null, name.strip(), "1", repository.projects().size(), "MANUAL", false, now, now, validated);
        repository.insertProject(project); repository.revise(project, "CREATE", "MANUAL");
        return project;
    }
    public List<Asset> projects() { return repository.projects(); }
    public Asset get(String projectId, String id) { return secrets.redact(getInternal(projectId, id)); }
    public Asset getInternal(String projectId, String id) { repository.project(projectId); return repository.find(projectId, id); }
    public List<Asset> all(String projectId) { repository.project(projectId); return repository.all(projectId, null, null).stream().map(secrets::redact).toList(); }
    public void validateCreation(String projectId, AssetType type, String parentId, String name, Map<String, Object> data, Map<String, AssetType> localTypes) {
        creationValidation(projectId, localTypes).validate(type, parentId, name, data);
    }
    /** A validation scope belongs to one preflight invocation, never an AI adoption or shared cache. */
    public CreationValidation creationValidation(String projectId, Map<String, AssetType> localTypes) {
        repository.project(projectId);
        return new CreationValidation(projectId, localTypes);
    }
    public final class CreationValidation {
        private final String projectId;
        private final Map<String, AssetType> types;
        private CreationValidation(String projectId, Map<String, AssetType> localTypes) {
            this.projectId = projectId; types = new LinkedHashMap<>(localTypes);
        }
        private AssetType typeOf(String id) {
            return types.computeIfAbsent(id, key -> repository.find(projectId, key).type());
        }
        public void validate(AssetType type, String parentId, String name, Map<String, Object> data) {
            if (type.requiresParent() && (parentId == null || parentId.isBlank())) throw Problem.invalid(type.label() + "需要父记录");
            if (parentId != null && !parentId.isBlank() && !typeOf(parentId).childTypes().contains(type))
                throw Problem.invalid("父记录不接受此类型");
            Asset candidate = new Asset(Ids.newId(), projectId, type, parentId, name, "1", 0, "AI", false, now(), now(), validator.validate(type, name, data));
            references(candidate).forEach((field, allowed) -> {
                Object value = candidate.data().get(field);
                if (value == null || value.toString().isBlank()) return;
                if (!allowed.contains(typeOf(value.toString()))) throw Problem.invalid(field + " 引用类型不兼容");
            });
            policies.forEach(policy -> policy.validate(null, candidate));
        }
    }
    public AssetPage list(String projectId, AssetType type, String parentId, String query, int offset, int limit) {
        repository.project(projectId);
        if (type == AssetType.PROJECT) return new AssetPage(List.of(repository.project(projectId)), 1);
        if (offset < 0 || limit < 1 || limit > 10000) throw Problem.invalid("分页范围无效，limit 为 1–10000");
        AssetPage page = repository.list(projectId, type, parentId, query, offset, limit);
        return new AssetPage(page.items().stream().map(secrets::redact).toList(), page.total());
    }
    public List<Asset> children(String projectId, String id) { get(projectId, id); return repository.all(projectId, null, id).stream().map(secrets::redact).toList(); }
    public List<Asset> recent(String projectId, AssetType type, int limit) {
        repository.project(projectId);
        if (type == AssetType.PROJECT || limit < 1 || limit > 200) throw Problem.invalid("最近资产查询范围无效");
        return repository.recent(projectId, type, limit).stream().map(secrets::redact).toList();
    }

    @Transactional
    public Asset create(String projectId, AssetType type, String parentId, String name, Map<String, Object> data, String source) {
        if (type == AssetType.PROJECT) throw Problem.invalid("请使用项目创建入口");
        repository.lockProject(projectId);
        if (parentId != null && parentId.isBlank()) parentId = null;
        if (type.requiresParent() && parentId == null) throw Problem.invalid(type.label() + "需要所属父记录");
        if (parentId != null && !repository.find(projectId, parentId).type().childTypes().contains(type)) throw Problem.invalid("父记录不接受此类型的子记录");
        Map<String, Object> checked = validator.validate(type, name, data);
        Instant now = now();
        Asset candidate = new Asset(Ids.newId(), projectId, type, parentId, name.strip(), "1", repository.nextPosition(projectId, type, parentId), actor(source), false, now, now, checked);
        validateReferences(candidate);
        policies.forEach(p -> p.validate(null, candidate));
        repository.insert(candidate); syncRelations(candidate); repository.revise(candidate, "CREATE", source);
        observers.forEach(observer -> observer.changed(null, candidate));
        return secrets.redact(candidate);
    }

    /** Internal creation inputs use server-generated IDs and dependency order. */
    public record Creation(String id, AssetType type, String parentId, String name, Map<String, Object> data) { }
    private record CreationScope(AssetType type, String parentId) { }

    @Transactional
    public List<Asset> createBatch(String projectId, List<Creation> inputs, String source) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 20000) throw Problem.invalid("批量创建要求 1–20000 条资产");
        repository.lockProject(projectId); String origin = actor(source);
        Set<String> ids = new HashSet<>();
        for (Creation input : inputs) {
            if (input.id() == null || !input.id().matches("[a-f0-9]{32}") || input.id().equals(projectId) || !ids.add(input.id()))
                throw Problem.invalid("批量资产 ID 必须由服务端生成且唯一");
            if (input.type() == null || input.type() == AssetType.PROJECT) throw Problem.invalid("请使用项目创建入口");
        }
        // These lookups exist only within this project-locked transaction; they
        // are never reused by editing or AI adoption transactions.
        Map<String, Asset> known = new LinkedHashMap<>();
        Map<CreationScope, Integer> positions = new LinkedHashMap<>();
        List<Asset> created = new ArrayList<>();
        for (Creation input : inputs) {
            String parent = input.parentId() == null || input.parentId().isBlank() ? null : input.parentId();
            if (input.type().requiresParent() && parent == null) throw Problem.invalid(input.type().label() + "需要所属父记录");
            if (input.id().equals(parent)) throw Problem.invalid("资产不能以自身作为父记录");
            if (parent != null && !known.computeIfAbsent(parent, key -> repository.find(projectId, key)).type().childTypes().contains(input.type()))
                throw Problem.invalid("父记录不接受此类型的子记录");
            var scope = new CreationScope(input.type(), parent);
            int position = positions.compute(scope, (key, previous) -> previous == null
                    ? parent != null && ids.contains(parent) ? 0 : repository.nextPosition(projectId, input.type(), parent)
                    : Math.addExact(previous, 1));
            Instant now = now();
            var checked = validator.validate(input.type(), input.name(), input.data());
            var candidate = new Asset(input.id(), projectId, input.type(), parent, input.name().strip(), "1", position, origin, false, now, now,
                    checked);
            for (String field : references(candidate).keySet()) {
                Object target = candidate.data().get(field);
                if (target != null && !target.toString().isBlank() && !target.equals(candidate.id()))
                    known.computeIfAbsent(target.toString(), key -> repository.find(projectId, key));
            }
            validateReferences(candidate, known);
            policies.forEach(policy -> policy.validate(null, candidate));
            known.put(candidate.id(), candidate); created.add(candidate);
        }
        repository.insertBatch(created);
        List<Object[]> links = new ArrayList<>();
        for (Asset candidate : created) references(candidate).forEach((field, allowed) -> {
            Object target = candidate.data().get(field);
            if (target != null && !target.toString().isBlank()) links.add(new Object[]{candidate.id(), target.toString(), field});
        });
        if (!links.isEmpty()) repository.jdbc().batchUpdate("INSERT INTO asset_relation(from_id,to_id,relation_type) VALUES(?,?,?)", links, 250,
                (statement, link) -> { for (int index = 0; index < link.length; index++) statement.setObject(index + 1, link[index]); });
        repository.reviseBatch(created, "CREATE", origin);
        // Observer writes stay in this transaction. Any rejection rolls back
        // every JDBC chunk, revision, relation and the enclosing import receipt.
        created.forEach(candidate -> observers.forEach(observer -> observer.changed(null, candidate)));
        return created.stream().map(secrets::redact).toList();
    }

    @Transactional
    public Asset update(String projectId, String id, String baseVersion, String name, Map<String, Object> patch, Boolean confirmed, String source) {
        return change(projectId, id, baseVersion, name, patch, confirmed, source, "UPDATE");
    }
    @Transactional
    public Asset updateInGraph(String projectId, String id, String baseVersion, String name, Map<String, Object> patch, String source, Map<String, Asset> proposedGraph) {
        return changeInGraph(projectId, id, baseVersion, name, patch, null, source, "UPDATE", proposedGraph);
    }
    private Asset change(String projectId, String id, String baseVersion, String name, Map<String, Object> patch, Boolean confirmed, String source, String operation) {
        return changeInGraph(projectId, id, baseVersion, name, patch, confirmed, source, operation, null);
    }
    private Asset changeInGraph(String projectId, String id, String baseVersion, String name, Map<String, Object> patch, Boolean confirmed, String source, String operation, Map<String, Asset> proposedGraph) {
        repository.lockProject(projectId);
        Asset previous = repository.find(projectId, id);
        requireVersion(previous, baseVersion);
        Asset candidate = prepare(previous, name, patch, confirmed);
        validateCandidate(previous, candidate, proposedGraph);
        repository.update(candidate, baseVersion);
        if (candidate.type() != AssetType.PROJECT) syncRelations(candidate);
        repository.revise(candidate, operation, actor(source));
        observers.forEach(observer -> observer.changed(previous, candidate));
        return secrets.redact(candidate);
    }
    public Asset prepare(Asset previous, String name, Map<String, Object> patch, Boolean confirmed) {
        Map<String, Object> merged = new LinkedHashMap<>(previous.data());
        if (patch != null) patch.forEach((key, value) -> merged.put(key, secrets.preserveMasks(value, previous.data().get(key))));
        String candidateName = name == null ? previous.name() : name.strip();
        Map<String, Object> checked = validator.validate(previous.type(), candidateName, merged);
        return new Asset(previous.id(), previous.projectId(), previous.type(), previous.parentId(), candidateName,
                String.valueOf(Long.parseLong(previous.version()) + 1), previous.position(), previous.source(),
                confirmed == null ? previous.confirmed() : confirmed, previous.createdAt(), now(), checked);
    }
    public void validateCandidate(Asset previous, Asset candidate) {
        validateCandidate(previous, candidate, null);
    }
    public void validateCandidate(Asset previous, Asset candidate, Map<String, Asset> proposedGraph) {
        validator.validate(candidate.type(), candidate.name(), candidate.data());
        validateReferences(candidate, proposedGraph);
        policies.forEach(p -> { if (proposedGraph == null) p.validate(previous, candidate); else p.validateInGraph(previous, candidate, proposedGraph); });
    }

    @Transactional
    public void delete(String projectId, String id, String baseVersion) {
        repository.lockProject(projectId);
        Asset root = repository.find(projectId, id); requireVersion(root, baseVersion);
        if (root.type() == AssetType.PROJECT) {
            repository.jdbc().update("UPDATE project SET deleted=TRUE,version=version+1,updated_at=? WHERE id=? AND version=?", Timestamp.from(now()), id, Long.parseLong(baseVersion));
            Asset deleted = new Asset(root.id(), root.projectId(), root.type(), null, root.name(), String.valueOf(Long.parseLong(baseVersion) + 1), root.position(), root.source(), root.confirmed(), root.createdAt(), now(), root.data());
            repository.revise(deleted, "DELETE", "MANUAL");
            observers.forEach(observer -> observer.deleted(deleted));
            return;
        }
        List<Asset> subtree = new ArrayList<>(); collect(root, subtree);
        Set<String> ids = new HashSet<>(subtree.stream().map(Asset::id).toList());
        for (Asset asset : subtree) {
            List<String> incoming = repository.jdbc().queryForList("SELECT r.from_id FROM asset_relation r JOIN asset a ON a.id=r.from_id WHERE r.to_id=? AND a.deleted=FALSE", String.class, asset.id());
            List<String> outside = incoming.stream().filter(ref -> !ids.contains(ref)).toList();
            if (!outside.isEmpty()) throw new Problem(409, "REFERENCE_CONFLICT", "资产仍被其他记录引用，请先处理引用", Map.of("targetId", asset.id(), "references", outside));
        }
        for (Asset asset : subtree.reversed()) {
            repository.jdbc().update("UPDATE asset SET deleted=TRUE,version=version+1,updated_at=? WHERE id=?", Timestamp.from(now()), asset.id());
            repository.jdbc().update("DELETE FROM asset_relation WHERE from_id=?", asset.id());
            Asset deleted = new Asset(asset.id(), asset.projectId(), asset.type(), asset.parentId(), asset.name(), String.valueOf(Long.parseLong(asset.version()) + 1), asset.position(), asset.source(), asset.confirmed(), asset.createdAt(), now(), asset.data());
            repository.revise(deleted, "DELETE", "MANUAL");
            observers.forEach(observer -> observer.deleted(deleted));
        }
    }
    private void collect(Asset parent, List<Asset> result) {
        java.util.ArrayDeque<Asset> pending = new java.util.ArrayDeque<>(); pending.add(parent);
        while (!pending.isEmpty()) {
            Asset current = pending.removeFirst(); result.add(current);
            if (!current.type().childTypes().isEmpty()) pending.addAll(repository.all(current.projectId(), null, current.id()));
        }
    }

    public List<Revision> history(String projectId, String id) {
        getInternal(projectId, id);
        return repository.history(projectId, id).stream().map(r -> new Revision(r.id(), r.assetId(), r.version(), r.operation(), r.source(), r.createdAt(), secrets.redact(r.snapshot()))).toList();
    }
    @Transactional
    public Asset undo(String projectId, String id, String baseVersion, String revisionId) {
        repository.lockProject(projectId);
        Revision revision = repository.history(projectId, id).stream().filter(r -> r.id().equals(revisionId)).findFirst().orElseThrow(Problem::missing);
        Asset old = revision.snapshot();
        return change(projectId, id, baseVersion, old.name(), old.data(), old.confirmed(), "MANUAL", "UNDO");
    }
    @Transactional
    public List<Asset> reorder(String projectId, AssetType type, String parentId, List<OrderItem> order) {
        repository.lockProject(projectId);
        String scope = parentId == null || parentId.isBlank() ? "ROOT" : parentId;
        List<Asset> current = repository.all(projectId, type, scope);
        if (order.size() != current.size() || order.stream().map(OrderItem::id).distinct().count() != order.size()
                || !new HashSet<>(order.stream().map(OrderItem::id).toList()).equals(new HashSet<>(current.stream().map(Asset::id).toList()))) throw Problem.conflict("排序范围已变化，请重新载入完整列表");
        Map<String, Asset> byId = new LinkedHashMap<>(); current.forEach(a -> byId.put(a.id(), a));
        for (OrderItem item : order) requireVersion(byId.get(item.id()), item.baseVersion());
        for (int i = 0; i < order.size(); i++) {
            Asset a = byId.get(order.get(i).id());
            if (a.position() == i) continue;
            Instant now = now();
            repository.jdbc().update("UPDATE asset SET position=?,version=version+1,updated_at=? WHERE id=? AND version=?", i, Timestamp.from(now), a.id(), Long.parseLong(a.version()));
            Asset changed = new Asset(a.id(), a.projectId(), a.type(), a.parentId(), a.name(), String.valueOf(Long.parseLong(a.version()) + 1), i, a.source(), a.confirmed(), a.createdAt(), now, a.data());
            repository.revise(changed, "REORDER", "MANUAL");
            observers.forEach(observer -> observer.changed(a, changed));
        }
        return repository.all(projectId, type, scope).stream().map(secrets::redact).toList();
    }
    public record OrderItem(String id, String baseVersion) { }
    private Map<String, Set<AssetType>> references(Asset candidate) {
        Map<String, Set<AssetType>> refs = new LinkedHashMap<>();
        refs.put("environmentId", Set.of(AssetType.ENVIRONMENT)); refs.put("databaseSourceId", Set.of(AssetType.DATABASE_SOURCE));
        refs.put("apiDefinitionId", Set.of(AssetType.API_DEFINITION)); refs.put("requirementId", Set.of(AssetType.REQUIREMENT)); refs.put("datasetId", Set.of(AssetType.DATASET));
        refs.put("associatedCaseId", Set.of(AssetType.FUNCTIONAL_CASE, AssetType.API_CASE, AssetType.SCENARIO, AssetType.UI_SCENARIO, AssetType.SQL_VALIDATION));
        if (candidate.type() == AssetType.PLAN_ITEM) refs.put("targetId", Set.of(AssetType.FUNCTIONAL_CASE, AssetType.API_CASE, AssetType.SCENARIO, AssetType.UI_SCENARIO, AssetType.SQL_VALIDATION));
        if (candidate.type() == AssetType.SCENARIO_STEP) {
            Set<AssetType> allowed = switch (String.valueOf(candidate.data().get("stepType"))) {
                case "HTTP" -> Set.of(AssetType.API_CASE);
                case "SQL" -> Set.of(AssetType.SQL_VALIDATION);
                case "WEB" -> Set.of(AssetType.UI_SCENARIO);
                default -> Set.of();
            };
            refs.put("targetId", allowed);
            Object targetId = candidate.data().get("targetId");
            if (!allowed.isEmpty() && (targetId == null || targetId.toString().isBlank())) throw Problem.invalid("此步骤需要引用测试资产");
        }
        return refs;
    }
    private void validateReferences(Asset candidate) {
        validateReferences(candidate, null);
    }
    private void validateReferences(Asset candidate, Map<String, Asset> proposedGraph) {
        references(candidate).forEach((field, allowed) -> {
            Object value = candidate.data().get(field);
            if (value == null || value.toString().isBlank()) return;
            if (value.equals(candidate.id())) throw Problem.invalid("资产不能引用自身");
            Asset target = proposedGraph == null ? repository.find(candidate.projectId(), value.toString()) : proposedGraph.get(value.toString());
            if (target == null || !candidate.projectId().equals(target.projectId())) throw Problem.invalid(field + " 引用了变更后不存在或不属于本项目的资产");
            if (!allowed.contains(target.type())) throw Problem.invalid(field + "引用了不兼容的资产类型");
        });
    }
    private void syncRelations(Asset candidate) {
        repository.jdbc().update("DELETE FROM asset_relation WHERE from_id=?", candidate.id());
        references(candidate).forEach((field, allowed) -> {
            Object value = candidate.data().get(field);
            if (value != null && !value.toString().isBlank()) repository.jdbc().update("INSERT INTO asset_relation(from_id,to_id,relation_type) VALUES(?,?,?)", candidate.id(), value.toString(), field);
        });
    }
    public static void requireVersion(Asset asset, String baseVersion) {
        if (baseVersion == null || !baseVersion.equals(asset.version())) throw new Problem(409, "VERSION_CONFLICT", "记录已更新，请重新载入", Map.of("targetId", asset.id(), "currentVersion", asset.version()));
    }
    private static String actor(String source) { if (!Set.of("MANUAL", "AI", "IMPORT").contains(source)) throw Problem.invalid("来源无效"); return source; }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
}
