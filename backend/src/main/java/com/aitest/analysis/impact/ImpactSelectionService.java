package com.aitest.analysis.impact;

import com.aitest.analysis.source.SourceFile;
import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.Values;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class ImpactSelectionService {
    private final SourceImpactRepository impacts; private final AssetRepository repository; private final AssetService assets; private final JsonCodec json;
    public ImpactSelectionService(SourceImpactRepository impacts, AssetRepository repository, AssetService assets, JsonCodec json) { this.impacts = impacts; this.repository = repository; this.assets = assets; this.json = json; }
    public record Request(List<String> assetIds, String name, String environmentId, String idempotencyKey) { }
    @Transactional public Map<String, Object> create(String project, String impactId, Request request) {
        SourceImpactService.key(request.idempotencyKey());
        if (request.assetIds() == null || request.assetIds().isEmpty() || request.assetIds().size() > 10000 || request.assetIds().stream().anyMatch(id -> id == null || id.isBlank()) || new HashSet<>(request.assetIds()).size() != request.assetIds().size()) throw Problem.invalid("请选择 1–10000 项不同的候选测试");
        String id = SourceImpactService.identity(project + ":regression:" + impactId + ":" + request.idempotencyKey()), hash = SourceFile.hash(json.write(request));
        repository.lockProject(project);
        var prior = repository.jdbc().queryForList("SELECT request_hash,plan_id FROM source_regression_submission WHERE id=? AND project_id=?", id, project);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("request_hash"))) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此回归计划幂等键已用于其他选择");
            return Map.of("planId", prior.getFirst().get("plan_id"), "impactId", impactId);
        }
        var report = impacts.get(project, impactId); var result = impacts.result(project, impactId);
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>(); Values.objects(result.get("candidates")).forEach(row -> candidates.put(row.get("assetId").toString(), row));
        if (!candidates.keySet().containsAll(request.assetIds())) throw Problem.invalid("所选记录不属于本次影响报告的候选测试");
        Map<String, Asset> current = new LinkedHashMap<>(); repository.all(project, null, null).forEach(asset -> current.put(asset.id(), asset));
        for (String selected : request.assetIds()) if (!Objects.equals(candidates.get(selected).get("dependencies"), ImpactCandidates.dependencies(selected, current)))
            throw new Problem(409, "IMPACT_CANDIDATE_CHANGED", "所选测试或其依赖已被修改，请重新分析当前资产后选择", Map.of("assetId", selected));
        String environment = Objects.toString(request.environmentId(), "");
        if (environment.isBlank()) {
            Set<String> inherited = new LinkedHashSet<>(); request.assetIds().forEach(selected -> { String value = Values.text(candidates.get(selected), "defaultEnvironmentId", ""); if (!value.isBlank()) inherited.add(value); });
            if (inherited.size() > 1) throw Problem.invalid("所选计划项来自不同环境，请指定回归执行环境");
            if (!inherited.isEmpty()) environment = inherited.iterator().next();
        }
        String name = request.name() == null || request.name().isBlank() ? "源码变更定向回归 " + impactId.substring(0, 8) : request.name();
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, name, Map.of("environmentId", environment, "sourceSnapshotId", report.get("sourceSnapshotId"), "impactId", impactId,
                "description", "依据固定源码影响报告，由人工选择 " + request.assetIds().size() + " 项测试"), "MANUAL");
        for (String selected : request.assetIds()) {
            Asset asset = current.get(selected); Map<String, Object> data = new LinkedHashMap<>();
            if (asset.type() == AssetType.PLAN_ITEM) {
                data.putAll(asset.data());
                if (Values.text(data, "datasetId", "").isBlank()) data.put("datasetId", Values.text(current.get(asset.parentId()).data(), "datasetId", ""));
            } else data.put("targetId", asset.id());
            assets.create(project, AssetType.PLAN_ITEM, plan.id(), asset.name(), data, "MANUAL");
        }
        repository.jdbc().update("INSERT INTO source_regression_submission(id,project_id,impact_id,request_hash,plan_id,created_at) VALUES(?,?,?,?,?,?)", id, project, impactId, hash, plan.id(), Timestamp.from(Instant.now()));
        return Map.of("planId", plan.id(), "impactId", impactId);
    }
}
