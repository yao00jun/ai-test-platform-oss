package com.aitest.asset;

import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AssetValidator {
    private final JsonCodec json;
    public AssetValidator(JsonCodec json) { this.json = json; }

    public Map<String, Object> validate(AssetType type, String name, Map<String, Object> input) {
        if (name == null || name.isBlank() || name.length() > 255) throw Problem.invalid("名称必填，且最多 255 个字符");
        Set<String> keys = type.fields().stream().map(FieldDefinition::key).collect(Collectors.toSet());
        for (String key : input.keySet()) if (!keys.contains(key)) throw Problem.invalid(type.label() + "不允许字段: " + key);
        Map<String, Object> result = new LinkedHashMap<>();
        for (FieldDefinition field : type.fields()) {
            Object value = input.containsKey(field.key()) ? input.get(field.key()) : field.defaultValue();
            if (field.required() && (value == null || value.toString().isBlank())) throw Problem.invalid(field.label() + "必填");
            if (value == null && !field.key().equals("body")) {
                if (List.of("boolean", "number", "select", "json").contains(field.kind())) throw Problem.invalid(field.label() + "不能为 null");
                value = "";
            }
            if (value != null) switch (field.kind()) {
                case "number" -> {
                    if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 0 || n.longValue() > 3_600_000) throw Problem.invalid(field.label() + "必须为 0–3600000 的整数");
                    value = ((Number) value).longValue();
                }
                case "boolean" -> { if (!(value instanceof Boolean)) throw Problem.invalid(field.label() + "必须是布尔值"); }
                case "json" -> {
                    if (field.defaultValue() instanceof Map && !(value instanceof Map) && !field.key().equals("body")) throw Problem.invalid(field.label() + "必须是 JSON 对象");
                    if (field.defaultValue() instanceof List && !(value instanceof List)) throw Problem.invalid(field.label() + "必须是 JSON 数组");
                    if (json.write(value).length() > 8_000_000) throw Problem.invalid(field.label() + "超过 8 MB 限制");
                }
                default -> {
                    if (!(value instanceof String)) throw Problem.invalid(field.label() + "必须是文本");
                    if (value.toString().length() > 2_000_000) throw Problem.invalid(field.label() + "内容过长");
                    if (!field.options().isEmpty() && !field.options().contains(value)) throw Problem.invalid(field.label() + "选项无效");
                }
            }
            result.put(field.key(), value);
        }
        switch (type) {
            case PROJECT -> {
                for (String key : List.of("backendRepoPath", "frontendRepoPath", "sqlScriptPath"))
                    com.aitest.analysis.source.SourcePaths.validate(result.get(key).toString(), !key.equals("sqlScriptPath"));
            }
            case ENVIRONMENT -> httpUrl(result.get("baseUrl").toString());
            case DATABASE_SOURCE -> {
                String url = result.get("jdbcUrl").toString();
                String expected = "POSTGRESQL".equals(result.get("dbType")) ? "jdbc:postgresql:" : "jdbc:mysql:";
                if (!url.startsWith(expected)) throw Problem.invalid("JDBC URL 与数据库类型不匹配");
                try {
                    URI address = URI.create(url.substring(5));
                    if (address.getHost() == null || address.getUserInfo() != null || address.getRawFragment() != null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) { throw Problem.invalid("请使用 jdbc:mysql://host:port/database 或 jdbc:postgresql://host:port/database；凭证须单独配置"); }
                String decoded = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
                if (decoded.matches("(?is).*://[^/?#]*@.*") || decoded.matches("(?is).*[?&;](user(?:name)?|password|passwd|api[-_]?key|token|secret)\\s*=.*")) throw Problem.invalid("JDBC URL 不能包含凭证，请使用用户名和加密密码字段");
                if (url.toLowerCase().matches(".*(allowloadlocalinfile|allowurlinlocalinfile|autodeserialize)=true.*")) throw Problem.invalid("不支持具有本地文件或反序列化选项的 JDBC URL");
                long size = ((Number) result.get("maxPoolSize")).longValue();
                if (size < 1 || size > 16) throw Problem.invalid("数据源连接池范围为 1–16");
            }
            case DATASET -> validateDataset(result);
            case WEBHOOK -> com.aitest.notification.WebhookProtocol.validate(result);
            case DASHBOARD -> result.put("cards", com.aitest.workbench.DashboardCardSchema.normalize(result.get("cards")));
            case TEST_PLAN -> {
                String cron = String.valueOf(result.get("cronExpression"));
                if (!cron.isBlank() && !org.springframework.scheduling.support.CronExpression.isValidExpression(cron)) throw Problem.invalid("Cron 需要六个字段（秒 分 时 日 月 周）");
                if (Boolean.TRUE.equals(result.get("scheduleEnabled")) && cron.isBlank()) throw Problem.invalid("启用巡检前需要配置 Cron 表达式");
                String timezone = String.valueOf(result.get("timezone"));
                if (!timezone.isBlank()) try { ZoneId.of(timezone); } catch (Exception e) { throw Problem.invalid("时区无效"); }
                if (((Number) result.get("concurrency")).longValue() < 1 || ((Number) result.get("concurrency")).longValue() > 32) throw Problem.invalid("计划并发范围为 1–32");
            }
            case UI_STEP -> validateUiStep(result);
            case BUG -> result.put("codeDiagnosis", com.aitest.analysis.rca.FailureAnalysisResult.validate(result.get("codeDiagnosis")));
            default -> { }
        }
        return result;
    }
    private void validateDataset(Map<String, Object> data) {
        List<?> columns = (List<?>) data.get("columns");
        List<?> rows = (List<?>) data.get("rows");
        if (columns.stream().anyMatch(c -> !(c instanceof String s) || s.isBlank()) || columns.stream().distinct().count() != columns.size()) throw Problem.invalid("数据集列名必须非空且唯一");
        if (columns.size() > 200 || rows.size() > 100_000) throw Problem.invalid("数据集最多 200 列、100000 行");
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof Map<?, ?> row) || !columns.containsAll(row.keySet())) throw Problem.invalid("数据集第 " + (i + 1) + " 行包含未知列或不是对象");
        }
    }
    private void validateUiStep(Map<String, Object> data) {
        String action = data.get("action").toString();
        if (action.equals("navigate") && data.get("url").toString().isBlank()) throw Problem.invalid("导航动作需要 URL");
        if (Set.of("click", "fill", "press", "select", "check", "uncheck", "hover", "assertText", "assertVisible", "assertHidden", "assertValue", "extract", "popup").contains(action) && data.get("selector").toString().isBlank()) throw Problem.invalid("此动作需要定位器");
    }
    public static void httpUrl(String value) {
        try { URI uri = URI.create(value); if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw Problem.invalid("地址必须为 http/https URL，且不能在 URL 中嵌入凭证"); }
    }
}
