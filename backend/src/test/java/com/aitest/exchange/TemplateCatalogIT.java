package com.aitest.exchange;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TemplateCatalogIT extends ExchangeHttpTest {
    @Test
    void everyAdvertisedTemplateDownloadsAndPassesItsActualImportParserWithoutExternalIds() throws Exception {
        var response = request("GET", "/api/templates", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var families = objects(json.tree(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(families).extracting(f -> f.get("family")).containsExactlyInAnyOrder("PRD", "PROJECT", "FUNCTIONAL", "API", "SCENARIO", "UI", "DATASET", "PLAN", "BUG", "WORKBENCH");
        for (var family : families) for (var variant : objects(family.get("variants"))) {
            var file = request("GET", "/api/templates/" + family.get("family") + "?format=" + variant.get("format"), null);
            assertThat(file.statusCode()).as(family.get("family") + "/" + variant.get("format")).isEqualTo(200);
            assertThat(file.body()).isNotEmpty();
            var project = project();
            var preview = preview(project.id(), variant.get("type").toString(), variant.get("importFormat").toString(), variant.get("filename").toString(), file.body(), Map.of());
            assertThat(objects(preview.get("errors"))).as(family.get("family") + "/" + variant.get("format")).isEmpty();
            apply(project.id(), preview);
            assertThat(assets.all(project.id())).isNotEmpty();
        }
    }
}
