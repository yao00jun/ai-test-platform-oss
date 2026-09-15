package com.aitest.ai.pipeline;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reconciles generated plan membership without rewriting human choices or saved runs. */
@Service
public final class PipelinePlanService {
    private static final Set<AssetType> ROOTS = Set.of(AssetType.FUNCTIONAL_CASE, AssetType.API_CASE, AssetType.SCENARIO, AssetType.UI_SCENARIO);
    private static final Set<AssetType> GENERATION = Set.of(AssetType.FUNCTIONAL_CASE, AssetType.FUNCTIONAL_STEP, AssetType.API_CASE, AssetType.SCENARIO, AssetType.SCENARIO_STEP, AssetType.SQL_VALIDATION, AssetType.UI_SCENARIO, AssetType.UI_STEP);
    private final AssetService assets;
    private final RunRepository runs;
    private final JdbcTemplate jdbc;
    public PipelinePlanService(AssetService assets, RunRepository runs, JdbcTemplate jdbc) { this.assets = assets; this.runs = runs; this.jdbc = jdbc; }

    public boolean needsRefresh(String project, Map<String, Object> pipeline, Map<String, Object> output) {
        List<Asset> generated = generated(project, pipeline);
        Set<String> inspected = inspected(project, generated, output);
        return generated.stream().anyMatch(asset -> !inspected.contains(asset.id()));
    }

    /** The caller holds the active pipeline's project lock inside its job transaction. */
    public Reconciliation reconcile(String project, Map<String, Object> pipeline, Map<String, Object> output, String environment) {
        List<Asset> generated = generated(project, pipeline);
        Set<String> referenced = new HashSet<>();
        generated.stream().filter(asset -> asset.type() == AssetType.SCENARIO_STEP).forEach(step -> referenced.add(Values.text(step.data(), "targetId", "")));
        List<Asset> targets = generated.stream().filter(asset -> ROOTS.contains(asset.type()) && !referenced.contains(asset.id())).toList();
        if (targets.isEmpty()) throw new Problem(422, "GENERATION_BLOCKED", "没有可加入测试计划的生成资产");

        Set<String> planned = new LinkedHashSet<>(output.containsKey("plannedAssetIds") ? strings(output.get("plannedAssetIds")) : inspected(project, generated, output));
        List<String> added = new ArrayList<>();
        String planId = Values.text(output, "planId", "");
        Asset plan;
        if (planId.isBlank()) {
            plan = assets.create(project, AssetType.TEST_PLAN, null, "流水线测试计划 " + pipeline.get("id").toString().substring(0, 8), Map.of("environmentId", environment, "sourceSnapshotId", Values.text(Values.map(pipeline.get("config")), "sourceSnapshotId", ""), "diagnoseFailures", false, "description", "由流水线生成；人工项等待实际录入结果"), "AI");
            planId = plan.id(); output.put("planId", planId); added.add(planId); planned.clear();
        } else {
            try { plan = assets.get(project, planId); }
            catch (Problem missing) {
                if (missing.status() != 404) throw missing;
                output.put("pendingPlanAssetIds", targets.stream().map(Asset::id).toList());
                return new Reconciliation(null, List.of(), targets, added, "原测试计划已删除；请明确恢复计划后再继续，已有运行保持不变");
            }
            if (plan.type() != AssetType.TEST_PLAN) throw Problem.invalid("流水线保存的计划类型无效");
        }
        List<Asset> items = assets.children(project, planId);
        if (!currentPlanView(project, plan, items)) {
            List<Asset> pending = pending(project, targets, generated, runIds(output));
            Set<String> unverified = new LinkedHashSet<>();
            targets.stream().filter(target -> !planned.contains(target.id())).forEach(target -> unverified.add(target.id()));
            pending.forEach(target -> unverified.add(target.id()));
            output.put("pendingPlanAssetIds", List.copyOf(unverified));
            output.put("pendingAssetIds", pending.stream().map(Asset::id).toList());
            return new Reconciliation(plan, items, pending, added, "测试计划或计划项已被修改；请重新载入并重试执行阶段，已有运行保持不变");
        }
        Set<String> present = new HashSet<>(); items.forEach(item -> present.add(Values.text(item.data(), "targetId", "")));
        List<String> pendingPlan = new ArrayList<>();
        for (Asset target : targets) {
            if (planned.contains(target.id())) continue; // Includes deliberately removed/retargeted items.
            if (!present.contains(target.id())) {
                if (plan.confirmed()) { pendingPlan.add(target.id()); continue; }
                added.add(assets.create(project, AssetType.PLAN_ITEM, planId, target.name(), Map.of("targetId", target.id(), "executionMode", target.type() == AssetType.FUNCTIONAL_CASE ? "MANUAL" : "AUTO"), "AI").id());
                present.add(target.id());
            }
            planned.add(target.id());
        }
        output.put("plannedAssetIds", List.copyOf(planned));
        output.put("planSourceAssetIds", generated.stream().map(Asset::id).toList());
        output.put("pendingPlanAssetIds", pendingPlan);
        List<String> runIds = runIds(output); output.put("runIds", runIds);
        List<Asset> pending = pending(project, targets, generated, runIds);
        output.put("pendingAssetIds", pending.stream().map(Asset::id).toList());
        String blocked = !pendingPlan.isEmpty() ? "测试计划已人工保护；新增资产尚未纳入，请明确调整计划后重试" : null;
        List<String> removed = pending.stream().filter(target -> !present.contains(target.id())).map(Asset::id).toList();
        if (blocked == null && !removed.isEmpty()) {
            output.put("pendingPlanAssetIds", removed);
            blocked = "待执行资产已从计划移除；保留人工修改，请明确调整计划后重试";
        }
        return new Reconciliation(plan, assets.children(project, planId), pending, added, blocked);
    }

