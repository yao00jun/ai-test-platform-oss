package com.aitest.workbench;

import com.aitest.common.Problem;
import java.util.*;

/** Layout configuration contains presentation choices only; all values come from measured project data. */
public final class DashboardCardSchema {
    public static final String VERSION = "aitest.dashboard-card/v1";
    private static final List<String> TYPES = List.of("metric", "quality", "assets", "bugs", "runs", "evalops");
    private DashboardCardSchema() { }

    public static List<String> fields(String type) {
        return switch (type) {
            case "metric" -> List.of("value");
            case "quality" -> List.of("runCount", "itemCount", "passRatePercent", "failureCount", "pendingCount", "p95Ms");
            case "assets" -> List.of("FUNCTIONAL_CASE", "API_CASE", "API_DEFINITION", "SCENARIO", "SQL_VALIDATION", "UI_SCENARIO", "TEST_PLAN", "BUG", "QUALITY_BRIEF", "DATASET", "REQUIREMENT");
            case "bugs" -> List.of("name", "severity", "status", "updatedAt");
            case "runs" -> List.of("name", "status", "createdAt");
            case "evalops" -> List.of("invocations", "totalTokens", "validRatePercent", "passRatePercent", "correctRatePercent", "confirmedRegressionBugs", "estimatedCost");
            default -> throw Problem.invalid("看板卡片类型无效");
        };
    }

    public static List<Map<String, Object>> normalize(Object input) {
        if (!(input instanceof List<?> cards) || cards.size() > 50) throw Problem.invalid("看板需要卡片数组，最多 50 张卡片");
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : cards) {
            if (!(value instanceof Map<?, ?> card)) throw Problem.invalid("每张看板卡片必须是对象");
            String id = text(card, "id", null), type = text(card, "type", null), title = text(card, "title", null);
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") || !ids.add(id)) throw Problem.invalid("卡片 ID 必须合法且在布局内唯一");
            if (title.isBlank() || title.length() > 120) throw Problem.invalid("卡片标题需要 1–120 个字符");
            if (!VERSION.equals(text(card, "schemaVersion", VERSION))) throw Problem.invalid("不支持此看板卡片版本");
            List<String> allowedFields = fields(type);
            Set<String> keys = new HashSet<>(Set.of("schemaVersion", "id", "type", "title", "size", "visible", "fields"));
            if (type.equals("metric")) keys.add("metric");
            if (Set.of("bugs", "runs").contains(type)) keys.add("limit");
            if (!keys.containsAll(card.keySet())) throw Problem.invalid("卡片包含未知配置；统计数值不能写入布局");
            String size = text(card, "size", type.equals("metric") ? "small" : "wide");
            if (!Set.of("small", "wide", "full").contains(size)) throw Problem.invalid("卡片尺寸只能为 small、wide 或 full");
            Object visible = card.containsKey("visible") ? card.get("visible") : true;
            if (!(visible instanceof Boolean)) throw Problem.invalid("卡片 visible 必须为布尔值");
            Object requested = card.containsKey("fields") ? card.get("fields") : allowedFields;
            if (!(requested instanceof List<?> selected) || selected.isEmpty() || selected.size() > allowedFields.size()
                    || !allowedFields.containsAll(selected) || new HashSet<>(selected).size() != selected.size()) throw Problem.invalid("卡片显示字段必须非空、唯一且属于该类型");
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("schemaVersion", VERSION); normalized.put("id", id); normalized.put("type", type); normalized.put("title", title);
            normalized.put("size", size); normalized.put("visible", visible); normalized.put("fields", List.copyOf(selected));
            if (type.equals("metric")) {
                String metric = text(card, "metric", "cases");
                if (!Set.of("cases", "openBugs").contains(metric)) throw Problem.invalid("指标只支持 cases 或 openBugs");
                normalized.put("metric", metric);
            }
            if (Set.of("bugs", "runs").contains(type)) {
                Object limit = card.containsKey("limit") ? card.get("limit") : 5;
                if (!(limit instanceof Number count) || count.doubleValue() != count.intValue() || count.intValue() < 1 || count.intValue() > 10) throw Problem.invalid("卡片记录数为 1–10 的整数");
                normalized.put("limit", ((Number) limit).intValue());
            }
            result.add(normalized);
        }
        return result;
    }

    public static Map<String, Object> contract() {
        return Map.of("schemaVersion", VERSION, "maxCards", 50, "id", "stable unique [A-Za-z0-9][A-Za-z0-9_-]{0,63}; preserve existing IDs when editing",
                "size", List.of("small", "wide", "full"), "visible", "boolean", "types", TYPES.stream().map(type -> Map.of("type", type, "fields", fields(type))).toList(),
                "metricOptions", List.of("cases", "openBugs"), "tableLimit", "1–10 for bugs/runs only", "instruction", "仅配置标题、类型、尺寸、顺序、显示字段与可见性。数值来自真实统计，不输出 value 或 HTML。调优范围为当前整个布局。");
    }
    private static String text(Map<?, ?> card, String key, String fallback) {
        Object value = card.containsKey(key) ? card.get(key) : fallback;
        if (!(value instanceof String text)) throw Problem.invalid("卡片 " + key + " 必须为文本");
        return text;
    }
}
