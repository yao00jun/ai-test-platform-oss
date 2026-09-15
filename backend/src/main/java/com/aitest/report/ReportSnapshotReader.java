package com.aitest.report;

import com.aitest.execution.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReportSnapshotReader {
    private final RunRepository runs;
    public ReportSnapshotReader(RunRepository runs) { this.runs = runs; }
    /** The short read transaction ends before any file IO or browser rendering starts. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> read(String projectId, String runId) {
        Map<String, Object> snapshot = new LinkedHashMap<>(runs.get(projectId, runId));
        snapshot.put("reportedAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)); snapshot.put("reportVersion", "aitest.report/v1"); return snapshot;
    }
}
