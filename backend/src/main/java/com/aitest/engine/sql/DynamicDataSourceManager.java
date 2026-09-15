package com.aitest.engine.sql;

import com.aitest.asset.Asset;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Component
public final class DynamicDataSourceManager {
    private final Map<String, Entry> current = new LinkedHashMap<>();
    private final List<Entry> retired = new ArrayList<>();
    private final int maxConnections;
    private boolean closed;
    public DynamicDataSourceManager(@Value("${aitest.execution.business-connections:32}") int maxConnections) { this.maxConnections = Math.clamp(maxConnections, 1, 256); }
    public Lease borrow(Asset source) throws SQLException {
        Entry entry;
        synchronized (this) {
            if (closed) throw new SQLException("业务连接池已关闭");
            entry = current.get(source.id());
            if (entry != null && !entry.version.equals(source.version())) { current.remove(source.id()); retired.add(entry); entry = null; }
            collect();
            if (entry == null) {
                int size = Values.integer(source.data(), "maxPoolSize", 4, 1, 16);
                int reserved = current.values().stream().mapToInt(e -> e.size).sum() + retired.stream().mapToInt(e -> e.size).sum();
                if (reserved + size > maxConnections) throw new Problem(503, "DATA_SOURCE_CAPACITY", "业务数据源总连接配额已用完，请关闭空闲数据源或调整配额");
                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(Values.text(source.data(), "jdbcUrl", "")); config.setUsername(Values.text(source.data(), "username", "")); config.setPassword(Values.text(source.data(), "password", ""));
                config.setMaximumPoolSize(size); config.setMinimumIdle(0); config.setConnectionTimeout(10000); config.setValidationTimeout(3000);
                config.setIdleTimeout(60000); config.setMaxLifetime(600000); config.setInitializationFailTimeout(-1);
                config.setPoolName("business-" + source.id().substring(0, Math.min(8, source.id().length())) + "-v" + source.version());
                entry = new Entry(source.version(), size, new HikariDataSource(config)); current.put(source.id(), entry);
            }
            entry.references++; entry.lastUsed = Instant.now();
        }
        try { return new Lease(entry, entry.pool.getConnection()); }
        catch (SQLException e) { release(entry); throw e; }
    }
    private synchronized void release(Entry entry) { entry.references--; entry.lastUsed = Instant.now(); collect(); }
    @Scheduled(fixedDelay = 60000) public synchronized void evict() {
        var iterator = current.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.references == 0 && entry.lastUsed.isBefore(Instant.now().minusSeconds(300))) { entry.pool.close(); iterator.remove(); }
        }
        collect();
    }
    private void collect() { retired.removeIf(entry -> { if (entry.references != 0) return false; entry.pool.close(); return true; }); }
    public synchronized Map<String, Object> statistics() {
        return Map.of("currentPools", current.size(), "retiredPools", retired.size(), "leasedConnections", current.values().stream().mapToInt(e -> e.references).sum() + retired.stream().mapToInt(e -> e.references).sum(), "connectionLimit", maxConnections);
    }
    @PreDestroy public synchronized void close() { closed = true; current.values().forEach(e -> e.pool.close()); retired.forEach(e -> e.pool.close()); current.clear(); retired.clear(); }
    private static final class Entry {
        final String version; final int size; final HikariDataSource pool;
        int references; Instant lastUsed = Instant.now();
        Entry(String version, int size, HikariDataSource pool) { this.version = version; this.size = size; this.pool = pool; }
    }
    public final class Lease implements AutoCloseable {
        private final Entry entry; private final Connection connection; private boolean returned;
        private Lease(Entry entry, Connection connection) { this.entry = entry; this.connection = connection; }
        public Connection connection() { return connection; }
        @Override public void close() throws SQLException {
            if (returned) return; returned = true;
            try { connection.close(); } finally { release(entry); }
        }
    }
}
