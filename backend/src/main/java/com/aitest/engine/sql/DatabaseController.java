package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.*;
import org.springframework.web.bind.annotation.*;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/databases/{id}")
public final class DatabaseController {
    private final AssetService assets;
    private final DatabaseSchemaService schemas;
    private final SqlStepExecutor sql;
    public DatabaseController(AssetService assets, DatabaseSchemaService schemas, SqlStepExecutor sql) { this.assets = assets; this.schemas = schemas; this.sql = sql; }
    @GetMapping("/schema") public Map<String, Object> schema(@PathVariable String projectId, @PathVariable String id) {
        return schemas.schema(projectId, id);
    }
    @PostMapping("/validate-query") public Map<String, Object> validateQuery(@PathVariable String projectId, @PathVariable String id, @RequestBody Map<String, Object> input) { return schemas.validateReadQuery(projectId, id, Values.text(input, "sql", "")); }
    @PostMapping("/query") public StepResult query(@PathVariable String projectId, @PathVariable String id, @RequestBody Map<String, Object> input) {
        Asset source = source(projectId, id); boolean permission = false;
        String environmentId = Values.text(source.data(), "environmentId", "");
        if (!environmentId.isBlank()) {
            Asset environment = assets.getInternal(projectId, environmentId);
            permission = !Values.text(environment.data(), "purpose", "TEST").equals("PRODUCTION") && Values.bool(environment.data(), "allowSqlWrite", false);
        }
        return sql.execute(source, input, new ExecutionContext(Values.map(input.get("variables")), () -> { }), permission);
    }
    private Asset source(String projectId, String id) { Asset source = assets.getInternal(projectId, id); if (source.type() != AssetType.DATABASE_SOURCE) throw Problem.invalid("目标不是业务数据源"); return source; }
}
