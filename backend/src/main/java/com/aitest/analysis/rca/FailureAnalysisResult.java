package com.aitest.analysis.rca;

import com.aitest.common.Problem;
import java.util.*;

/** One coherent, revisioned code hypothesis; evidence and human judgements live separately. */
public final class FailureAnalysisResult {
    public static final String VERSION = "aitest.code-rca/v1";
    private static final Set<String> KEYS = Set.of("formatVersion", "root_cause", "affected_code_path", "suggested_fix", "is_regression", "confidence");
    private FailureAnalysisResult() { }
    public static Map<String, Object> validate(Object value) {
        if (!(value instanceof Map<?, ?> input)) throw invalid("代码诊断必须为 JSON 对象");
        if (input.isEmpty()) return Map.of();
        if (!input.keySet().equals(KEYS) || !VERSION.equals(input.get("formatVersion"))) throw invalid("代码诊断版本或字段无效，证据和人工评价不能由诊断内容写入");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("formatVersion", VERSION);
        for (String key : List.of("root_cause", "affected_code_path", "suggested_fix")) {
            int limit = key.equals("suggested_fix") ? 100_000 : key.equals("affected_code_path") ? 2200 : 32_000;
            if (!(input.get(key) instanceof String text) || text.length() > limit || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) throw invalid("代码诊断文本类型、长度或控制字符无效：" + key);
            result.put(key, input.get(key));
        }
        Object regression = input.get("is_regression"), confidence = input.get("confidence");
        if (regression != null && !(regression instanceof Boolean)) throw invalid("退化推测必须为布尔值或 null");
        if (confidence != null && (!(confidence instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0 || number.doubleValue() > 1)) throw invalid("置信度必须为 0–1 的数值或 null");
        result.put("is_regression", regression); result.put("confidence", confidence); return result;
    }
    static Problem invalid(String message) { return new Problem(422, "CODE_DIAGNOSIS_INVALID", message); }
}
