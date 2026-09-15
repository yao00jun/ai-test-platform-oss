package com.aitest.execution;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.LinkedHashMap;
import java.util.Map;

/** One instance per scenario/DDT row. Cookies and extracted variables never cross runs. */
public final class ExecutionContext {
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final Runnable cancellationCheck;
    private final java.util.Set<String> published = new java.util.HashSet<>();
    public ExecutionContext(Map<String, Object> initial, Runnable cancellationCheck) {
        variables.putAll(initial); this.cancellationCheck = cancellationCheck;
    }
    public Map<String, Object> variables() { return variables; }
    public CookieManager cookies() { return cookies; }
    public void checkpoint() { cancellationCheck.run(); }
    public void publish(Map<String, Object> values) { variables.putAll(values); published.addAll(values.keySet()); }
    public void beginStep() { published.clear(); }
    public boolean published(String key) { return published.contains(key); }
}
