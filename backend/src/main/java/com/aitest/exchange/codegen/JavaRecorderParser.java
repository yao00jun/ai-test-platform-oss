package com.aitest.exchange.codegen;

import com.aitest.exchange.ExchangeIssue;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import javax.tools.*;
import java.net.URI;
import java.util.*;
import static com.aitest.exchange.codegen.RecorderSyntax.*;

/** Uses the JDK parser, with annotation processing disabled and without analyze/generate. */
final class JavaRecorderParser {
    private final String source;
    private final List<ExchangeIssue> errors;
    private CompilationUnitTree unit;
    private Trees trees;
    private int offset;
    JavaRecorderParser(String source, List<ExchangeIssue> errors) { this.source = source; this.errors = errors; }

    List<Statement> parse(String text) {
        boolean complete = java.util.regex.Pattern.compile("\\b(?:class|record|interface)\\s+[A-Za-z_$]").matcher(text).find();
        if (!complete) { text = "class RecorderInput { void recorded() {\n" + text + "\n} }"; offset = 1; }
        final String input = text;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) { errors.add(new ExchangeIssue(source, 1, "$java", "Java 录制导入需要使用完整 JDK 21 启动服务")); return List.of(); }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///RecorderInput.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return input; }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "--release", "21"), null, List.of(file));
            unit = task.parse().iterator().next(); trees = Trees.instance(task);
            for (var diagnostic : diagnostics.getDiagnostics()) if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                errors.add(new ExchangeIssue(source, Math.max(1, (int) diagnostic.getLineNumber() - offset), "$java", "Java 语法无效，请修正这一行后重新预检"));
            List<Statement> result = new ArrayList<>(); int methods = 0;
            for (Tree declaration : unit.getTypeDecls()) {
                if (!(declaration instanceof ClassTree type)) { result.add(unsupported(declaration, "只支持录制类或录制语句")); continue; }
                for (Tree member : type.getMembers()) {
                    if (member instanceof MethodTree method && method.getBody() != null) {
                        methods++; result.addAll(statements(method.getBody().getStatements()));
                    } else if (!(member instanceof EmptyStatementTree)) result.add(unsupported(member, "不支持录制类中的字段、嵌套类或初始化块"));
                }
            }
            if (methods != 1) errors.add(new ExchangeIssue(source, 1, "$java", "每次导入需要且只能包含一个录制方法；请将多个测试分别导入"));
            return result;
        } catch (java.io.IOException | RuntimeException failure) {
            errors.add(new ExchangeIssue(source, 1, "$java", "无法解析 Java 录制文件")); return List.of();
        }
    }
    private List<Statement> statements(List<? extends StatementTree> input) {
        List<Statement> result = new ArrayList<>();
        for (StatementTree tree : input) {
            switch (tree) {
                case VariableTree variable -> result.add(new Statement(variable.getName().toString(), expression(variable.getInitializer()), line(tree)));
                case ExpressionStatementTree statement -> result.add(new Statement(null, expression(statement.getExpression()), line(tree)));
                case BlockTree block -> result.addAll(statements(block.getStatements()));
                case TryTree attempt -> {
                    for (Tree resource : attempt.getResources()) {
                        if (resource instanceof VariableTree variable) result.add(new Statement(variable.getName().toString(), expression(variable.getInitializer()), line(resource)));
                        else result.add(unsupported(resource, "仅支持 Playwright 的显式资源声明"));
                    }
                    result.addAll(statements(attempt.getBlock().getStatements()));
                    if (!attempt.getCatches().isEmpty()) result.add(unsupported(tree, "录制导入不支持异常分支；请手工编排失败策略"));
                    if (attempt.getFinallyBlock() != null) result.addAll(statements(attempt.getFinallyBlock().getStatements()));
                }
                case EmptyStatementTree ignored -> { }
                default -> result.add(unsupported(tree, "录制导入不支持条件、循环、赋值或自定义控制流程"));
            }
        }
        return result;
    }
    private Expr expression(ExpressionTree tree) {
        if (tree == null) return new Unsupported("变量声明需要明确的初始值", 1);
        int row = line(tree);
        return switch (tree) {
            case LiteralTree literal -> new Literal(literal.getValue(), row);
            case IdentifierTree identifier -> new Name(identifier.getName().toString(), row);
            case MemberSelectTree member -> new Member(expression(member.getExpression()), member.getIdentifier().toString(), row);
            case MethodInvocationTree invocation -> {
                var arguments = invocation.getArguments().stream().map(this::expression).toList();
                if (invocation.getMethodSelect() instanceof MemberSelectTree member) yield new Call(expression(member.getExpression()), member.getIdentifier().toString(), arguments, row);
                yield new Call(null, invocation.getMethodSelect().toString(), arguments, row);
            }
            case NewClassTree constructor -> new Construct(constructor.getIdentifier().toString(), constructor.getArguments().stream().map(this::expression).toList(), row);
            case LambdaExpressionTree lambda -> new Lambda(lambda.getBody() instanceof BlockTree block ? statements(block.getStatements()) : List.of(new Statement(null, expression((ExpressionTree) lambda.getBody()), row)), row);
            case ParenthesizedTree group -> expression(group.getExpression());
            case UnaryTree unary when unary.getKind() == Tree.Kind.UNARY_MINUS && unary.getExpression() instanceof LiteralTree literal && literal.getValue() instanceof Number number -> new Literal(-number.doubleValue(), row);
            default -> new Unsupported("参数需要静态文字或支持的 Playwright 调用；不支持动态表达式", row);
        };
    }
    private Statement unsupported(Tree tree, String message) { return new Statement(null, new Unsupported(message, line(tree)), line(tree)); }
    private int line(Tree tree) { return Math.max(1, (int) unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, tree)) - offset); }
}
