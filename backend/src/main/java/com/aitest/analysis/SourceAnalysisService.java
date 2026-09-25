package com.aitest.analysis;

import com.aitest.analysis.ast.*;
import com.aitest.analysis.frontend.FrontendSourceParser;
import com.aitest.analysis.schema.SqlSchemaParser;
import com.aitest.analysis.source.*;
import com.aitest.asset.*;
import com.aitest.common.*;
import com.aitest.job.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class SourceAnalysisService implements JobHandler {
    private final AssetRepository assets;
    private final SourceSnapshotRepository snapshots;
    private final SourceCollector collector;
    private final GitSourceReader git;
    private final JavaCodeParser java;
    private final MapperXmlParser mapper;
    private final FrontendSourceParser frontend;
    private final SqlSchemaParser sql;
    private final JobService jobs;
    private final JsonCodec json;
    private final SecretProtector secrets;
    public SourceAnalysisService(AssetRepository assets, SourceSnapshotRepository snapshots, SourceCollector collector, GitSourceReader git, JavaCodeParser java, MapperXmlParser mapper,
                                 FrontendSourceParser frontend, SqlSchemaParser sql, JobService jobs, JsonCodec json, SecretProtector secrets) {
        this.assets = assets; this.snapshots = snapshots; this.collector = collector; this.git = git; this.java = java; this.mapper = mapper; this.frontend = frontend; this.sql = sql; this.jobs = jobs; this.json = json; this.secrets = secrets;
    }
    public record Input(String backendRepoPath, String frontendRepoPath, String sqlScriptPath, String ddlText, String backendRef, String frontendRef, String baselineRef, String idempotencyKey) { }
    public String kind() { return "SOURCE_ANALYZE"; }
    @Transactional public Map<String, Object> submit(String project, Input request) {
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 120) throw Problem.invalid("源码分析需要 1–120 字符的幂等键");
        String id = UUID.nameUUIDFromBytes((project + ":sources:" + request.idempotencyKey()).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        String hash = SourceFile.hash(json.write(request));
        assets.lockProject(project);
        var existing = assets.jdbc().queryForList("SELECT request_hash,job_id FROM source_snapshot WHERE id=? AND project_id=?", id, project);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get("request_hash"))) throw new Problem(409, "IDEMPOTENCY_CONFLICT", "此分析幂等键已用于其他输入");
            return Map.of("analysisId", id, "jobId", existing.getFirst().get("job_id"));
        }
        Asset owner = assets.project(project);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("backendRepoPath", request.backendRepoPath() == null ? owner.data().get("backendRepoPath") : request.backendRepoPath().strip());
        input.put("frontendRepoPath", request.frontendRepoPath() == null ? owner.data().get("frontendRepoPath") : request.frontendRepoPath().strip());
        input.put("sqlScriptPath", request.sqlScriptPath() == null ? owner.data().get("sqlScriptPath") : request.sqlScriptPath().strip());
        input.put("ddlText", Objects.toString(request.ddlText(), ""));
        for (String key : List.of("backendRepoPath", "frontendRepoPath", "sqlScriptPath")) SourcePaths.validate(input.get(key).toString(), !key.equals("sqlScriptPath"));
        if (input.values().stream().allMatch(value -> value.toString().isBlank())) throw Problem.invalid("请配置至少一项源码路径或数据库 DDL");
        input.put("backendRef", Objects.toString(request.backendRef(), "").strip());
        input.put("frontendRef", Objects.toString(request.frontendRef(), "").strip());
        input.put("baselineRef", Objects.toString(request.baselineRef(), "").strip());
        for (String field : List.of("backendRef", "frontendRef", "baselineRef")) SourcePaths.validateRef(input.get(field).toString());
        if ((!input.get("backendRef").toString().isBlank() || !input.get("baselineRef").toString().isBlank()) && input.get("backendRepoPath").toString().isBlank()) throw Problem.invalid("指定 Git 后端版本前需要提供后端仓库");
        if (!input.get("frontendRef").toString().isBlank() && input.get("frontendRepoPath").toString().isBlank()) throw Problem.invalid("指定 Git 前端版本前需要提供前端仓库");
        if (request.ddlText() != null && request.ddlText().getBytes(StandardCharsets.UTF_8).length > 2_000_000) throw Problem.invalid("粘贴 DDL 最多 2 MB");
        Job job = jobs.submit(project, kind(), "source:" + id, Map.of("analysisId", id));
        assets.jdbc().update("INSERT INTO source_snapshot(id,project_id,project_version,job_id,request_hash,input_cipher,status,diagnostics,created_at) VALUES(?,?,?,?,?,?,'PENDING','[]',?)",
                id, project, Long.parseLong(owner.version()), job.id(), hash, secrets.encrypt(json.write(input)), Timestamp.from(Instant.now()));
        return Map.of("analysisId", id, "jobId", job.id());
    }
    @Override public Map<String, Object> execute(JobContext job, Map<String, Object> request) {
        String id = request.get("analysisId").toString();
        Map<String, Object> input = snapshots.input(job.projectId(), id);
        List<SourceFile> files = new ArrayList<>(); List<SourceDiagnostic> diagnostics = new ArrayList<>(); Map<String, Object> origins = new LinkedHashMap<>();
        SourceBudget budget = collector.budget();
        String[][] kinds = {{"BACKEND", "backendRepoPath"}, {"FRONTEND", "frontendRepoPath"}, {"DDL", "sqlScriptPath"}};
        for (String[] source : kinds) {
            job.checkpoint();
            String location = Objects.toString(input.get(source[1]), "");
            String ref = Objects.toString(input.get(source[0].equals("BACKEND") ? "backendRef" : "frontendRef"), "");
            String baselineRef = source[0].equals("BACKEND") ? Objects.toString(input.get("baselineRef"), "") : "";
            SourceCollector.CollectionResult collected;
            Map<String, Object> origin = new LinkedHashMap<>();
            if (!source[0].equals("DDL") && !location.isBlank() && (SourcePaths.remote(location) || !ref.isBlank() || !baselineRef.isBlank())) {
                var repository = git.read(source[0], location, ref, baselineRef, budget, job::checkpoint);
                collected = repository.head();
                if (repository.baseline() != null) {
                    files.addAll(repository.baseline().files()); diagnostics.addAll(repository.baseline().diagnostics());
                    origin.put("baselineRevision", repository.baseline().revision());
                }
            } else collected = collector.local(source[0], location, budget, job::checkpoint);
            files.addAll(collected.files()); diagnostics.addAll(collected.diagnostics());
            origin.put("location", collected.location()); origin.put("revision", collected.revision()); origins.put(source[0], origin);
        }
        String ddl = Objects.toString(input.get("ddlText"), "");
        if (!ddl.isBlank()) collector.accept("DDL", "pasted-schema.sql", ddl.getBytes(StandardCharsets.UTF_8), files, diagnostics, budget);
        Map<String, List<Map<String, Object>>> backend = JavaCodeParser.empty();
        Map<String, List<Map<String, Object>>> baselineBackend = JavaCodeParser.empty();
        Map<String, List<Map<String, Object>>> browser = new LinkedHashMap<>(Map.of("selectors", new ArrayList<>(), "routes", new ArrayList<>(), "validations", new ArrayList<>()));
        List<Object> tables = new ArrayList<>();
        int processed = 0;
        files.sort(Comparator.comparing(SourceFile::kind).thenComparing(SourceFile::path));
        // An <include> may name a fragment in another mapper of the same code base, so fragments are gathered first.
        Map<String, MapperXmlParser.Fragments> fragments = new HashMap<>();
        for (SourceFile file : files) if (Set.of("BACKEND", "BASELINE").contains(file.kind()) && !file.path().toLowerCase(Locale.ROOT).endsWith(".java"))
            fragments.computeIfAbsent(file.kind(), kind -> new MapperXmlParser.Fragments()).collect(file.content());
        for (SourceFile file : files) {
            job.checkpoint();
            if (Set.of("BACKEND", "BASELINE").contains(file.kind())) {
                var target = file.kind().equals("BACKEND") ? backend : baselineBackend;
                List<SourceDiagnostic> fileDiagnostics = new ArrayList<>();
                if (file.path().toLowerCase(Locale.ROOT).endsWith(".java")) java.parse(file.path(), file.content(), fileDiagnostics).forEach((key, values) -> target.get(key).addAll(values));
                else target.get("mapperStatements").addAll(mapper.parse(file.path(), file.content(), fileDiagnostics, fragments.get(file.kind())));
                fileDiagnostics.forEach(d -> diagnostics.add(new SourceDiagnostic(d.severity(), d.code(), file.kind(), d.path(), d.line(), d.message())));
            }
            else if (file.kind().equals("FRONTEND")) frontend.parse(file.path(), file.content(), diagnostics).forEach((key, values) -> browser.get(key).addAll(values));
            else tables.addAll((List<?>) sql.parse(file.path(), file.content(), diagnostics).get("tables"));
            if (++processed % 25 == 0 || processed == files.size()) job.progress(Math.min(90, 10 + processed * 80 / Math.max(1, files.size())), "已分析 " + processed + "/" + files.size() + " 个源码文件");
        }
        Map<String, Object> result = Map.of("formatVersion", "aitest.source-evidence/v1", "parserVersion", "JavaParser 3.28.2 / JSqlParser 5.4", "javaAstFormatVersion", JavaCodeParser.FORMAT_VERSION, "origins", origins,
                "backend", backend, "baselineBackend", baselineBackend, "frontend", browser, "database", Map.of("tables", tables), "evidenceLevel", "STATIC", "requiresRuntimeVerification", true);
        SourceSnapshotRepository.Prepared prepared = snapshots.prepare(id, files, result, diagnostics);
        return job.completeAtomically(() -> {
            assets.lockProject(job.projectId()); snapshots.save(job.projectId(), prepared);
            return Map.of("analysisId", id, "fileCount", files.size(), "diagnosticCount", diagnostics.size());
        });
    }
}
