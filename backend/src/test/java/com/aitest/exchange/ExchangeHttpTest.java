package com.aitest.exchange;

import com.aitest.asset.Asset;
import com.aitest.asset.AssetService;
import com.aitest.common.JsonCodec;
import com.aitest.support.MySqlIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, filesystem and MySQL boundary shared by exchange acceptance tests. */
public abstract class ExchangeHttpTest extends MySqlIntegrationTest {
    @Autowired protected AssetService assets;
    @Autowired protected JsonCodec json;
    @LocalServerPort protected int port;
    protected final HttpClient http = HttpClient.newHttpClient();

    protected Asset project() { return assets.createProject("Exchange " + UUID.randomUUID(), Map.of("description", "Original project")); }
    protected URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    protected HttpResponse<byte[]> request(String method, String path, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    protected Map<String, Object> object(HttpResponse<byte[]> response) { return json.map(new String(response.body(), StandardCharsets.UTF_8)); }
    protected Map<String, Object> preview(String projectId, String type, String format, String name, byte[] bytes, Map<String, String> extra) throws Exception {
        String boundary = "ExchangeTest" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var fields = new java.util.LinkedHashMap<String, String>(); fields.put("type", type); fields.put("format", format); fields.putAll(extra);
        for (var entry : fields.entrySet()) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n" + entry.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = http.send(HttpRequest.newBuilder(uri("/api/projects/" + projectId + "/imports/preview"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return object(response);
    }
    protected Map<String, Object> preview(String projectId, String type, String format, String text) throws Exception {
        return preview(projectId, type, format, "fixture." + format, text.getBytes(StandardCharsets.UTF_8), Map.of());
    }
    @SuppressWarnings("unchecked") protected static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") protected static List<Map<String, Object>> objects(Object value) { return (List<Map<String, Object>>) value; }
    protected Map<String, Object> apply(String projectId, Map<String, Object> preview) throws Exception {
        var response = request("POST", "/api/projects/" + projectId + "/imports/" + preview.get("id") + "/apply", Map.of());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return object(response);
    }
    protected static Map<String, Object> node(String key, String type, String name, String parent, Map<String, Object> data, Map<String, String> refs) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("key", key); value.put("type", type); value.put("name", name); value.put("parentKey", parent); value.put("position", 0); value.put("data", data); value.put("references", refs); return value;
    }
    protected String bundle(List<Map<String, Object>> nodes) { return json.write(Map.of("formatVersion", "aitest.exchange/v1", "metadata", Map.of(), "nodes", nodes, "externalReferences", Map.of(), "warnings", List.of())); }
}
