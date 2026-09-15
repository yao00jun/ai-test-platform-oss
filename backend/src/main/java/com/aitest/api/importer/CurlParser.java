package com.aitest.api.importer;

import com.aitest.exchange.ExchangeIO;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A tokenizer and request decoder, never a shell. No process, local-file or network API is used. */
final class CurlParser {
    private record Token(String value, int row) { }
    record Request(String method, String url, Map<String, Object> headers, String bodyType, Object body, Map<String, Object> details, int row) { }

    static List<Request> parse(String text, String source) {
        List<Request> requests = new ArrayList<>();
        for (var command : tokenize(text, source)) requests.add(request(command, source));
        if (requests.isEmpty()) throw ExchangeIO.error(source, 1, "$curl", "未找到 cURL 请求");
        return requests;
    }
    private static List<List<Token>> tokenize(String text, String source) {
        List<List<Token>> commands = new ArrayList<>(); List<Token> tokens = new ArrayList<>();
        StringBuilder value = new StringBuilder(); char quote = 0; boolean started = false; int row = 1; int startRow = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != '\'' && (c == '`' || c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '('))
                throw ExchangeIO.error(source, row, "$curl", "不支持命令替换；只接受静态 cURL 请求");
            if (c == '\\' && quote != '\'') {
                if (i + 1 >= text.length()) throw ExchangeIO.error(source, row, "$curl", "结尾反斜杠缺少后续字符");
                char next = text.charAt(++i);
                if (next == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; row++; continue; }
                if (next == '\n') { row++; continue; }
                if (quote == '"' && "\\\"$`".indexOf(next) < 0) value.append('\\');
                if (!started) startRow = row;
                started = true; value.append(next); continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else { value.append(c); if (c == '\n') row++; }
                continue;
            }
            if (c == '\'' || c == '"') { if (!started) startRow = row; started = true; quote = c; continue; }
            if (";|&<>".indexOf(c) >= 0) throw ExchangeIO.error(source, row, "$curl", "不支持管道、重定向或 shell 操作符；URL 中的特殊字符请加引号");
            if (Character.isWhitespace(c)) {
                if (started) { tokens.add(new Token(value.toString(), startRow)); value.setLength(0); started = false; }
                if (c == '\n') { row++; if (!tokens.isEmpty()) { commands.add(List.copyOf(tokens)); tokens.clear(); } }
            } else { if (!started) startRow = row; started = true; value.append(c); }
        }
        if (quote != 0) throw ExchangeIO.error(source, row, "$curl", "引号没有闭合");
        if (started) tokens.add(new Token(value.toString(), startRow));
        if (!tokens.isEmpty()) commands.add(tokens);
        return commands;
    }
    private static Request request(List<Token> tokens, String source) {
        int row = tokens.getFirst().row();
        if (!Set.of("curl", "curl.exe").contains(tokens.getFirst().value())) throw ExchangeIO.error(source, row, "$curl", "每条请求必须以 curl 开头；不支持 shell 包装器或变量赋值");
        String method = null, url = null, bodyType = "NONE"; boolean get = false;
        List<String> parts = new ArrayList<>(); Map<String, Object> form = new LinkedHashMap<>(), headers = new LinkedHashMap<>(), details = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i); String option = token.value(), inline = null;
            if (option.startsWith("--") && option.contains("=")) { int equal = option.indexOf('='); inline = option.substring(equal + 1); option = option.substring(0, equal); }
            else if (option.matches("^-[XHdFu].+")) { inline = option.substring(2); option = option.substring(0, 2); }
            if (!option.startsWith("-")) {
                if (url != null) throw ExchangeIO.error(source, token.row(), "url", "每条 cURL 请求只支持一个 URL");
                url = token.value(); continue;
            }
            if (Set.of("-L", "--location", "-s", "--silent", "-S", "--show-error", "--compressed", "--http1.1", "--http2").contains(option)) { details.put(option, true); continue; }
            if (Set.of("-I", "--head").contains(option)) { method = "HEAD"; continue; }
            if (Set.of("-G", "--get").contains(option)) { get = true; continue; }
            if (!Set.of("-X", "--request", "--url", "-H", "--header", "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode", "--json", "-F", "--form", "--form-string", "-u", "--user", "-b", "--cookie").contains(option))
                throw ExchangeIO.error(source, token.row(), "option", "此 cURL 选项不受支持；配置文件、脚本和本地文件读取均不允许");
            String argument;
            if (inline != null) argument = inline;
            else if (++i < tokens.size()) argument = tokens.get(i).value();
            else throw ExchangeIO.error(source, token.row(), "option", "cURL 选项缺少参数");
            switch (option) {
                case "-X", "--request" -> method = argument.toUpperCase(Locale.ROOT);
                case "--url" -> { if (url != null) throw ExchangeIO.error(source, token.row(), "url", "每条请求只能有一个 URL"); url = argument; }
                case "-H", "--header" -> {
                    if (argument.startsWith("@")) throw ExchangeIO.error(source, token.row(), "headers", "不读取本地 Header 文件");
                    int colon = argument.indexOf(':');
                    if (colon < 1) throw ExchangeIO.error(source, token.row(), "headers", "Header 必须使用名称: 值格式");
                    ApiDocumentImporter.add(headers, argument.substring(0, colon).strip(), argument.substring(colon + 1).strip());
                }
                case "-u", "--user" -> {
                    if (!argument.contains(":")) throw ExchangeIO.error(source, token.row(), "auth", "Basic 鉴权必须显式提供用户名和密码，不能交互输入");
                    headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(argument.getBytes(StandardCharsets.UTF_8)));
                    details.put("authentication", "basic");
                }
                case "-b", "--cookie" -> {
                    if (!argument.contains("=")) throw ExchangeIO.error(source, token.row(), "cookies", "不读取 Cookie 文件；请显式提供 name=value");
                    headers.put("Cookie", argument);
                }
                case "-F", "--form", "--form-string" -> {
                    int equal = argument.indexOf('=');
                    if (equal < 1 || !option.equals("--form-string") && (argument.substring(equal + 1).startsWith("@") || argument.substring(equal + 1).startsWith("<")))
                        throw ExchangeIO.error(source, token.row(), "body", "表单要求 name=value；本地上传文件请在导入后绑定受管文件");
                    if (!parts.isEmpty()) throw ExchangeIO.error(source, token.row(), "body", "不能混用 multipart 与其他请求体参数");
                    ApiDocumentImporter.add(form, argument.substring(0, equal), argument.substring(equal + 1)); bodyType = "MULTIPART";
                }
                default -> {
                    if (!form.isEmpty()) throw ExchangeIO.error(source, token.row(), "body", "不能混用 multipart 与其他请求体参数");
                    if (argument.startsWith("@") && !option.equals("--data-raw") || option.equals("--data-urlencode") && argument.indexOf('@') >= 0 && argument.indexOf('=') < 0)
                        throw ExchangeIO.error(source, token.row(), "body", "不读取本地请求体文件；请提供文件中的实际文本");
                    if (option.equals("--json")) { headers.putIfAbsent("Content-Type", "application/json"); headers.putIfAbsent("Accept", "application/json"); }
                    if (option.equals("--data-urlencode")) {
                        int equal = argument.indexOf('=');
                        if (equal < 0) argument = java.net.URLEncoder.encode(argument, StandardCharsets.UTF_8);
                        else argument = java.net.URLEncoder.encode(argument.substring(0, equal), StandardCharsets.UTF_8) + "=" + java.net.URLEncoder.encode(argument.substring(equal + 1), StandardCharsets.UTF_8);
                    }
                    parts.add(argument);
                }
            }
        }
        if (url == null || url.isBlank()) throw ExchangeIO.error(source, row, "url", "cURL 缺少 URL");
        Object body = Map.of();
        if (get && (!parts.isEmpty() || !form.isEmpty())) {
            if (!form.isEmpty()) throw ExchangeIO.error(source, row, "body", "GET 转换不支持 multipart 请求体");
            url += (url.contains("?") ? "&" : "?") + String.join("&", parts); parts.clear(); method = method == null ? "GET" : method;
        }
        if (!form.isEmpty()) body = form;
        else if (!parts.isEmpty()) {
            String contentType = ApiDocumentImporter.header(headers, "Content-Type");
            if (contentType == null) { contentType = "application/x-www-form-urlencoded"; headers.put("Content-Type", contentType); }
            String text = String.join("&", parts);
            bodyType = ApiDocumentImporter.bodyType(contentType);
            body = ApiDocumentImporter.body(text, bodyType, source, row);
        }
        if (method == null) method = bodyType.equals("NONE") ? "GET" : "POST";
        return new Request(method, url, headers, bodyType, body, details, row);
    }
}
