package com.aitest.engine.sql;

import com.aitest.common.Problem;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class SqlPolicyValidator {
    public Checked validate(String sql, boolean writesAllowed) {
        try {
            if (sql == null || sql.length() > 100000 || sql.contains("/*!")) throw Problem.invalid("SQL 长度或可执行注释不受支持");
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(2000));
            if (statements.size() != 1) throw Problem.invalid("每个 SQL 步骤只能包含一条语句");
            Statement statement = statements.getFirst();
            boolean write = statement instanceof Update || statement instanceof Delete || statement instanceof Insert;
            if (!(statement instanceof Select) && !write) throw Problem.invalid("只允许 SELECT 和受保护的 INSERT/UPDATE/DELETE");
            String structural = withoutLiterals(statement.toString()).toUpperCase(Locale.ROOT);
            if (structural.matches("(?s).*\\b(DROP|ALTER|TRUNCATE|CREATE|GRANT|REVOKE|CALL|EXECUTE|COPY|OUTFILE|DUMPFILE|LOAD_FILE|PG_READ_FILE|PG_WRITE_FILE|SLEEP|PG_SLEEP|BENCHMARK|DBLINK|GET_LOCK|RELEASE_LOCK)\\b.*")
                    || structural.contains(":=") || structural.matches("(?s).*\\bFOR\\s+(UPDATE|SHARE)\\b.*") || structural.matches("(?s).*\\bLOCK\\s+IN\\b.*")) throw Problem.invalid("SQL 包含禁止的危险或阻塞操作");
            if (statement instanceof Select && structural.matches("(?s).*\\b(INSERT|UPDATE|DELETE|INTO)\\b.*")) throw Problem.invalid("查询中不能包含写操作或 SELECT INTO");
            if (write && !writesAllowed) throw Problem.invalid("当前数据源或环境未允许 SQL 写入");
            if (statement instanceof Update update && !restricted(update.getWhere()) || statement instanceof Delete delete && !restricted(delete.getWhere())) throw Problem.invalid("UPDATE/DELETE 必须有可验证的 WHERE 条件，不能无条件或恒真");
            if (write && structural.startsWith("WITH")) throw Problem.invalid("不支持通过 CTE 执行写入");
            return new Checked(statement, write);
        } catch (Problem error) { throw error; }
        catch (Exception error) { throw Problem.invalid("SQL 无法完整解析，请检查语法或使用支持的 SQL 子集"); }
    }
    private boolean restricted(Expression condition) {
        if (condition == null) return false;
        if (condition instanceof ParenthesedExpressionList<?> list) return list.size() == 1 && restricted(list.getFirst());
        if (condition instanceof AndExpression and) return restricted(and.getLeftExpression()) || restricted(and.getRightExpression());
        if (condition instanceof OrExpression or) return restricted(or.getLeftExpression()) && restricted(or.getRightExpression());
        if (condition instanceof ComparisonOperator comparison) {
            return comparison.getLeftExpression() instanceof Column && literal(comparison.getRightExpression())
                    || comparison.getRightExpression() instanceof Column && literal(comparison.getLeftExpression());
        }
        if (condition instanceof InExpression in && !in.isNot() && in.getLeftExpression() instanceof Column && in.getRightExpression() instanceof ExpressionList<?> list)
            return !list.isEmpty() && list.stream().allMatch(this::literal);
        if (condition instanceof Between between) return !between.isNot() && between.getLeftExpression() instanceof Column && literal(between.getBetweenExpressionStart()) && literal(between.getBetweenExpressionEnd());
        return false;
    }
    private boolean literal(Expression value) {
        return value instanceof JdbcParameter || value instanceof LongValue || value instanceof DoubleValue || value instanceof StringValue || value instanceof DateValue || value instanceof TimestampValue || value instanceof TimeValue || value instanceof SignedExpression sign && literal(sign.getExpression());
    }
    private String withoutLiterals(String sql) {
        return sql.replaceAll("'(?:''|\\\\.|[^'])*'", "''").replaceAll("`[^`]*`|\"(?:\"\"|[^\"])*\"", "identifier");
    }
    public record Checked(Statement statement, boolean write) { }
}
