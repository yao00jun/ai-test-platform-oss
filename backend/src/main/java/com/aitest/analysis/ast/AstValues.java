package com.aitest.analysis.ast;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import java.util.*;

final class AstValues {
    private AstValues() { }
    static int start(Node node) { return node.getBegin().map(position -> position.line).orElse(0); }
    static int end(Node node) { return node.getEnd().map(position -> position.line).orElse(0); }
    static String simple(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    static Map<String, Object> values(AnnotationExpr annotation) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (annotation instanceof SingleMemberAnnotationExpr single) result.put("value", literal(single.getMemberValue()));
        if (annotation instanceof NormalAnnotationExpr normal) normal.getPairs().forEach(pair -> result.put(pair.getNameAsString(), literal(pair.getValue())));
        return result;
    }
    static Object literal(Expression expression) {
        if (expression instanceof StringLiteralExpr string) return string.asString();
        if (expression instanceof TextBlockLiteralExpr block) return block.asString();
        if (expression instanceof BooleanLiteralExpr bool) return bool.getValue();
        if (expression instanceof ArrayInitializerExpr array) return array.getValues().stream().map(AstValues::literal).toList();
        if (expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS && constantString(binary)) return string(binary);
        return expression.toString();
    }
    static boolean constantString(Expression expression) {
        return expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr || expression instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS && constantString(binary.getLeft()) && constantString(binary.getRight());
    }
    static String string(Expression expression) {
        if (expression instanceof BinaryExpr binary) return string(binary.getLeft()) + string(binary.getRight());
        return expression instanceof StringLiteralExpr string ? string.asString() : ((TextBlockLiteralExpr) expression).asString();
    }
    static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) row.put(values[i].toString(), values[i + 1]);
        return row;
    }
    static List<Map<String, Object>> annotations(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream().map(annotation -> row("name", simple(annotation.getNameAsString()), "values", values(annotation), "line", start(annotation))).toList();
    }
    static String limit(String source, int max) { return source.substring(0, Math.min(source.length(), max)); }
}
