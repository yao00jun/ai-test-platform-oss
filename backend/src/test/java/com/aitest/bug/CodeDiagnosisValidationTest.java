package com.aitest.bug;

import com.aitest.analysis.rca.*;
import com.aitest.common.Problem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CodeDiagnosisValidationTest {
    private final FailureDiagnosisAgent validator = new FailureDiagnosisAgent(null);
    private final Map<String, Object> evidence = Map.of("locations", List.of(Map.of("path", "OrderService.java", "sha256", "fixed-hash", "from", 1, "to", 8, "content", CodeRcaSupport.SOURCE.stripTrailing())));
    private Map<String, Object> code(String patch) {
        var value = new LinkedHashMap<String, Object>(); value.put("formatVersion", FailureAnalysisResult.VERSION); value.put("root_cause", "待验证推测"); value.put("affected_code_path", "OrderService.java:5"); value.put("suggested_fix", patch); value.put("confidence", null); value.put("is_regression", null); return value;
    }
    @Test void ordinaryAndWindowsUnifiedPatchBytesArePreserved() {
        for (String patch : List.of(CodeRcaSupport.PATCH, CodeRcaSupport.PATCH.replace("\n", "\r\n")))
            assertThat(validator.validate(code(patch), evidence)).containsEntry("suggested_fix", patch);
    }
    @Test void invalidHeadersHunksModesAndFabricatedLinesDoNotPassAsSafePatchSuggestions() {
        for (String patch : List.of(CodeRcaSupport.PATCH.replace("@@ -4,3 +4,3 @@", "@@ -4,3 +400,3 @@"), CodeRcaSupport.PATCH.replace("@@ -4,3 +4,3 @@", "@@ -4,4 +4,4 @@"), CodeRcaSupport.PATCH.replace("--- a/OrderService.java", "--- /dev/null"), "old mode 100644\nnew mode 100755\n" + CodeRcaSupport.PATCH,
                CodeRcaSupport.PATCH.replace("OrderService.java", "../OrderService.java"), CodeRcaSupport.PATCH + "GIT binary patch\n", CodeRcaSupport.PATCH.replace("missing id", "fabricated source")))
            assertThatThrownBy(() -> validator.validate(code(patch), evidence)).as(patch).isInstanceOf(Problem.class);
    }
    @Test void confidenceAndHumanOrProvenanceKeysAreStrictlySeparated() {
        for (Object confidence : List.of(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1, "0.5")) { var value = code(""); value.put("confidence", confidence); assertThatThrownBy(() -> FailureAnalysisResult.validate(value)).isInstanceOf(Problem.class); }
        for (String key : List.of("verdict", "sourceEvidence", "actor", "sourceSnapshotId")) { var value = code(""); value.put(key, "invented"); assertThatThrownBy(() -> FailureAnalysisResult.validate(value)).isInstanceOf(Problem.class); }
        assertThat(FailureAnalysisResult.validate(Map.of())).isEmpty();
        var unknown = code(""); unknown.put("affected_code_path", ""); assertThat(validator.validate(unknown, Map.of())).isEqualTo(unknown);
    }
}
