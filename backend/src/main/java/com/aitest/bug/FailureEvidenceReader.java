package com.aitest.bug;

import com.aitest.common.*;
import com.aitest.execution.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Fingerprints describe the failed check; volatile response bodies never define identity. */
@Component
public final class FailureEvidenceReader {
    public record Failure(String runId, String itemId, String key, String caseId, String caseName, String fingerprint, Map<String, Object> evidence) { }
    private final RunRepository runs;
    private final JsonCodec json;
    private final com.aitest.analysis.rca.SourceEvidenceReader sources;
    public FailureEvidenceReader(RunRepository runs, JsonCodec json, com.aitest.analysis.rca.SourceEvidenceReader sources) { this.runs = runs; this.json = json; this.sources = sources; }
    public List<Failure> read(String projectId, String runId) {
        return read(projectId, runId, true);
    }
    /** Submission and completion-event checks never decrypt/source-diff files inside their transaction. */
    public List<Failure> readWithoutSource(String projectId, String runId) { return read(projectId, runId, false); }
    private List<Failure> read(String projectId, String runId, boolean includeSource) {
        var run = runs.get(projectId, runId);
        if (Set.of("QUEUED", "RUNNING").contains(run.get("status"))) throw Problem.conflict("运行仍在进行，请等待结果保存后诊断");
        List<Failure> failures = new ArrayList<>();
        for (var item : Values.objects(run.get("items"))) {
            if (!Set.of("FAILED", "ERROR").contains(item.get("status"))) continue;
            var failed = Values.objects(item.get("steps")).stream().filter(step -> Set.of("FAILED", "ERROR").contains(step.get("status"))).toList();
            if (failed.isEmpty()) failures.add(failure(projectId, runId, item, null));
            else for (var step : failed) failures.add(failure(projectId, runId, item, step));
        }
        if (!includeSource || failures.isEmpty()) return failures;
        RunDefinition definition = runs.definition(projectId, runId);
        return failures.stream().map(failure -> {
            Map<String, Object> enriched = new LinkedHashMap<>(failure.evidence());
            enriched.put("sourceEvidence", sources.read(projectId, definition, failure.evidence()));
            return new Failure(failure.runId(), failure.itemId(), failure.key(), failure.caseId(), failure.caseName(), failure.fingerprint(), enriched);
        }).toList();
    }
    private Failure failure(String project, String run, Map<String, Object> item, Map<String, Object> step) {
        String caseId = item.get("assetId").toString(), itemId = item.get("id").toString();
        String key = step == null ? "item-" + item.get("manualVersion") : step.get("id").toString();
        Map<String, Object> result = step == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Values.map(step.get("result")));
        if (step == null) { result.put("actual", Objects.toString(item.get("notes"), Objects.toString(item.get("error"), ""))); result.put("error", Objects.toString(item.get("error"), "人工记录失败")); }
        List<Map<String, Object>> assertions = Values.objects(result.get("assertions")).stream().filter(assertion -> Boolean.FALSE.equals(assertion.get("passed"))).toList();
        String engine = step == null ? item.get("assetType").toString() : step.get("engine").toString();
        Map<String, Object> signature = new TreeMap<>();
        signature.put("project", project); signature.put("case", caseId); signature.put("engine", engine); signature.put("step", step == null ? caseId : step.get("assetId"));
        signature.put("status", item.get("status")); signature.put("httpStatus", result.get("actual") instanceof Map<?, ?> actual ? actual.get("status") : null);
        signature.put("assertions", assertions.stream().map(assertion -> {
            Map<String, Object> check = new TreeMap<>();
            for (String field : List.of("type", "path", "operator", "expected")) check.put(field, assertion.get(field));
            return check;
        }).toList());
        if (assertions.isEmpty()) signature.put("error", normalize(Objects.toString(result.get("error"), "")));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runId", run); evidence.put("runName", item.get("name")); evidence.put("runItemId", itemId); evidence.put("caseId", caseId); evidence.put("caseName", item.get("name"));
        evidence.put("engine", engine); evidence.put("rowIndex", item.get("rowIndex")); evidence.put("variables", item.get("variables")); evidence.put("result", result); evidence.put("failedAssertions", assertions);
        if (step != null) { evidence.put("stepId", step.get("assetId")); evidence.put("stepName", step.get("name")); evidence.put("stepResultId", step.get("id")); }
        return new Failure(run, itemId, key, caseId, item.get("name").toString(), hash(json.write(signature)), evidence);
    }
    /** Run-specific values (ids, times, generated numbers) are masked so a recurring failure keeps one fingerprint and is diagnosed once. */
    static String normalize(String error) {
        return error.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?", "{time}")
                .replaceAll("\\b\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\b", "{time}")
                .replaceAll("(?i)\\b[0-9a-f]{24,}\\b", "{id}").replaceAll("\\b\\d+(?:\\.\\d+)?\\s*(?:ms|milliseconds|秒)\\b", "{duration}")
                .replaceAll("\\d{6,}", "{n}")
                .replaceAll("\\s+", " ").strip();
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
