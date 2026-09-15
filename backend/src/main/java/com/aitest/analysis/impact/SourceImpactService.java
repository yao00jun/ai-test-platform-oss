package com.aitest.analysis.impact;

import com.aitest.analysis.SourceSnapshotRepository;
import com.aitest.analysis.ast.JavaCodeParser;
import com.aitest.analysis.diff.GitDiffAnalyzer;
import com.aitest.analysis.source.SourceFile;
import com.aitest.asset.AssetRepository;
import com.aitest.common.*;
import com.aitest.execution.Values;
import com.aitest.job.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class SourceImpactService implements JobHandler {
    private final SourceSnapshotRepository sources; private final SourceImpactRepository impacts; private final AssetRepository assets;
    private final GitDiffAnalyzer diff; private final BlastRadiusService blast; private final JobService jobs; private final JsonCodec json; private final TransactionTemplate transactions;
    private final CapturedBackendReader reader;
    public SourceImpactService(SourceSnapshotRepository sources, SourceImpactRepository impacts, AssetRepository assets, GitDiffAnalyzer diff, BlastRadiusService blast, JobService jobs, JsonCodec json, TransactionTemplate transactions, CapturedBackendReader reader) {
        this.sources = sources; this.impacts = impacts; this.assets = assets; this.diff = diff; this.blast = blast; this.jobs = jobs; this.json = json; this.transactions = transactions;
        this.reader = reader;
    }
    public record Request(String baselineSnapshotId, String idempotencyKey) { }
    @Override public String kind() { return "SOURCE_IMPACT"; }
    @Transactional public Map<String, Object> submit(String project, String source, Request request) {
        key(request.idempotencyKey());
        String id = identity(project + ":impact:" + source + ":" + request.idempotencyKey()), hash = SourceFile.hash(json.write(request));
        assets.lockProject(project);
        var prior = assets.jdbc().queryForList("SELECT request_hash,job_id FROM source_impact WHERE id=? AND project_id=?", id, project);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("request_hash"))) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此影响分析幂等键已用于其他输入");
            return Map.of("impactId", id, "jobId", prior.getFirst().get("job_id"));
        }
        Map<String, Object> head = sources.result(project, source);
        String baseline = Objects.toString(request.baselineSnapshotId(), "").strip();
        if (!baseline.isBlank()) {
            sources.binding(project, baseline);
            if (source.equals(baseline)) throw Problem.invalid("前后源码快照必须不同");
        } else if (Values.text(Values.map(Values.map(head.get("origins")).get("BACKEND")), "baselineRevision", "").isBlank()) throw Problem.invalid("该快照没有 Git 基线，请选择同项目的旧源码快照");
        Job job = jobs.submit(project, kind(), "impact:" + id, Map.of("impactId", id, "sourceSnapshotId", source, "baselineSnapshotId", baseline));
        assets.jdbc().update("INSERT INTO source_impact(id,project_id,source_snapshot_id,baseline_snapshot_id,job_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?)", id, project, source, baseline.isBlank() ? null : baseline, job.id(), hash, Timestamp.from(Instant.now()));
        return Map.of("impactId", id, "jobId", job.id());
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> request) {
        String project = job.projectId(), id = request.get("impactId").toString(), source = request.get("sourceSnapshotId").toString(), baseline = request.get("baselineSnapshotId").toString();
        var head = sources.result(project, source); var old = baseline.isBlank() ? head : sources.result(project, baseline);
        var oldFiles = sources.contents(project, baseline.isBlank() ? source : baseline, baseline.isBlank() ? "BASELINE" : "BACKEND"); var newFiles = sources.contents(project, source, "BACKEND");
        List<Map<String, Object>> parseDiagnostics = new ArrayList<>();
        var oldAst = reader.read(old, baseline.isBlank() ? "baselineBackend" : "backend", oldFiles, "BASELINE", parseDiagnostics, job::checkpoint);
        var newAst = reader.read(head, "backend", newFiles, "HEAD", parseDiagnostics, job::checkpoint);
        var changes = diff.compare(oldFiles, newFiles, oldAst, newAst, job::checkpoint);
        job.progress(35, "已定位旧、新方法变更范围");
        var result = blast.analyze(changes, oldAst, newAst, job::checkpoint);
        result.putAll(ImpactCandidates.map(transactions.execute(tx -> assets.all(project, null, null)), Values.objects(result.get("affectedEndpoints"))));
        result.put("sourceBinding", sources.binding(project, source));
        result.put("baselineBinding", baseline.isBlank() ? Map.of("sourceSnapshotId", source, "kind", "BASELINE", "revision", Values.map(Values.map(head.get("origins")).get("BACKEND")).get("baselineRevision")) : sources.binding(project, baseline));
        result.put("sourceDiagnostics", sources.get(project, source).get("diagnostics"));
        if (!baseline.isBlank()) result.put("baselineDiagnostics", sources.get(project, baseline).get("diagnostics"));
        result.put("formatVersion", "aitest.source-impact/v1");
        result.put("javaAstFormatVersion", JavaCodeParser.FORMAT_VERSION);
        var diagnostics = new ArrayList<>(Values.objects(result.get("diagnostics"))); diagnostics.addAll(parseDiagnostics); result.put("diagnostics", diagnostics);
        job.progress(90, "已匹配候选测试及依赖版本");
        String cipher = impacts.prepare(project, source, baseline, result);
        return job.completeAtomically(() -> { assets.lockProject(project); impacts.save(project, id, cipher); return Map.of("impactId", id, "candidateCount", Values.objects(result.get("candidates")).size()); });
    }
    static void key(String key) { if (key == null || key.isBlank() || key.length() > 120) throw Problem.invalid("需要 1–120 字符的幂等键"); }
    static String identity(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""); }
}
