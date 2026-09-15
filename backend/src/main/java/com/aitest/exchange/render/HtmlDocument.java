package com.aitest.exchange.render;

import java.util.Objects;

/** All caller content passes through text escaping; the document contains no executable content. */
public final class HtmlDocument {
    private HtmlDocument() { }
    public static String escape(Object value) {
        return Objects.toString(value, "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
    public static StringBuilder start(String title, String subtitle) {
        return new StringBuilder("""
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:;">
                <title>
                """).append(escape(title)).append("""
                </title><style>
                @page{size:A4;margin:17mm 14mm 19mm}*{box-sizing:border-box}
                body{margin:0 auto;max-width:1000px;padding:32px;color:#1c2942;background:#fff;font:14px/1.7 "Microsoft YaHei","Noto Sans CJK SC",Arial,sans-serif}
                h1{font-size:28px;line-height:1.4;margin:0 0 10px;color:#12264c}h2{font-size:20px;margin:30px 0 12px;padding-bottom:6px;border-bottom:2px solid #d8e3f4}
                h3{font-size:16px;margin:20px 0 8px}h1,h2,h3,h4{break-after:avoid;overflow-wrap:anywhere}
                p{margin:6px 0 12px}.meta{font-size:12px;color:#66738a;overflow-wrap:anywhere}.metrics{display:flex;gap:12px;flex-wrap:wrap;margin:22px 0}
                .metric{background:#eff5ff;border:1px solid #d9e7fc;border-radius:8px;padding:12px 18px;font-weight:600}
                .status{display:inline-block;white-space:nowrap;font-size:12px;padding:1px 8px;border-radius:4px;background:#eef1f6;color:#34435c}.PASSED{background:#e3f5eb;color:#14693a}
                .FAILED,.ERROR{background:#ffeded;color:#ac2836}.BLOCKED,.MANUAL_PENDING,.INTERRUPTED{background:#fff4d9;color:#77570d}
                table{border-collapse:collapse;width:100%;table-layout:fixed;margin:12px 0 18px;font-size:12px}thead{display:table-header-group}
                th,td{border:1px solid #dbe2ec;padding:8px;text-align:left;vertical-align:top;overflow-wrap:anywhere;white-space:pre-wrap}th{background:#f3f6fb;color:#40526f}
                tr{break-inside:avoid}pre{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;background:#f6f8fb;border:1px solid #e4e9f0;padding:10px;font:11px/1.65 "Microsoft YaHei","Noto Sans Mono CJK SC",monospace}
                .field{margin:10px 0}.field-label{font-weight:600;color:#50617a;font-size:12px;break-after:avoid}.value{white-space:pre-wrap;overflow-wrap:anywhere;orphans:3;widows:3}pre{orphans:3;widows:3}
                .item{padding-top:4px}.step{border-left:3px solid #d9e7f8;padding-left:12px;margin:18px 0}.evidence{margin:16px 0;break-inside:avoid}
                .evidence img{display:block;max-width:100%;max-height:215mm;object-fit:contain;border:1px solid #dbe2ec}.note{padding:12px;background:#f6f8fb;border-radius:6px;color:#5b6880}
                @media print{body{padding:0;max-width:none;font-size:11px}h1{font-size:24px}h2{font-size:17px}h3{font-size:13px}.metrics{gap:8px}.metric{padding:9px 12px}pre{font-size:9px;line-height:1.65}table{font-size:10px}a{color:inherit;text-decoration:none}}
                </style></head><body><header><div class="meta">AI-TEST-PLATFORM · 持续测试工作台</div><h1>
                """).append(escape(title)).append("</h1><p class=\"meta\">").append(escape(subtitle)).append("</p></header>");
    }
    public static String finish(StringBuilder html) { return html.append("</body></html>").toString(); }
    public static void field(StringBuilder html, String label, Object value) {
        if (value == null || value.toString().isBlank()) return;
        html.append("<div class=\"field\"><div class=\"field-label\">").append(escape(label)).append("</div><div class=\"value\">").append(escape(value)).append("</div></div>");
    }
    public static void status(StringBuilder html, Object value) {
        String status = Objects.toString(value, "UNKNOWN");
        String label = switch (status) {
            case "PASSED" -> "通过";
            case "FAILED" -> "失败";
            case "ERROR" -> "执行错误";
            case "BLOCKED" -> "阻塞";
            case "MANUAL_PENDING" -> "待人工执行";
            case "INTERRUPTED" -> "执行中断";
            case "CANCELLED" -> "已取消";
            case "SKIPPED" -> "已跳过";
            case "RUNNING" -> "执行中";
            case "PENDING", "QUEUED" -> "等待执行";
            case "COMPLETED", "SUCCEEDED" -> "已完成";
            default -> status;
        };
        html.append("<span class=\"status ").append(status.matches("[A-Z_]+") ? status : "UNKNOWN").append("\">").append(escape(label)).append("</span>");
    }
}
