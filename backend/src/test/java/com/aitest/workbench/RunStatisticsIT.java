package com.aitest.workbench;

import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.common.Ids;
import com.aitest.execution.RunRepository;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatisticsIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunRepository runs;
    @Autowired WorkbenchController workbench;

    @Test void batchedRunSummariesCountDistinctCasesAcrossStatusesAndObserveFreshResults() {
        String project = assets.createProject("运行统计口径", Map.of()).id();
        String first = run(project), second = run(project), empty = run(project);
        item(first, "case-a", 0, "PASSED");
        String changed = item(first, "case-a", 1, "FAILED");
        item(first, "case-b", null, "PASSED");
        item(second, "case-a", null, "BLOCKED");
        var listed = objects(runs.list(project, 0, 10).get("items"));
        assertThat(listed).hasSize(3);
        assertThat(summary(listed, first)).isEqualTo(Map.of("total", 3L, "counts", Map.of("PASSED", 2L, "FAILED", 1L), "caseCount", 2L, "dataRows", 2L));
        assertThat(summary(listed, second)).isEqualTo(Map.of("total", 1L, "counts", Map.of("BLOCKED", 1L), "caseCount", 1L, "dataRows", 0L));
        assertThat(summary(listed, empty)).isEqualTo(Map.of("total", 0L, "counts", Map.of(), "caseCount", 0L, "dataRows", 0L));
        jdbc.update("UPDATE test_run_item SET status='PASSED' WHERE id=?", changed);
        assertThat(summary(objects(runs.list(project, 0, 10).get("items")), first))
                .isEqualTo(Map.of("total", 3L, "counts", Map.of("PASSED", 3L), "caseCount", 2L, "dataRows", 2L));
        assertThat(runs.summary(first)).isEqualTo(Map.of("total", 3L, "counts", Map.of("PASSED", 3L), "caseCount", 2L, "dataRows", 2L));
        String other = assets.createProject("空统计项目", Map.of()).id();
        assertThat(objects(runs.list(other, 0, 10).get("items"))).isEmpty();
    }

    @Test void workbenchRecentBugsPreserveOrderingAndExcludeDeletedAndOtherProjects() {
        String project = assets.createProject("缺陷统计口径", Map.of()).id();
        String other = assets.createProject("其他缺陷项目", Map.of()).id();
        var open = assets.create(project, AssetType.BUG, null, "开放", Map.of("status", "OPEN"), "MANUAL");
        var closed = assets.create(project, AssetType.BUG, null, "关闭", Map.of("status", "CLOSED"), "MANUAL");
        var deleted = assets.create(project, AssetType.BUG, null, "已删除", Map.of("status", "OPEN"), "MANUAL");
        assets.create(other, AssetType.BUG, null, "其他项目", Map.of("status", "OPEN"), "MANUAL");
        assets.delete(project, deleted.id(), "1");
        jdbc.update("UPDATE asset SET updated_at=? WHERE id IN (?,?)", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), open.id(), closed.id());
        Map<String, Object> result = object(workbench.summary(project));
        assertThat(result.get("openBugCount")).isEqualTo(1L);
        assertThat(object(result.get("counts"))).containsEntry("BUG", 2L);
        @SuppressWarnings("unchecked") var recent = (List<com.aitest.asset.Asset>) result.get("recentBugs");
        assertThat(recent).extracting(com.aitest.asset.Asset::id)
                .containsExactlyElementsOf(List.of(open.id(), closed.id()).stream().sorted(java.util.Comparator.reverseOrder()).toList());
        assertThat(object(workbench.summary(other)).get("openBugCount")).isEqualTo(1L);
    }

    @Test void runIdsRetainMysqlCaseInsensitiveLookupInDetailAndSummary() {
        String project = assets.createProject("运行 ID 大小写兼容", Map.of()).id();
        String run = run(project); item(run, "case-a", 0, "PASSED");
        String upper = run.toUpperCase(java.util.Locale.ROOT);
        var expected = Map.of("total", 1L, "counts", Map.of("PASSED", 1L), "caseCount", 1L, "dataRows", 1L);
        assertThat(runs.get(project, upper).get("summary")).isEqualTo(expected);
        assertThat(runs.summary(upper)).isEqualTo(expected);
    }

    private String run(String project) {
        String job = Ids.newId(), run = "a" + Ids.newId().substring(1); Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO job_task(id,project_id,kind,idempotency_key,input,status,created_at,updated_at) VALUES(?,?,?,?,'{}','SUCCEEDED',?,?)", job, project, "STATS_TEST", job, now, now);
        jdbc.update("INSERT INTO test_run(id,project_id,asset_id,job_id,name,status,snapshot,private_snapshot,summary,created_at) VALUES(?,?,?,?,?,'PASSED','{}','test','{}',?)", run, project, project, job, "统计运行", now);
        return run;
    }
    private String item(String run, String asset, Integer row, String status) {
        String id = Ids.newId();
        jdbc.update("INSERT INTO test_run_item(id,run_id,asset_id,name,asset_type,position,row_index,variables,status) VALUES(?,?,?,?,'FUNCTIONAL_CASE',0,?,'{}',?)", id, run, asset, asset, row, status);
        return id;
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> summary(List<Map<String, Object>> runs, String id) {
        return object(runs.stream().filter(run -> id.equals(run.get("id"))).findFirst().orElseThrow().get("summary"));
    }
}
