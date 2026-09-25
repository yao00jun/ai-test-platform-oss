package com.aitest.engine.http;

import com.aitest.asset.Asset;
import com.aitest.common.JsonCodec;
import com.aitest.common.Problem;
import com.aitest.execution.*;
import com.jayway.jsonpath.JsonPath;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class GlobalAuthService {
    private final HttpStepExecutor http;
    private final VariableResolver resolver;
    private final JsonCodec json;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<ExecutionContext, Map<String, Session>> sessions = new WeakHashMap<>();
    public GlobalAuthService(HttpStepExecutor http, VariableResolver resolver, JsonCodec json) { this.http = http; this.resolver = resolver; this.json = json; }
    public Token token(Asset environment, Asset auth, ExecutionContext context, String rejectedToken) {
        context.checkpoint();
        Map<String, Object> data = auth.data();
        Object payload = resolver.resolve(data.getOrDefault("loginPayload", Map.of()), context.variables());
        if (payload == null) payload = Map.of();
        String login = resolver.text(data.get("loginUrl"), context.variables());
        Map<String, Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Values.map(resolver.resolve(environment.data().get("headers"), context.variables())).forEach((name, value) -> headers.put(name.toLowerCase(Locale.ROOT), value));
        String method = Values.text(data, "loginMethod", "POST");
        String absoluteLogin = login.startsWith("http://") || login.startsWith("https://") ? login : Values.text(environment.data(), "baseUrl", "").replaceAll("/$", "") + (login.startsWith("/") ? "" : "/") + login;
        List<String> cookies = cookies(context, absoluteLogin);
        String identity = environment.projectId() + ":" + environment.id() + ":" + environment.version() + ":" + auth.id() + ":" + auth.version() + ":" + hash(json.write(List.of(method, absoluteLogin, headers, payload)));
        synchronized (sessions) {
            Map<String, Session> own = sessions.get(context);
            Session session = own == null ? null : own.get(identity);
            if (session != null) {
                if (session.expiresAt().isAfter(Instant.now()) && session.cookies().equals(cookies) && !session.token().value().equals(rejectedToken)) return session.token();
                own.remove(identity);
            }
        }
        String key = identity + ":" + hash(json.write(cookies));
        if (cache.size() > 1024) evict();
        if (cache.size() > 1024 && !cache.containsKey(key)) throw new Problem(503, "AUTH_CACHE_CAPACITY", "鉴权上下文数量过多，请稍后重试");
        Entry entry = cache.compute(key, (ignored, existing) -> { Entry value = existing == null ? new Entry() : existing; value.borrowers++; return value; });
        boolean locked = false;
        try {
            while (!(locked = entry.lock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS))) context.checkpoint();
            context.checkpoint();
            if (entry.token != null && entry.expiresAt.isAfter(Instant.now()) && !entry.token.value().equals(rejectedToken)) return entry.token;
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("method", method); spec.put("path", login);
            // A GET login carries its parameters in the query string; a request body on GET is dropped by most servers.
            if (method.equals("GET")) { spec.put("bodyType", "NONE"); spec.put("queryParams", payload instanceof Map<?, ?> ? payload : Map.of()); }
            else { spec.put("bodyType", "JSON"); spec.put("body", payload); }
            StepResult result = http.execute(spec, Values.text(environment.data(), "baseUrl", ""), headers, Values.map(environment.data().get("httpOptions")), context);
            if (!result.successful()) {
                Object status = result.actual().get("status"); String body = Objects.toString(result.actual().get("body"), "").replaceAll("\\s+", " ").strip();
                String detail = status == null ? Objects.toString(result.error(), "未收到响应") : "HTTP " + status + (body.isEmpty() ? "" : "，响应：" + (body.length() > 200 ? body.substring(0, 200) + "…" : body));
                throw new Problem(502, "AUTH_FAILED", "全局鉴权登录失败（" + detail + "），请检查鉴权配置里的登录地址、请求方法和登录参数");
            }
            String path = nonempty(data, "tokenJsonPath", "$.token");
            Object value;
            try { value = JsonPath.read(result.actual().get("body").toString(), path); }
            catch (RuntimeException e) { throw Problem.invalid("登录成功（HTTP " + result.actual().get("status") + "）但响应里没有 Token 路径 " + path + "；" + loginHint(result.actual().get("body"))); }
            if (!(value instanceof String token) || token.isBlank()) throw Problem.invalid("登录响应中 " + path + " 不是非空文本；" + loginHint(result.actual().get("body")));
            String headerKey = nonempty(data, "headerKey", "Authorization");
            // An empty prefix means "Bearer " for the standard Authorization header and the bare token for custom headers (token, X-Access-Token).
            String prefix = Values.text(data, "headerPrefix", "");
            if (prefix.isBlank()) prefix = headerKey.equalsIgnoreCase("Authorization") ? "Bearer " : "";
            if (!prefix.endsWith(" ") && !prefix.isEmpty()) prefix += " ";
            Token issued = new Token(token, headerKey, prefix);
            Instant expiresAt = Instant.now().plusSeconds(Values.integer(data, "ttlSeconds", 1800, ExecutionLimits.AUTH_TTL_MIN_SECONDS, ExecutionLimits.AUTH_TTL_MAX_SECONDS));
            boolean cookieBound = !cookies.isEmpty() || Values.map(result.actual().get("headers")).keySet().stream().anyMatch(name -> name.equalsIgnoreCase("set-cookie"));
            if (cookieBound) {
                // The server may rotate session cookies while logging in. Cache that resulting
                // session only in its owning run, without keeping the run alive after completion.
                synchronized (sessions) { sessions.computeIfAbsent(context, ignored -> new HashMap<>()).put(identity, new Session(issued, expiresAt, cookies(context, absoluteLogin))); }
                entry.token = null; entry.expiresAt = Instant.EPOCH;
            } else { entry.token = issued; entry.expiresAt = expiresAt; }
            return issued;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException("鉴权等待已中断"); }
        catch (RuntimeException failed) { if (locked) { entry.token = null; entry.expiresAt = Instant.EPOCH; } throw failed; }
        finally {
            if (locked) entry.lock.unlock();
            cache.computeIfPresent(key, (ignored, current) -> {
                if (current != entry) return current;
                current.borrowers--; return current.borrowers == 0 && current.token == null ? null : current;
            });
        }
    }
    public boolean canRetry(Map<String, Object> spec, Asset auth) {
        return Values.bool(auth.data(), "retryUnauthorized", true)
                && (Set.of("GET", "PUT", "DELETE", "HEAD", "OPTIONS").contains(Values.text(spec, "method", "GET")) || Values.bool(auth.data(), "retryNonIdempotent", false));
    }
    @Scheduled(fixedDelay = 60000) public void evict() {
        Instant now = Instant.now();
        cache.keySet().forEach(key -> cache.computeIfPresent(key, (ignored, entry) -> entry.borrowers == 0 && entry.expiresAt.isBefore(now) ? null : entry));
    }
    private String nonempty(Map<String, Object> data, String key, String fallback) { String value = Values.text(data, key, ""); return value.isBlank() ? fallback : value; }
    /** Names the response's top-level fields and any error text, never the values: a login response carries the token itself. */
    private String loginHint(Object body) {
        try {
            if (!(json.tree(Objects.toString(body, "")) instanceof Map<?, ?> map)) return "响应不是 JSON 对象，请确认登录接口和 Token 路径";
            StringBuilder hint = new StringBuilder("响应顶层字段：" + String.join("、", map.keySet().stream().map(Object::toString).limit(20).toList()));
            for (String key : List.of("msg", "message", "errMsg", "error", "errorMessage")) if (map.get(key) instanceof String text && !text.isBlank() && text.length() <= 120) { hint.append("；").append(key).append("：").append(text); break; }
            return hint.append("。如果登录接口用 HTTP 200 返回业务错误，请检查登录参数").toString();
        } catch (RuntimeException unreadable) { return "响应不是 JSON，请确认登录接口和 Token 路径"; }
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private List<String> cookies(ExecutionContext context, String login) { return context.cookies().getCookieStore().get(URI.create(login)).stream().map(Object::toString).sorted().toList(); }
    private record Session(Token token, Instant expiresAt, List<String> cookies) { }
    private static final class Entry {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        int borrowers; volatile Token token; volatile Instant expiresAt = Instant.EPOCH;
    }
    public record Token(String value, String header, String prefix) { public String headerValue() { return prefix + value; } }
}
