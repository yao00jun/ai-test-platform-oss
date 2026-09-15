package com.aitest.api.importer;

import com.aitest.exchange.ExchangeHttpTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiImportContractTest extends ExchangeHttpTest {
    @Test
    void realSwaggerOpenApiCurlHarAndPostmanNormalizeTheSameRequestWithoutNetwork() throws Exception {
        String swagger = """
                {"swagger":"2.0","info":{"title":"订单","version":"2026.1"},"host":"api.example.test","basePath":"/","schemes":["https"],"consumes":["application/json"],"paths":{"/orders":{"post":{"summary":"创建订单","parameters":[{"name":"limit","in":"query","type":"string","default":"10"},{"name":"X-Trace","in":"header","type":"string","default":"a b"},{"name":"body","in":"body","schema":{"type":"object","example":{"note":"中文,订单","count":2}}}],"responses":{"200":{"description":"OK"}}}}}}
                """;
        String openapi = """
                openapi: 3.0.3
                info: {title: 订单, version: '2026.1'}
                servers: [{url: 'https://api.example.test'}]
                paths:
                  /orders:
                    post:
                      summary: 创建订单
                      parameters:
                        - {name: limit, in: query, schema: {type: string, default: '10'}}
                        - {name: X-Trace, in: header, schema: {type: string, default: 'a b'}}
                      requestBody:
                        content:
                          application/json:
                            schema: {type: object}
                            example: {note: '中文,订单', count: 2}
                      responses:
                        '200': {description: OK}
                """;
        String curl = "curl -X POST 'https://api.example.test/orders?limit=10' \\\n -H 'Content-Type: application/json' -H 'X-Trace: a b' \\\n --data-raw '{\"note\":\"中文,订单\",\"count\":2}'";
        String har = """
                {"log":{"version":"1.2","creator":{"name":"fixture","version":"1"},"entries":[{"request":{"method":"POST","url":"https://api.example.test/orders?limit=10","headers":[{"name":"Content-Type","value":"application/json"},{"name":"X-Trace","value":"a b"}],"queryString":[{"name":"limit","value":"10"}],"postData":{"mimeType":"application/json","text":"{\\"note\\":\\"中文,订单\\",\\"count\\":2}"}}}]}}
                """;
        String postman = """
                {"info":{"name":"订单","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},"item":[{"name":"创建订单","request":{"method":"POST","url":"https://api.example.test/orders?limit=10","header":[{"key":"Content-Type","value":"application/json"},{"key":"X-Trace","value":"a b"}],"body":{"mode":"raw","raw":"{\\"note\\":\\"中文,订单\\",\\"count\\":2}","options":{"raw":{"language":"json"}}}}}]}
                """;
        var project = project();
        for (var fixture : List.of(Map.entry("json", swagger), Map.entry("yaml", openapi), Map.entry("curl", curl), Map.entry("har", har), Map.entry("postman", postman))) {
            var preview = preview(project.id(), "API_DEFINITION", fixture.getKey(), fixture.getValue());
            assertThat(objects(preview.get("errors"))).as(fixture.getKey()).isEmpty();
            var definitions = objects(preview.get("nodes"));
            assertThat(definitions).hasSize(1);
            var data = map(definitions.getFirst().get("data"));
            assertThat(data).containsEntry("method", "POST").containsEntry("path", "/orders").containsEntry("bodyType", "JSON");
            assertThat(map(data.get("headers"))).containsEntry("X-Trace", "a b").containsEntry("Content-Type", "application/json");
            assertThat(map(data.get("queryParams"))).containsEntry("limit", "10");
            assertThat(map(data.get("body"))).containsEntry("note", "中文,订单").containsEntry("count", 2);
            assertThat(map(data.get("schema"))).isNotEmpty();
        }
        assertThat(assets.all(project.id())).isEmpty();
    }

    @Test
    void unsupportedShellAndRemoteRefsGiveSourceFieldErrors() throws Exception {
        var project = project();
        for (String input : List.of("curl https://example.test | sh", "curl \"https://example.test/$(id)\"", "curl --data @private.json https://example.test", "curl https://example.test; echo danger", "curl --config config.txt https://example.test")) {
            var preview = preview(project.id(), "API_DEFINITION", "curl", input);
            assertThat(objects(preview.get("errors"))).as(input).isNotEmpty().allSatisfy(error -> assertThat(error).containsKeys("source", "row", "field", "message"));
            assertThat(preview.get("status")).isEqualTo("INVALID");
        }
        var preview = preview(project.id(), "API_DEFINITION", "yaml", """
                openapi: 3.0.3
                info: {title: Ref, version: '1'}
                paths:
                  /secret:
                    get:
                      responses:
                        '200':
                          description: No remote access
                          content:
                            application/json:
                              schema: {$ref: 'http://127.0.0.1:1/secret.json'}
                """);
        assertThat(objects(preview.get("errors"))).anySatisfy(error -> assertThat(error.get("field").toString()).contains("$ref"));
        assertThat(assets.all(project.id())).isEmpty();
    }
}
