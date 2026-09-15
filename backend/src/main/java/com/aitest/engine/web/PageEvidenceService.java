package com.aitest.engine.web;

import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.execution.*;
import com.aitest.job.*;
import com.aitest.storage.FileStorageService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public final class PageEvidenceService implements JobHandler {
    private final AssetService assets;
    private final WebWorkerClient workers;
    private final JobService jobs;
    private final FileStorageService files;
    private final JsonCodec json;
    public PageEvidenceService(AssetService assets, WebWorkerClient workers, JobService jobs, FileStorageService files, JsonCodec json) { this.assets = assets; this.workers = workers; this.jobs = jobs; this.files = files; this.json = json; }
    public String kind() { return "PAGE_EVIDENCE"; }
    public Map<String, Object> submit(String project, String environment, String key) { environment(project, environment); return Map.of("jobId", jobs.submit(project, kind(), key, Map.of("environmentId", environment)).id()); }
    public Map<String, Object> execute(JobContext job, Map<String, Object> input) {
        var evidence = capture(job, input.get("environmentId").toString());
        return job.completeAtomically(() -> evidence);
    }
    public Map<String, Object> capture(JobContext job, String environmentId) {
        Asset environment = environment(job.projectId(), environmentId);
        String url = Values.text(environment.data(), "webUrl", "");
        Asset scenario = transientAsset(job.projectId(), AssetType.UI_SCENARIO, null, "页面证据采集", Map.of("baseUrl", url, "timeoutMs", 45000));
        Asset navigate = transientAsset(job.projectId(), AssetType.UI_STEP, scenario.id(), "访问已配置页面", Map.of("action", "navigate", "url", url, "timeoutMs", 20000));
        Asset observe = transientAsset(job.projectId(), AssetType.UI_STEP, scenario.id(), "读取真实页面结构", Map.of("action", "_observePage", "timeoutMs", 15000));
        observe = new Asset(observe.id(), observe.projectId(), observe.type(), observe.parentId(), observe.name(), observe.version(), 1, observe.source(), false, observe.createdAt(), observe.updatedAt(), observe.data());
        Map<String, Asset> graphAssets = new LinkedHashMap<>(); for (Asset asset : List.of(environment, scenario, navigate, observe)) graphAssets.put(asset.id(), asset);
        AssetGraph graph = new AssetGraph(scenario.id(), environmentId, graphAssets);
        List<StepResult> results = new ArrayList<>();
        String outcome = workers.execute(scenario, graph, new ExecutionContext(graph.environmentVariables(), job::checkpoint), (asset, result) -> results.add(result));
        if (!outcome.equals("PASSED")) throw new Problem(422, "PAGE_EVIDENCE_UNAVAILABLE", "无法采集已配置页面的证据：" + results.stream().filter(result -> !result.successful()).map(StepResult::error).filter(Objects::nonNull).findFirst().orElse("页面不可访问"));
        Map<String, Object> evidence = new LinkedHashMap<>(results.getLast().actual()); evidence.put("environmentId", environment.id()); evidence.put("environmentVersion", environment.version());
        job.checkpoint();
        var file = job.atomic(() -> files.save(job.projectId(), "page-evidence.json", "application/json", json.write(evidence).getBytes(StandardCharsets.UTF_8)));
        evidence.put("fileId", file.id()); return evidence;
    }
    private Asset environment(String project, String id) {
        Asset environment = assets.getInternal(project, id);
        if (environment.type() != AssetType.ENVIRONMENT || Values.text(environment.data(), "webUrl", "").isBlank()) throw Problem.invalid("请为项目环境配置 Web 基础地址");
        return environment;
    }
    private Asset transientAsset(String project, AssetType type, String parent, String name, Map<String, Object> data) {
        Instant now = Instant.now(); return new Asset(Ids.newId(), project, type, parent, name, "1", 0, "AI", false, now, now, data);
    }
}
