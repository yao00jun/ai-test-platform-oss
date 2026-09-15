package com.aitest.acceptance;

import com.aitest.exchange.ExchangeHttpTest;
import com.aitest.storage.FileStorageService;
import com.aitest.common.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {"aitest.schedules.enabled=false", "aitest.morning-brief.enabled=false", "aitest.notifications.enabled=false"})
class StorageFailureIT extends ExchangeHttpTest {
    @Autowired FileStorageService storage;
    @Autowired JdbcTemplate jdbc;

    @Test void aRealFilesystemWriteFailureLeavesNoFileRowsOrAssetsAndCanBeRetriedAfterRepair() throws Exception {
        String project = project().id();
        var blocked = storage.root().resolve("files").resolve(project);
        Files.createDirectories(blocked.getParent()); Files.writeString(blocked, "preexisting-file-must-survive");
        try {
            assertThatThrownBy(() -> storage.save(project, "case.json", "application/json", "{}".getBytes()))
                    .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.getMessage()).contains("文件保存失败"));
            assertThat(Files.readString(blocked)).isEqualTo("preexisting-file-must-survive");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_file WHERE project_id=?", Long.class, project)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_job WHERE project_id=?", Long.class, project)).isZero();
            assertThat(assets.all(project)).isEmpty();
        } finally { Files.delete(blocked); }
        var restored = preview(project, "FUNCTIONAL_CASE", "json", bundle(List.of(node("case", "FUNCTIONAL_CASE", "恢复后导入", null, Map.of(), Map.of()))));
        assertThat(objects(restored.get("errors"))).isEmpty();
        assertThat(apply(project, restored)).containsEntry("createdCount", 1);
        assertThat(assets.all(project)).singleElement().satisfies(asset -> assertThat(asset.name()).isEqualTo("恢复后导入"));
    }
}
