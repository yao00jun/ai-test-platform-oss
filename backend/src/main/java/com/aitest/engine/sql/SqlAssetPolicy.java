package com.aitest.engine.sql;

import com.aitest.asset.*;
import com.aitest.common.Problem;
import com.aitest.execution.Values;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class SqlAssetPolicy implements AssetPolicy {
    private final SqlParameters parameters;
    private final SqlPolicyValidator policy;
    public SqlAssetPolicy(SqlParameters parameters, SqlPolicyValidator policy) { this.parameters = parameters; this.policy = policy; }
    @Override public void validate(Asset previous, Asset candidate) {
        if (candidate.type() != AssetType.SQL_VALIDATION) return;
        var checked = policy.validate(parameters.parameterize(Values.text(candidate.data(), "sql", "")), Values.bool(candidate.data(), "allowWrite", false));
        Map<String, Object> exports = Values.map(candidate.data().get("exports"));
        if (!exports.isEmpty() && checked.statement() instanceof PlainSelect select) {
            Set<String> columns = new HashSet<>(); boolean wildcard = false;
            for (var item : select.getSelectItems()) {
                if (item.getAlias() != null) columns.add(item.getAlias().getName().replace("`", "").replace("\"", ""));
                else if (item.getExpression() instanceof net.sf.jsqlparser.schema.Column column) columns.add(column.getColumnName());
                else if (item.getExpression().toString().contains("*")) wildcard = true;
            }
            if (!wildcard && !columns.containsAll(exports.values())) throw Problem.invalid("SQL 返回列与结果变量映射不一致；局部调优不能破坏已有字段依赖");
        }
    }
}
