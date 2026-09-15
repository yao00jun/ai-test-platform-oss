package com.aitest.exchange;

import com.aitest.api.importer.ApiDocumentImporter;
import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ExchangeBoundaryTest {
    private final JsonCodec json = new JsonCodec();

    @Test
    void customPostmanApiKeyCredentialsAreRedactedWithoutLosingHeaderName() {
        String source = """
                {"info":{"name":"API","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},"auth":{"type":"apikey","apikey":[{"key":"key","value":"X-Custom-Access","type":"string"},{"key":"value","value":"private-custom-key-value","type":"string"},{"key":"in","value":"header","type":"string"}]},"item":[{"name":"read","request":{"method":"GET","url":"https://example.invalid/read"}}]}
                """;
        for (String placement : List.of("header", "query")) {
            String fixture = source.replace("\"header\"", "\"" + placement + "\"");
            if (placement.equals("query")) fixture = fixture.replace("https://example.invalid/read", "https://example.invalid/read?X-Custom-Access=private-custom-key-value");
            var parsed = new ApiDocumentImporter(json).parse("postman.json", "postman", fixture.getBytes(StandardCharsets.UTF_8), AssetType.API_CASE);
            assertThat(parsed.errors()).isEmpty();
            var publicBundle = new ExchangeRedactor().bundle(parsed.bundle());
            assertThat(publicBundle.nodes()).hasSize(2);
            assertThat(json.write(publicBundle)).doesNotContain("private-custom-key-value").contains("X-Custom-Access");
            for (var node : publicBundle.nodes()) assertThat(((Map<?, ?>) node.data().get(placement.equals("header") ? "headers" : "queryParams")).get("X-Custom-Access"))
                    .isEqualTo(com.aitest.common.SecretProtector.MASK);
            assertThat(json.write(parsed.bundle())).contains("private-custom-key-value");
        }
    }

    @Test
    void customOpenapiSecurityNamesAndSensitiveSchemaExamplesAreRedactedWithoutLosingDefinitions() {
        var data = Map.of("headers", Map.of("X-Custom-Access", "private-openapi-key"), "schema", Map.of("document", Map.of(
                "components", Map.of("securitySchemes", Map.of("CustomAuth", Map.of("type", "apiKey", "in", "header", "name", "X-Custom-Access")),
                        "schemas", Map.of("Credentials", Map.of("type", "object", "properties", Map.of("password", Map.of("type", "string", "minLength", 8, "default", "private-password-example"))))))));
        var result = new ExchangeRedactor().value("", data);
        assertThat(json.write(result)).doesNotContain("private-openapi-key", "private-password-example").contains("\"type\":\"apiKey\"", "\"type\":\"string\"", "\"minLength\":8", "X-Custom-Access");
    }

    @Test
    void safeYamlRejectsDuplicateKeysCollectionAliasesAndCustomTagsWhileKeepingLeadingZeroStrings() {
        assertThatThrownBy(() -> ExchangeIO.yaml("key: first\nkey: second", "dup.yaml")).isInstanceOf(ExchangeException.class);
        assertThatThrownBy(() -> ExchangeIO.yaml("a: &a [one, two]\nb: *a", "alias.yaml")).isInstanceOf(ExchangeException.class);
        assertThatThrownBy(() -> ExchangeIO.yaml("!!javax.script.ScriptEngineManager {}", "tag.yaml")).isInstanceOf(ExchangeException.class);
        assertThat(ExchangeIO.map(ExchangeIO.yaml("id: 001\nflag: true\ndate: 2026-09-14", "safe.yaml"), "safe.yaml", 1, "root")).containsEntry("id", "001").containsEntry("flag", true).containsEntry("date", "2026-09-14");
        assertThatThrownBy(() -> ExchangeIO.json("{\"key\":1,\"key\":2}", "dup.json")).isInstanceOf(ExchangeException.class);
    }

    @Test
    void malformedLedgerJsonIsAnExplicitFieldError() {
        var ledger = new LedgerCodec(json, new PortableBundleCodec(json));
        var parsed = ledger.parse("api.csv", "csv", "name,path,headers\nAPI,/health,not-json\n".getBytes(StandardCharsets.UTF_8), AssetType.API_CASE);
        assertThat(parsed.errors()).anySatisfy(error -> { assertThat(error.row()).isEqualTo(2); assertThat(error.field()).isEqualTo("headers"); });
        assertThat(parsed.bundle().nodes()).isEmpty();
    }
}
