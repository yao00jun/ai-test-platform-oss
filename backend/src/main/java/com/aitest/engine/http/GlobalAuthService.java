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
            spec.put("method", method); spec.put("path", login); spec.put("bodyType", "JSON"); spec.put("body", payload);
            StepResult result = http.execute(spec, Values.text(environment.data(), "baseUrl", ""), headers, Values.map(environment.data().get("httpOptions")), context);
            if (!result.successful()) throw new Problem(502, "AUTH_FAILED", "登录请求失败，请查看环境和鉴权配置");
            Object value;
            try { value = JsonPath.read(result.actual().get("body").toString(), nonempty(data, "tokenJsonPath", "$.token")); }
            catch (RuntimeException e) { throw Problem.invalid("登录响应中没有配置的 Token 路径"); }
            if (!(value instanceof String token) || token.isBlank()) throw Problem.invalid("登录 Token 必须为非空文本");
            String prefix = nonempty(data, "headerPrefix", "Bearer ");
            if (!prefix.endsWith(" ") && !prefix.isEmpty()) prefix += " ";
            Token issued = new Token(token, nonempty(data, "headerKey", "Authorization"), prefix);
            Instant expiresAt = Instant.now().plusSeconds(Values.integer(data, "ttlSeconds", 1800, 1, 86400));
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