    private boolean currentPlanView(String project, Asset plan, List<Asset> items) {
        // The job checkpoint can establish an RR snapshot before the project lock.
        // Every supported plan/item edit advances its metadata version. Check the
        // complete current membership and versions before using any snapshot data.
        Map<String, String> expected = new HashMap<>(); expected.put(plan.id(), plan.version());
        items.forEach(item -> expected.put(item.id(), item.version()));
        Map<String, String> current = new HashMap<>();
        jdbc.queryForList("SELECT id,version FROM asset WHERE project_id=? AND deleted=FALSE AND (id=? OR parent_id=?) FOR UPDATE", project, plan.id(), plan.id())
                .forEach(row -> current.put(row.get("id").toString(), row.get("version").toString()));
        return expected.equals(current);
    }

    /** A fresh, explicit S6 request executes only outstanding targets and their current overrides. */
    public Continuation continuation(String project, Reconciliation current) {
        Set<String> targets = new LinkedHashSet<>(); current.pending().forEach(target -> targets.add(target.id()));
        Map<String, Object> data = new LinkedHashMap<>(current.plan().data()); data.put("diagnoseFailures", false);
        String suffix = " · 补充执行", name = current.plan().name();
        Asset plan = assets.create(project, AssetType.TEST_PLAN, null, name.substring(0, Math.min(name.length(), 255 - suffix.length())) + suffix, data, "AI");
        List<String> added = new ArrayList<>(List.of(plan.id()));
        for (Asset item : current.items()) if (targets.contains(item.data().get("targetId")))
            added.add(assets.create(project, AssetType.PLAN_ITEM, plan.id(), item.name(), item.data(), "AI").id());
        return new Continuation(plan.id(), added);
    }

    public List<String> runIds(Map<String, Object> output) {
        Set<String> ids = new LinkedHashSet<>(strings(output.get("runIds")));
        String latest = Values.text(output, "runId", ""); if (!latest.isBlank()) ids.add(latest);
        return List.copyOf(ids);
    }

    private List<Asset> generated(String project, Map<String, Object> pipeline) {
        List<Asset> result = new ArrayList<>();
        for (String id : strings(pipeline.get("assetIds"))) try {
            Asset asset = assets.get(project, id); if (GENERATION.contains(asset.type())) result.add(asset);
        } catch (Problem missing) { if (missing.status() != 404) throw missing; }
        return result;
    }

    private Set<String> inspected(String project, List<Asset> generated, Map<String, Object> output) {
        if (output.containsKey("planSourceAssetIds")) return new LinkedHashSet<>(strings(output.get("planSourceAssetIds")));
        String planId = Values.text(output, "planId", "");
        if (planId.isBlank()) return Set.of();
        try {
            // Legacy attempts predate the manifest field. Creation time retains membership
            // intent even if a human has since deleted or retargeted an original plan item.
            Asset plan = assets.get(project, planId); Set<String> ids = new LinkedHashSet<>();
            generated.stream().filter(asset -> !asset.createdAt().isAfter(plan.createdAt())).forEach(asset -> ids.add(asset.id()));
            return ids;
        } catch (Problem missing) { if (missing.status() != 404) throw missing; return Set.of(); }
    }

    private List<Asset> pending(String project, List<Asset> targets, List<Asset> generated, List<String> runIds) {
        if (runIds.isEmpty()) return List.of();
        List<RunDefinition> saved = runIds.stream().map(id -> runs.definition(project, id)).toList();
        Map<String, Asset> byId = new HashMap<>(); Map<String, List<Asset>> children = new HashMap<>();
        for (Asset asset : generated) { byId.put(asset.id(), asset); if (asset.parentId() != null) children.computeIfAbsent(asset.parentId(), ignored -> new ArrayList<>()).add(asset); }
        List<Asset> pending = new ArrayList<>();
        for (Asset target : targets) {
            Set<String> required = new HashSet<>(); ArrayDeque<Asset> queue = new ArrayDeque<>(List.of(target));
            while (!queue.isEmpty()) {
                Asset asset = queue.removeFirst(); if (!required.add(asset.id())) continue;
                queue.addAll(children.getOrDefault(asset.id(), List.of()));
                if (asset.type() == AssetType.SCENARIO_STEP) {
                    Asset referenced = byId.get(Values.text(asset.data(), "targetId", "")); if (referenced != null) queue.add(referenced);
                }
            }
            // New SQL/UI/scenario children also make a root outstanding even if an earlier
            // run contains that root. Saved execution snapshots are never retroactively edited.
            boolean covered = saved.stream().anyMatch(run -> run.items().stream().anyMatch(item -> item.assetId().equals(target.id())) && run.graph().assets().keySet().containsAll(required));
            if (!covered) pending.add(target);
        }
        return pending;
    }
    private static List<String> strings(Object value) { return value instanceof List<?> list ? list.stream().map(Object::toString).toList() : List.of(); }
    public record Reconciliation(Asset plan, List<Asset> items, List<Asset> pending, List<String> addedIds, String blockedReason) { }
    public record Continuation(String planId, List<String> addedIds) { }
}
