package com.aitest.execution;

import com.aitest.asset.*;
import com.aitest.engine.sql.SqlStepExecutor;
import org.springframework.stereotype.Component;

@Component
public final class SqlAssetExecutor implements AssetExecutor {
    private final SqlStepExecutor sql;
    public SqlAssetExecutor(SqlStepExecutor sql) { this.sql = sql; }
    public AssetType type() { return AssetType.SQL_VALIDATION; }
    public StepResult execute(Asset asset, AssetGraph graph, ExecutionContext context) {
        Asset source = graph.get(Values.text(asset.data(), "databaseSourceId", ""));
        String id = Values.text(source.data(), "environmentId", "");
        boolean allowed = false;
        if (!id.isBlank()) {
            Asset environment = graph.get(id);
            allowed = !Values.text(environment.data(), "purpose", "TEST").equals("PRODUCTION") && Values.bool(environment.data(), "allowSqlWrite", false)
                    && (graph.environmentId() == null || graph.environmentId().equals(id));
        }
        return sql.execute(source, asset.data(), context, allowed);
    }
}
