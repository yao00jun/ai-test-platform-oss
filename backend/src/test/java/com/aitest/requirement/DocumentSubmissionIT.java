package com.aitest.requirement;

import com.aitest.asset.AssetService;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import com.aitest.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSubmissionIT extends MySqlIntegrationTest {
    @Autowired AssetService assets;
    @Autowired JsonCodec json;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @TempDir Path documents;
    final HttpClient client = HttpClient.newHttpClient();

    @Test void simultaneousTextRetriesReturnOneRequirementAndPreserveLaterHumanEdits() throws Exception {
        var project = assets.createProject("需求幂等 " + UUID.randomUUID(), Map.of());
        var input = Map.of("text", "# 账单\n用户可以核对账单。", "name", "账单", "idempotencyKey", "read-once");
        List<HttpResponse<String>> responses;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            responses = workers.<HttpResponse<String>>invokeAll(List.of(() -> read(project.id(), input), () -> read(project.id(), input), () -> read(project.id(), input))).stream().map(result -> {
                try { return result.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
        }
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).as(response.body()).isEqualTo(200));
        var ids = responses.stream().map(response -> json.map(response.body()).get("id")).distinct().toList();
        assertThat(ids).hasSize(1);
        String id = ids.getFirst().toString();
        var original = assets.get(project.id(), id);
        var changed = assets.update(project.id(), id, original.version(), "人工修改的需求", Map.of("content", "保留人工澄清"), true, "MANUAL");
        var retried = read(project.id(), input);
        assertThat(json.map(retried.body())).containsEntry("id", id).containsEntry("version", changed.version());
        assertThat(json.map(retried.body()).get("name")).isEqualTo("人工修改的需求");
        assertThat(assets.list(project.id(), AssetType.REQUIREMENT, null, "", 0, 100).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_file WHERE project_id=?", Long.class, project.id())).isEqualTo(1);
        var conflict = read(project.id(), Map.of("text", "完全不同的正文", "idempotencyKey", "read-once"));
        assertThat(conflict.statusCode()).as(conflict.body()).isEqualTo(409);
        var other = assets.createProject("独立范围 " + UUID.randomUUID(), Map.of());
        assertThat(json.map(read(other.id(), input).body()).get("id")).isNotEqualTo(id);
    }

    @Test void acceptedPathRetryDoesNotRereadChangedOrMissingSourceOrRecreateDeletedRequirement() throws Exception {
        var project = assets.createProject("路径重试 " + UUID.randomUUID(), Map.of());
        Path source = documents.resolve("需求.md"); Files.writeString(source, "# 原始需求\n支持支付", StandardCharsets.UTF_8);
        var input = Map.of("path", source.toString(), "idempotencyKey", "path-once");
        var accepted = read(project.id(), input);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        String id = json.map(accepted.body()).get("id").toString();
        Files.delete(source);
        var recovered = read(project.id(), input);
        assertThat(recovered.statusCode()).as(recovered.body()).isEqualTo(200);
        assertThat(json.map(recovered.body()).get("id")).isEqualTo(id);
        var requirement = assets.get(project.id(), id); assets.delete(project.id(), id, requirement.version());
        var deleted = read(project.id(), input);
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(410);
        assertThat(json.map(deleted.body()).get("code")).isEqualTo("DOCUMENT_RESULT_DELETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE project_id=? AND asset_type='REQUIREMENT'", Long.class, project.id())).isEqualTo(1);
    }

    @Test void uploadedBytesAreIdempotentAndFailedParsingDoesNotConsumeTheKey() throws Exception {
        var project = assets.createProject("上传重试 " + UUID.randomUUID(), Map.of());
        var first = upload(project.id(), "upload-once", "需求.md", "# 上传\n检查余额");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(json.map(upload(project.id(), "upload-once", "需求.md", "# 上传\n检查余额").body()).get("id")).isEqualTo(json.map(first.body()).get("id"));
        var changed = upload(project.id(), "upload-once", "需求.md", "# 新内容");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(409);
        assertThat(upload(project.id(), "repair-key", "wrong.docx", "invalid-zip").statusCode()).isEqualTo(422);
        assertThat(upload(project.id(), "repair-key", "fixed.md", "# 修复\n有效需求").statusCode()).isEqualTo(200);
        assertThat(assets.list(project.id(), AssetType.REQUIREMENT, null, "", 0, 100).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM artifact_file WHERE project_id=?", Long.class, project.id())).isEqualTo(2);
    }

    private HttpResponse<String> read(String project, Object input) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(project, "/read")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(input))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(String project, String key, String filename, String content) throws Exception {
        String boundary = "document-" + UUID.randomUUID();
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"idempotencyKey\"\r\n\r\n" + key + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n";
        return client.send(HttpRequest.newBuilder(uri(project, "")).timeout(Duration.ofSeconds(30)).header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String project, String suffix) { return URI.create("http://127.0.0.1:" + port + "/api/projects/" + project + "/documents" + suffix); }
}
