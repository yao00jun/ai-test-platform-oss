package com.aitest.api.diff;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.exchange.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ApiDiffService {
    private final AssetRepository repository;
    private final AssetService assets;
    private final ExchangeService exchange;
    private final ExchangeRedactor redactor;
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final SecretProtector secrets;
    public ApiDiffService(AssetRepository repository, AssetService assets, ExchangeService exchange, ExchangeRedactor redactor, JdbcTemplate jdbc, JsonCodec json, SecretProtector secrets) {
        this.repository = repository; this.assets = assets; this.exchange = exchange; this.redactor = redactor; this.jdbc = jdbc; this.json = json; this.secrets = secrets;
    }
    public record PreviewInput(String importId, Map<String, String> definitionMappings) { }
    public record ApplyInput(List<String> itemIds, String idempotencyKey) { }
    record Stored(String id, String projectId, String importId, String filename, Instant createdAt, List<ApiDiffAnalyzer.Item> items,
                  Map<String, ApiDiffAnalyzer.Impact> originalImpacts) { }
    public record Accepted(String itemId, String assetId, String version, String operation) { }
    public record ItemView(String id, String kind, String match, String importKey, String definitionId, Asset before,
                           ApiDiffAnalyzer.Candidate candidate, List<String> matchCandidates, List<ApiDiffAnalyzer.FieldChange> fields,
                           List<String> affectedAssetIds, List<ApiDiffAnalyzer.Link> links, List<String> originalAffectedAssetIds,
                           List<ApiDiffAnalyzer.Link> originalLinks, String status, String appliedVersion, Asset current) { }
    public record View(String id, String projectId, String importId, String filename, String status, Instant createdAt, List<ItemView> items) { }

    @Transactional
    public View preview(String projectId, PreviewInput input) {
        repository.lockProject(projectId);
        if (input == null || input.importId() == null || input.importId().isBlank()) throw Problem.invalid("请选择已有 API 导入预览");
        ExchangeService.Pending pending = exchange.verifiedApiDefinitions(projectId, input.importId());
        var source = exchange.get(projectId, input.importId());
        List<Asset> all = repository.all(projectId, null, null);
        List<Asset> definitions = all.stream().filter(a -> a.type() == AssetType.API_DEFINITION).toList();
        var items = ApiDiffAnalyzer.match(definitions, pending.bundle().nodes(), pending.parentId(), input.definitionMappings() == null ? Map.of() : input.definitionMappings());
        Map<String, ApiDiffAnalyzer.Impact> impacts = new LinkedHashMap<>();
        items.forEach(item -> impacts.put(item.id(), ApiDiffAnalyzer.impact(item.definitionId() == null ? item.matchCandidates() : List.of(item.definitionId()), all)));
        Stored stored = new Stored(Ids.newId(), projectId, input.importId(), source.filename(), Instant.now(), items, impacts);
        jdbc.update("INSERT INTO api_document_diff(id,project_id,import_id,private_payload,accepted_items,created_at,updated_at) VALUES(?,?,?,?,'[]',?,?)", stored.id(), projectId, input.importId(), secrets.encrypt(json.write(stored)), Timestamp.from(stored.createdAt()), Timestamp.from(stored.createdAt()));
        return get(projectId, stored.id());
    }
    public Map<String, Object> list(String projectId, int offset, int limit) {
        repository.project(projectId);
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("分页范围无效，limit 为 1–100");
        var ids = jdbc.queryForList("SELECT id FROM api_document_diff WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", String.class, projectId, limit, offset);
        List<Map<String, Object>> items = ids.stream().map(id -> {
            var view = get(projectId, id);
            return Map.<String, Object>of("id", id, "importId", view.importId(), "filename", view.filename(), "status", view.status(), "createdAt", view.createdAt(), "changeCount", view.items().stream().filter(item -> !item.kind().equals("UNCHANGED")).count());
        }).toList();
        return Map.of("items", items, "total", Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM api_document_diff WHERE project_id=?", Long.class, projectId)));
    }
    public View get(String projectId, String id) {
        var row = row(projectId, id); Stored stored = stored(row);
        Map<String, Accepted> accepted = accepted(row);
        List<Asset> all = repository.all(projectId, null, null); Map<String, Asset> current = new LinkedHashMap<>(); redactor.assets(all).forEach(a -> current.put(a.id(), a));
        List<ItemView> items = stored.items().stream().map(item -> {
            Accepted applied = accepted.get(item.id());
            Asset before = item.before() == null ? null : redactor.asset(item.before());
            var candidate = item.candidate();
            if (candidate != null) {
                var bundle = new ExchangeBundle(ExchangeBundle.VERSION, Map.of(), List.of(new ExchangeNode(candidate.key(), AssetType.API_DEFINITION, null, candidate.name(), 0, candidate.data(), Map.of())), Map.of(), List.of());
                candidate = new ApiDiffAnalyzer.Candidate(candidate.key(), candidate.name(), candidate.parentId(), redactor.bundle(bundle).nodes().getFirst().data());
            }
            var fields = before != null && candidate != null ? ApiDiffAnalyzer.fields(before.name(), before.data(), candidate.name(), candidate.data()) : List.<ApiDiffAnalyzer.FieldChange>of();
            var impact = ApiDiffAnalyzer.impact(item.definitionId() == null ? applied == null ? List.of() : List.of(applied.assetId()) : List.of(item.definitionId()), all);
            var original = stored.originalImpacts() == null ? new ApiDiffAnalyzer.Impact(List.of(), List.of()) : stored.originalImpacts().getOrDefault(item.id(), new ApiDiffAnalyzer.Impact(List.of(), List.of()));
            return new ItemView(item.id(), item.kind(), item.match(), item.importKey(), item.definitionId(), before, candidate, item.matchCandidates(), fields, impact.assetIds(), impact.links(), original.assetIds(), original.links(), applied == null ? "PENDING" : "ACCEPTED", applied == null ? null : applied.version(), current.get(applied == null ? item.definitionId() : applied.assetId()));
        }).toList();
        long pending = items.stream().filter(i -> !i.kind().equals("UNCHANGED") && !i.status().equals("ACCEPTED")).count();
        String status = accepted.isEmpty() ? pending == 0 ? "UNCHANGED" : "READY" : pending == 0 ? "APPLIED" : "PARTIAL";
        return new View(id, projectId, stored.importId(), stored.filename(), status, stored.createdAt(), items);
    }
    @Transactional
    public Map<String, Object> apply(String projectId, String id, ApplyInput input) {
        repository.lockProject(projectId);
        var row = row(projectId, id); Stored stored = stored(row);
        if (input == null || input.itemIds() == null || input.itemIds().isEmpty() || input.itemIds().size() > 20000 || new HashSet<>(input.itemIds()).size() != input.itemIds().size() || input.itemIds().stream().anyMatch(Objects::isNull)) throw Problem.invalid("请选择唯一的接口变更项");
        if (input.idempotencyKey() == null || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 160) throw Problem.invalid("需要有效的 idempotencyKey");
        Set<String> selected = new TreeSet<>(input.itemIds());
        var prior = jdbc.queryForList("SELECT * FROM api_diff_application WHERE project_id=? AND idempotency_key=?", projectId, input.idempotencyKey());
        if (!prior.isEmpty()) {
            var existing = prior.getFirst();
            if (!id.equals(existing.get("diff_id")) || !new TreeSet<>(List.of(json.read(existing.get("selected_items").toString(), String[].class))).equals(selected)) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同的接口变更选择");
            return json.map(existing.get("result").toString());
        }
        var items = stored.items().stream().filter(item -> selected.contains(item.id())).toList();
        if (items.size() != selected.size()) throw Problem.invalid("变更项不属于当前对比");
        var accepted = accepted(row); List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Asset> definitions = repository.all(projectId, AssetType.API_DEFINITION, null);
        for (var item : items) {
            if (!Set.of("CHANGED", "ADDED", "REMOVED").contains(item.kind())) throw Problem.invalid("未变化或身份不明确的接口不能直接采纳，请先完成映射");
            if (accepted.containsKey(item.id())) throw Problem.conflict("该项已经采纳，请刷新对比结果");
            if (item.before() != null) {
                try { AssetService.requireVersion(assets.get(projectId, item.definitionId()), item.before().version()); }
                catch (Problem conflict) { if (conflict.status() != 404 && conflict.status() != 409) throw conflict; conflicts.add(Map.of("targetId", item.definitionId(), "baseVersion", item.before().version())); }
            } else for (Asset current : definitions) {
                if (ApiDiffAnalyzer.endpoint(current.data()).equals(ApiDiffAnalyzer.endpoint(item.candidate().data())) || !ApiDiffAnalyzer.operationId(item.candidate().data()).isBlank() && ApiDiffAnalyzer.operationId(current.data()).equals(ApiDiffAnalyzer.operationId(item.candidate().data())))
                    conflicts.add(Map.of("targetId", current.id(), "message", "预览后出现同身份接口，请重新对比或明确映射"));
            }
        }
        if (!conflicts.isEmpty()) throw new Problem(409, "API_DIFF_CONFLICT", "接口定义已变化，没有采纳任何选定项", conflicts);
        List<Asset> updated = new ArrayList<>(); List<String> removed = new ArrayList<>();
        for (var item : items) {
            Asset result;
            if (item.kind().equals("REMOVED")) {
                assets.delete(projectId, item.definitionId(), item.before().version()); removed.add(item.definitionId());
                accepted.put(item.id(), new Accepted(item.id(), item.definitionId(), String.valueOf(Long.parseLong(item.before().version()) + 1), "REMOVED"));
                continue;
            }
            var candidate = item.candidate();
            if (item.kind().equals("ADDED")) result = assets.create(projectId, AssetType.API_DEFINITION, candidate.parentId(), candidate.name(), candidate.data(), "IMPORT");
            else result = assets.update(projectId, item.definitionId(), item.before().version(), candidate.name(), candidate.data(), null, "IMPORT");
            updated.add(redactor.asset(repository.find(projectId, result.id())));
            accepted.put(item.id(), new Accepted(item.id(), result.id(), result.version(), item.kind()));
        }
        Map<String, Object> result = json.map(json.write(Map.of("diffId", id, "assets", updated, "removedIds", removed, "acceptedItemIds", selected)));
        Instant now = Instant.now();
        jdbc.update("UPDATE api_document_diff SET accepted_items=?,updated_at=? WHERE id=?", json.write(accepted.values()), Timestamp.from(now), id);
        jdbc.update("INSERT INTO api_diff_application(project_id,idempotency_key,diff_id,selected_items,result,created_at) VALUES(?,?,?,?,?,?)", projectId, input.idempotencyKey(), id, json.write(selected), json.write(result), Timestamp.from(now));
        return result;
    }
    private Map<String, Object> row(String projectId, String id) {
        repository.project(projectId);
        var rows = jdbc.queryForList("SELECT * FROM api_document_diff WHERE project_id=? AND id=?", projectId, id);
        if (rows.isEmpty()) throw Problem.missing(); return rows.getFirst();
    }
    private Stored stored(Map<String, Object> row) { return json.read(secrets.decrypt(row.get("private_payload").toString()), Stored.class); }
    private Map<String, Accepted> accepted(Map<String, Object> row) { Map<String, Accepted> result = new LinkedHashMap<>(); for (var a : json.read(row.get("accepted_items").toString(), Accepted[].class)) result.put(a.itemId(), a); return result; }
}
