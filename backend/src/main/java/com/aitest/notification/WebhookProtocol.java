package com.aitest.notification;

import com.aitest.asset.AssetValidator;
import com.aitest.common.*;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;

/** Vendor envelopes and signatures, independent of delivery persistence and HTTP I/O. */
@Component
public final class WebhookProtocol {
    private final JsonCodec json;
    public WebhookProtocol(JsonCodec json) { this.json = json; }

    public static void validate(Map<String, Object> data) {
        String url = Values.text(data, "webhookUrl", ""), secret = Values.text(data, "secret", "");
        if (Values.bool(data, "enabled", false) && url.isBlank()) throw Problem.invalid("启用群通知前需要填写 Webhook URL");
        if (!url.isBlank()) {
            AssetValidator.httpUrl(url);
            URI uri = URI.create(url);
            if (url.length() > 8192 || uri.getFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw Problem.invalid("Webhook URL 不能包含片段，端口必须有效，长度最多 8192 字符");
        }
        if (secret.length() > 2048) throw Problem.invalid("签名密钥最多 2048 字符");
        if ("WECHAT_WORK".equals(data.get("platform")) && !secret.isBlank()) throw Problem.invalid("企业微信机器人使用 URL 中的 key，不支持单独配置签名密钥");
        Values.integer(data, "maxRetries", 3, 0, 5);
        Values.integer(data, "timeoutSeconds", 10, 1, 60);
    }

    Request request(Map<String, Object> config, Map<String, Object> report, Instant now) {
        validate(config);
        String platform = Values.text(config, "platform", "DINGTALK"), url = Values.text(config, "webhookUrl", ""), secret = Values.text(config, "secret", "");
        String text = markdown(report);
        Map<String, Object> body = new LinkedHashMap<>();
        switch (platform) {
            case "DINGTALK" -> {
                body.put("msgtype", "markdown"); body.put("markdown", Map.of("title", "持续测试完成通知", "text", text)); body.put("at", Map.of("isAtAll", false));
                if (!secret.isBlank()) {
                    String timestamp = Long.toString(now.toEpochMilli());
                    URI uri = URI.create(url);
                    List<String> query = new ArrayList<>();
                    if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) for (String part : uri.getRawQuery().split("&")) {
                        String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8);
                        if (!Set.of("timestamp", "sign").contains(name)) query.add(part);
                    }
                    query.add("timestamp=" + timestamp); query.add("sign=" + URLEncoder.encode(hmac(secret, timestamp + "\n" + secret), StandardCharsets.UTF_8));
                    url = url.split("\\?", 2)[0] + "?" + String.join("&", query);
                }
            }
            case "WECHAT_WORK" -> { body.put("msgtype", "markdown"); body.put("markdown", Map.of("content", text)); }
            case "FEISHU" -> {
                body.put("msg_type", "interactive");
                body.put("card", Map.of("header", Map.of("title", Map.of("tag", "plain_text", "content", "持续测试完成通知")),
                        "elements", List.of(Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", text)))));
                if (!secret.isBlank()) { String timestamp = Long.toString(now.getEpochSecond()); body.put("timestamp", timestamp); body.put("sign", hmac(timestamp + "\n" + secret, "")); }
            }
            default -> throw Problem.invalid("群通知平台无效");
        }
        return new Request(url, json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    Result response(String platform, int status, String body) {
        if (status < 200 || status >= 300) return new Result("REJECTED", status == 408 || status == 429 || status >= 500, "HTTP_REJECTED", status);
        Map<String, Object> response;
        try { response = json.map(body); } catch (RuntimeException invalid) { return new Result("UNCERTAIN", false, "INVALID_ACKNOWLEDGEMENT", status); }
        if (response == null) return new Result("UNCERTAIN", false, "INVALID_ACKNOWLEDGEMENT", status);
        String key = "FEISHU".equals(platform) ? "code" : "errcode";
        Object code = response.get(key);
        if ("FEISHU".equals(platform) && response.containsKey("StatusCode")) {
            Object old = response.get("StatusCode");
            if (response.containsKey(key) && (!numeric(code) || !numeric(old) || ((Number) code).longValue() != ((Number) old).longValue())) return new Result("UNCERTAIN", false, "CONFLICTING_ACKNOWLEDGEMENT", status);
            if (!response.containsKey(key)) code = old;
        }
        if (!numeric(code)) return new Result("UNCERTAIN", false, "MISSING_ACKNOWLEDGEMENT", status);
        return ((Number) code).longValue() == 0 ? new Result("DELIVERED", false, null, status) : new Result("REJECTED", false, "VENDOR_REJECTED", status);
    }

    private static boolean numeric(Object value) { return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == n.longValue(); }
    private static String hmac(String key, String value) {
        try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException failure) { throw new IllegalStateException("Webhook signing unavailable", failure); }
    }
    private static String label(Object value) {
        return Objects.toString(value, "").replaceAll("[\\r\\n\\p{Cntrl}]", " ").replace("<", "＜").replace(">", "＞").replace("&", "＆")
                .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
    }
    private static String markdown(Map<String, Object> report) {
        Map<String, Object> summary = Values.map(report.get("summary")), counts = Values.map(summary.get("counts"));
        String status = switch (Values.text(report, "status", "")) { case "PASSED" -> "通过"; case "FAILED", "ERROR" -> "失败"; case "BLOCKED" -> "阻塞"; case "CANCELLED" -> "取消"; case "INTERRUPTED" -> "中断"; case "SKIPPED" -> "跳过"; default -> "待确认"; };
        StringBuilder text = new StringBuilder("### 持续测试完成通知\n\n项目：").append(label(report.get("projectName"))).append("\n\n计划：").append(label(report.get("planName"))).append("\n\n结果：").append(status)
                .append("\n\n执行项：").append(summary.getOrDefault("total", 0)).append("；用例：").append(summary.getOrDefault("caseCount", 0)).append("；数据行：").append(summary.getOrDefault("dataRows", 0));
        Map<String, String> labels = new LinkedHashMap<>(); labels.put("PASSED", "通过"); labels.put("FAILED", "失败"); labels.put("ERROR", "错误"); labels.put("BLOCKED", "阻塞"); labels.put("SKIPPED", "跳过"); labels.put("CANCELLED", "取消"); labels.put("INTERRUPTED", "中断"); labels.put("MANUAL_PENDING", "待人工执行");
        labels.forEach((key, name) -> { if (counts.containsKey(key)) text.append("\n\n").append(name).append("：").append(counts.get(key)); });
        return text.append("\n\n运行 ID：").append(report.get("runId")).append("\n\n完成时间（UTC）：").append(report.get("completedAt")).toString();
    }
    record Request(String url, byte[] body) { }
    record Result(String outcome, boolean retryable, String code, Integer httpStatus) { }
}
