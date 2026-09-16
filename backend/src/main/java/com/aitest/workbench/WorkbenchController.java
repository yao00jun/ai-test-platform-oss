package com.aitest.workbench;

import com.aitest.asset.*;
import com.aitest.execution.RunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/summary")
public final class WorkbenchController {
    private final AssetService assets; private final JdbcTemplate jdbc; private final RunRepository runs;
    public WorkbenchController(AssetService assets, JdbcTemplate jdbc, RunRepository runs) { this.assets = assets; this.jdbc = jdbc; this.runs = runs; }
    @GetMapping Object summary(@PathVariable String projectId) {
        assets.get(projectId, projectId);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT asset_type,COUNT(*) AS total FROM asset WHERE project_id=? AND deleted=FALSE GROUP BY asset_type", projectId)) counts.put(row.get("asset_type").toString(), ((Number) row.get("total")).longValue());
        Map<String, Long> status = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT i.status,COUNT(*) AS total FROM test_run_item i JOIN test_run r ON i.run_id=r.id WHERE r.project_id=? GROUP BY i.status", projectId)) status.put(row.get("status").toString(), ((Number) row.get("total")).longValue());
        long openBugs = jdbc.queryForObject("SELECT COUNT(*) FROM asset a JOIN bug_issue b ON b.asset_id=a.id WHERE a.project_id=? AND a.deleted=FALSE AND b.status IN ('OPEN','IN_PROGRESS','REOPENED')", Long.class, projectId);
        var recentBugs = assets.recent(projectId, AssetType.BUG, 10);
        return Map.of("counts", counts, "runSummary", status, "openBugCount", openBugs, "recentRuns", runs.list(projectId, 0, 10).get("items"), "recentBugs", recentBugs);
    }
}
