package com.aitest.bug;

import com.aitest.asset.*;
import com.aitest.analysis.source.SourceFile;
import com.aitest.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Human judgements are immutable observations about a saved diagnosis, never writable AI asset fields. */
@Service
public class RcaEvaluationService {
    private final AssetRepository assets;
    private final JsonCodec json;
    public RcaEvaluationService(AssetRepository assets, JsonCodec json) { this.assets = assets; this.json = json; }
    public record Input(String baseVersion, String verdict, String regression, String note, String idempotencyKey) { }
    @Transactional
    public Map<String, Object> record(String project, String bug, Input input) {
        if (input == null || input.baseVersion() == null || input.verdict() == null || input.regression() == null
                || !Set.of("CORRECT", "PARTIAL", "INCORRECT", "UNREVIEWED").contains(input.verdict()) || !Set.of("CONFIRMED", "NOT_REGRESSION", "UNREVIEWED").contains(input.regression())) throw Problem.invalid("人工评价、退化确认和被评价版本不能为空且需使用合法选项");
        if (input.idempotencyKey() == null || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 120) throw Problem.invalid("人工评价需要 1–120 字符的幂等键");
        String note = Objects.toString(input.note(), ""); if (note.length() > 4000) throw Problem.invalid("评价备注不能超过 4000 字符");
        assets.lockProject(project); Asset current = bug(project, bug);
        String requestHash = SourceFile.hash(json.write(new TreeMap<>(Map.of("baseVersion", input.baseVersion(), "verdict", input.verdict(), "regression", input.regression(), "note", note))));
        var prior = assets.jdbc().queryForList("SELECT * FROM bug_rca_evaluation WHERE project_id=? AND bug_id=? AND idempotency_key=?", project, bug, input.idempotencyKey());
        if (!prior.isEmpty()) {
            if (!requestHash.equals(prior.getFirst().get("request_hash"))) throw Problem.conflict("此幂等键已经提交了不同的人工评价");
            return row(prior.getFirst());
        }
        AssetService.requireVersion(current, input.baseVersion());
        String id = Ids.newId(); Instant now = Instant.now();
        assets.jdbc().update("INSERT INTO bug_rca_evaluation(id,project_id,bug_id,asset_version,diagnosis_hash,verdict,regression,note,actor,source,idempotency_key,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, project, bug, Long.parseLong(current.version()), digest(current), input.verdict(), input.regression(), note, "LOCAL_USER", "MANUAL", input.idempotencyKey(), requestHash, Timestamp.from(now));
        return row(assets.jdbc().queryForMap("SELECT * FROM bug_rca_evaluation WHERE id=?", id));
    }
    @Transactional(readOnly = true)
    public Map<String, Object> list(String project, String bug, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw Problem.invalid("评价分页范围为 1–100，offset 不能为负");
        Asset target = bug(project, bug); String hash = digest(target);
        var current = assets.jdbc().queryForList("SELECT * FROM bug_rca_evaluation WHERE project_id=? AND bug_id=? AND diagnosis_hash=? ORDER BY created_at DESC,id DESC LIMIT 1", project, bug, hash);
        var items = assets.jdbc().queryForList("SELECT * FROM bug_rca_evaluation WHERE project_id=? AND bug_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", project, bug, limit, offset);
        Map<String, Object> result = new LinkedHashMap<>(); result.put("current", current.isEmpty() ? null : row(current.getFirst()));
        result.put("items", items.stream().map(this::row).toList()); result.put("total", assets.jdbc().queryForObject("SELECT COUNT(*) FROM bug_rca_evaluation WHERE project_id=? AND bug_id=?", Long.class, project, bug)); result.put("diagnosisHash", hash); return result;
    }
    public String digest(Asset bug) {
        Map<String, Object> content = new TreeMap<>();
        for (String key : List.of("rootCauseAnalysis", "suggestion", "codeDiagnosis")) content.put(key, canonical(bug.data().getOrDefault(key, key.equals("codeDiagnosis") ? Map.of() : "")));
        return SourceFile.hash(json.write(content));
    }
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> sorted = new TreeMap<>(); map.forEach((key, item) -> sorted.put(key.toString(), canonical(item))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }
    private Asset bug(String project, String id) { assets.project(project); Asset bug = assets.find(project, id); if (bug.type() != AssetType.BUG) throw Problem.invalid("目标不是缺陷"); return bug; }
    private Map<String, Object> row(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("bugId", row.get("bug_id")); result.put("assetVersion", row.get("asset_version").toString()); result.put("diagnosisHash", row.get("diagnosis_hash"));
        for (String key : List.of("verdict", "regression", "note", "actor", "source")) result.put(key, row.get(key));
        Object at = row.get("created_at"); result.put("createdAt", at instanceof Timestamp timestamp ? timestamp.toInstant() : ((LocalDateTime) at).toInstant(ZoneOffset.UTC)); return result;
    }
}
