package com.aitest.analysis.ast;

import com.aitest.analysis.SourceDiagnostic;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;
import static com.aitest.analysis.ast.AstValues.*;

final class SpringEndpointVisitor {
    private static final Map<String, String> VERBS = Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT", "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "ANY");
    List<Map<String, Object>> read(TypeDeclaration<?> owner, String qualified, String path, List<SourceDiagnostic> diagnostics) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> bases = List.of("");
        List<String> baseVerbs = List.of("ANY");
        for (var annotation : owner.getAnnotations()) if (simple(annotation.getNameAsString()).equals("RequestMapping")) { bases = paths(annotation, path, diagnostics); baseVerbs = methods(annotation, baseVerbs); }
        for (MethodDeclaration method : owner.getMethods()) for (var annotation : method.getAnnotations()) {
            String verb = VERBS.get(simple(annotation.getNameAsString())); if (verb == null) continue;
            List<String> verbs = verb.equals("ANY") ? methods(annotation, baseVerbs) : List.of(verb);
            for (String base : bases) for (String suffix : paths(annotation, path, diagnostics)) for (String httpMethod : verbs) {
                String route = ("/" + base + "/" + suffix).replaceAll("/{2,}", "/");
                if (route.length() > 1 && route.endsWith("/")) route = route.substring(0, route.length() - 1);
                result.add(row("method", httpMethod, "path", route, "methodId", qualified + "#" + method.getSignature().asString(), "sourcePath", path,
                        "startLine", start(method), "endLine", end(method), "parameters", method.getParameters().stream().map(parameter -> row("name", parameter.getNameAsString(), "type", parameter.getTypeAsString(), "annotations", annotations(parameter))).toList(), "evidenceLevel", "STATIC"));
            }
        }
        return result;
    }
    private List<String> methods(AnnotationExpr annotation, List<String> fallback) {
        Object configured = values(annotation).get("method");
        if (configured == null) return fallback;
        List<String> methods = (configured instanceof List<?> list ? list : List.of(configured)).stream().map(value -> simple(value.toString())).toList();
        return methods.isEmpty() ? fallback : methods;
    }
    private List<String> paths(AnnotationExpr annotation, String path, List<SourceDiagnostic> diagnostics) {
        Expression expression = null;
        if (annotation instanceof SingleMemberAnnotationExpr single) expression = single.getMemberValue();
        if (annotation instanceof NormalAnnotationExpr normal) expression = normal.getPairs().stream().filter(pair -> Set.of("path", "value").contains(pair.getNameAsString())).map(MemberValuePair::getValue).findFirst().orElse(null);
        if (expression == null) return List.of("");
        List<Expression> expressions = expression instanceof ArrayInitializerExpr array ? array.getValues() : List.of(expression);
        List<String> result = new ArrayList<>();
        for (Expression item : expressions) {
            if (constantString(item)) result.add(string(item));
            else diagnostics.add(new SourceDiagnostic("WARNING", "DYNAMIC_ENDPOINT_PATH", "BACKEND", path, start(annotation), "映射路径含未解析常量或表达式，未推断为实际 URL"));
        }
        return result;
    }
}
