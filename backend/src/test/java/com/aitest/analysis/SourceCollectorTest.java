package com.aitest.analysis;

import com.aitest.analysis.source.SourceCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SourceCollectorTest {
    @TempDir Path root;

    @Test void depthTruncationIsVisibleAndArbitraryMapperNamesAreIncluded() throws Exception {
        Files.writeString(root.resolve("billing-statements.xml"), "<mapper namespace='Billing'><select id='one'>SELECT 1</select></mapper>");
        Path deep = root;
        for (int i = 0; i < 51; i++) deep = Files.createDirectory(deep.resolve("d"));
        Files.writeString(deep.resolve("Hidden.java"), "class Hidden {}");
        var result = new SourceCollector("", 10, 1024, 4096).local("BACKEND", root.toString(), () -> {});
        assertThat(result.files()).extracting(file -> file.path()).contains("billing-statements.xml").doesNotContain("Hidden.java");
        assertThat(result.diagnostics()).extracting(SourceDiagnostic::code).contains("SOURCE_DEPTH_LIMIT");
    }

    @Test void byteLimitsAndInvalidEncodingCannotLookLikeCompleteEmptySources() throws Exception {
        Files.write(root.resolve("Large.java"), new byte[2048]);
        Files.write(root.resolve("Invalid.java"), new byte[]{(byte) 0xc3, (byte) 0x28});
        var result = new SourceCollector("", 10, 1024, 4096).local("BACKEND", root.toString(), () -> {});
        assertThat(result.files()).isEmpty();
        assertThat(result.diagnostics()).extracting(SourceDiagnostic::code).contains("FILE_TOO_LARGE", "SOURCE_ENCODING");
    }
}
