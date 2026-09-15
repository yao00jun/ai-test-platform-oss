package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class CsvBoundaryTest {
    private final JsonCodec json = new JsonCodec();

    @Test
    void ledgerFormulaLookingTextIsInertInExportAndRestoredExactlyOnImport() {
        var codec = new LedgerCodec(json, new PortableBundleCodec(json));
        var data = Map.<String, Object>of("reproduceSteps", "\t=2+2", "actualResult", "'already literal", "expectedResult", "@SUM(1)", "suggestion", "  +1");
        var bundle = ExchangeBundle.of(List.of(new ExchangeNode("bug", AssetType.BUG, null, "=2+2", 0, data, Map.of())));
        var file = codec.encode(bundle, "bugs", "csv");
        assertInertCells(file.bytes());
        var parsed = codec.parse("bugs.csv", "csv", file.bytes(), AssetType.BUG);
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.bundle().nodes().getFirst().name()).isEqualTo("=2+2");
        assertThat(parsed.bundle().nodes().getFirst().data()).containsAllEntriesOf(data);
        var ordinary = codec.parse("ordinary.csv", "csv", "name,actualResult\n'Original,'Unchanged\n".getBytes(StandardCharsets.UTF_8), AssetType.BUG);
        assertThat(ordinary.bundle().nodes().getFirst().name()).isEqualTo("'Original");
        assertThat(ordinary.bundle().nodes().getFirst().data()).containsEntry("actualResult", "'Unchanged");
    }

    @Test
    void datasetColumnNamesAndMissingMarkersAreInertAndRoundTripWithoutDataLoss() {
        var codec = new DatasetCodec(json); var columns = List.of("=1", "'quoted", "missing");
        var values = Map.<String, Object>of("=1", "=2", "'quoted", "001");
        var bundle = ExchangeBundle.of(List.of(new ExchangeNode("dataset", AssetType.DATASET, null, "data", 0,
                Map.of("columns", columns, "rows", List.of(values)), Map.of())));
        var file = codec.encode(bundle, "data", "csv");
        assertInertCells(file.bytes());
        var parsed = codec.parse("data.csv", "csv", file.bytes(), Map.of(), Map.of());
        assertThat(parsed.errors()).isEmpty();
        assertThat(parsed.bundle().nodes().getFirst().data()).containsEntry("columns", columns).containsEntry("rows", List.of(values));
    }

    @Test
    void duplicateLedgerColumnsAreRejectedBeforeTheyCanOverwriteValues() {
        var codec = new LedgerCodec(json, new PortableBundleCodec(json));
        assertThatThrownBy(() -> codec.parse("duplicate.csv", "csv", "name,actualResult,actualResult\nBug,first,second\n".getBytes(StandardCharsets.UTF_8), AssetType.BUG))
                .isInstanceOfSatisfying(ExchangeException.class, error -> assertThat(error.issue().field()).isEqualTo("header"));
    }

    private static void assertInertCells(byte[] bytes) {
        var rows = TabularSupport.csv(ExchangeIO.utf8(bytes, "export.csv"), "export.csv");
        assertThat(rows.stream().flatMap(List::stream)).noneMatch(value -> {
            String stripped = value.stripLeading();
            return !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0;
        });
    }
}
