package com.aitest.analysis.ast;

import com.aitest.analysis.SourceDiagnostic;
import com.aitest.analysis.source.SourceFile;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.aitest.analysis.ast.AstValues.*;

/** Parses Java syntax without class loading, compilation, annotation processing or application startup. */
@Component
public final class JavaCodeParser {
    /** v4: mapper statements carry rendered dynamic SQL, so older snapshots are re-read with the current parser. */
    public static final String FORMAT_VERSION = "aitest.java-ast/v4";
    private static final Set<String> CONSTRAINTS = Set.of("NotNull", "NotBlank", "NotEmpty", "Null", "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Size", "Pattern", "Email", "Digits", "Past", "PastOrPresent", "Future", "FutureOrPresent", "AssertTrue", "AssertFalse", "Valid");
    public Map<String, List<Map<String, Object>>> parse(String path, String source, List<SourceDiagnostic> diagnostics) {
        Map<String, List<Map<String, Object>>> result = empty();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setAttributeComments(false));
        ParseResult<CompilationUnit> parsed = parser.parse(source);
        for (var problem : parsed.getProblems()) diagnostics.add(new SourceDiagnostic("ERROR", "JAVA_PARSE_ERROR", "BACKEND", path,
                problem.getLocation().flatMap(TokenRange::toRange).map(range -> range.begin.line).orElse(0), limit(problem.getMessage(), 600)));
        if (parsed.getResult().isEmpty()) return result;
        CompilationUnit unit = parsed.getResult().orElseThrow();
        List<String> imports = unit.getImports().stream().map(imported -> (imported.isStatic() ? "static " : "") + imported.getNameAsString() + (imported.isAsterisk() ? ".*" : "")).toList();
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        for (TypeDeclaration<?> owner : unit.findAll(TypeDeclaration.class)) {
            String qualified = qualified(owner);
            List<String> parents = new ArrayList<>();
            if (owner instanceof ClassOrInterfaceDeclaration type) { type.getExtendedTypes().forEach(parent -> parents.add(parent.asString())); type.getImplementedTypes().forEach(parent -> parents.add(parent.asString())); }
            if (owner instanceof RecordDeclaration record) record.getImplementedTypes().forEach(parent -> parents.add(parent.asString()));
            Map<String, String> fields = new LinkedHashMap<>();
            owner.getFields().forEach(field -> field.getVariables().forEach(variable -> fields.put(variable.getNameAsString(), variable.getTypeAsString())));
            if (owner instanceof RecordDeclaration record) record.getParameters().forEach(parameter -> fields.put(parameter.getNameAsString(), parameter.getTypeAsString()));
            result.get("classes").add(row("id", qualified, "name", owner.getNameAsString(), "packageName", packageName, "kind", owner.getClass().getSimpleName(), "parents", parents,
                    "fields", fields, "imports", imports, "annotations", annotations(owner), "sourcePath", path, "startLine", start(owner), "endLine", end(owner)));
            result.get("models").addAll(new DataModelVisitor().read(owner, qualified, path));
            result.get("endpoints").addAll(new SpringEndpointVisitor().read(owner, qualified, path, diagnostics));
        }
        for (CallableDeclaration<?> callable : unit.findAll(CallableDeclaration.class)) {
            TypeDeclaration<?> owner = callable.findAncestor(TypeDeclaration.class).orElse(null); if (owner == null) continue;
            String qualified = qualified(owner), id = methodId(callable);
            Map<String, String> variables = new LinkedHashMap<>();
            owner.getFields().forEach(field -> field.getVariables().forEach(variable -> variables.put(variable.getNameAsString(), variable.getTypeAsString())));
            callable.getParameters().forEach(parameter -> variables.put(parameter.getNameAsString(), parameter.getTypeAsString()));
            callable.findAll(VariableDeclarator.class).forEach(variable -> variables.put(variable.getNameAsString(), variable.getTypeAsString()));
            Map<String, Set<String>> variableTypes = new LinkedHashMap<>();
            owner.getFields().forEach(field -> field.getVariables().forEach(variable -> addType(variableTypes, variable.getNameAsString(), variable.getTypeAsString())));
            callable.getParameters().forEach(parameter -> addType(variableTypes, parameter.getNameAsString(), parameter.getTypeAsString()));
            callable.findAll(VariableDeclarator.class).forEach(variable -> addType(variableTypes, variable.getNameAsString(), variable.getTypeAsString()));
            variableTypes.forEach((name, types) -> { if (types.size() > 1) variables.put(name, "?"); });
            String body = callable instanceof MethodDeclaration method ? method.getBody().map(Object::toString).orElse("") : callable instanceof ConstructorDeclaration constructor ? constructor.getBody().toString() : "";
            result.get("methods").add(row("id", id, "owner", qualified, "name", callable.getNameAsString(), "signature", callable.getSignature().asString(),
                    "returnType", callable instanceof MethodDeclaration method ? method.getTypeAsString() : qualified,
                    "parameters", callable.getParameters().stream().map(parameter -> row("name", parameter.getNameAsString(), "type", parameter.getTypeAsString(), "varArgs", parameter.isVarArgs(), "annotations", annotations(parameter))).toList(),
                    "variables", variables, "variableTypes", variableTypes, "bodyHash", body.isBlank() ? "" : SourceFile.hash(body), "constructor", callable instanceof ConstructorDeclaration,
                    "static", callable.hasModifier(Modifier.Keyword.STATIC), "annotations", annotations(callable), "sourcePath", path, "startLine", start(callable), "endLine", end(callable)));
            for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
                if (call.findAncestor(CallableDeclaration.class).orElse(null) != callable) continue;
                result.get("calls").add(row("from", id, "receiver", call.getScope().map(Object::toString).orElse("this"), "name", call.getNameAsString(), "arity", call.getArguments().size(),
                        "arguments", call.getArguments().stream().map(argument -> limit(argument.toString(), 200)).toList(),
                        "argumentTypes", call.getArguments().stream().map(argument -> inferType(argument, variables)).toList(), "sourcePath", path, "line", start(call)));
            }
            for (ObjectCreationExpr creation : callable.findAll(ObjectCreationExpr.class)) {
                if (creation.findAncestor(CallableDeclaration.class).orElse(null) != callable) continue;
                result.get("calls").add(row("from", id, "receiver", creation.getTypeAsString(), "name", "<init>", "kind", "CONSTRUCTOR", "arity", creation.getArguments().size(),
                        "argumentTypes", creation.getArguments().stream().map(argument -> inferType(argument, variables)).toList(), "sourcePath", path, "line", start(creation)));
            }
            for (ExplicitConstructorInvocationStmt invocation : callable.findAll(ExplicitConstructorInvocationStmt.class)) {
                if (invocation.findAncestor(CallableDeclaration.class).orElse(null) != callable) continue;
                result.get("calls").add(row("from", id, "receiver", invocation.isThis() ? "this" : "super", "name", "<init>", "kind", "CONSTRUCTOR", "arity", invocation.getArguments().size(),
                        "argumentTypes", invocation.getArguments().stream().map(argument -> inferType(argument, variables)).toList(), "sourcePath", path, "line", start(invocation)));
            }
            for (MethodReferenceExpr reference : callable.findAll(MethodReferenceExpr.class)) {
                if (reference.findAncestor(CallableDeclaration.class).orElse(null) != callable) continue;
                result.get("calls").add(row("from", id, "receiver", reference.getScope().toString(), "name", reference.getIdentifier().equals("new") ? "<init>" : reference.getIdentifier(), "kind", "METHOD_REFERENCE",
                        "arity", -1, "argumentTypes", List.of(), "sourcePath", path, "line", start(reference)));
            }
            for (IfStmt branch : callable.findAll(IfStmt.class)) if (branch.findAncestor(CallableDeclaration.class).orElse(null) == callable)
                result.get("branches").add(row("methodId", id, "kind", "IF", "condition", limit(branch.getCondition().toString(), 1000), "effect", limit(branch.getThenStmt().toString(), 1600), "hasElse", branch.getElseStmt().isPresent(), "sourcePath", path, "startLine", start(branch), "endLine", end(branch)));
            for (SwitchEntry entry : callable.findAll(SwitchEntry.class)) if (entry.findAncestor(CallableDeclaration.class).orElse(null) == callable)
                result.get("branches").add(row("methodId", id, "kind", "SWITCH", "condition", entry.getLabels().isEmpty() ? "default" : limit(entry.getLabels().toString(), 1000), "effect", limit(entry.getStatements().toString(), 1600), "sourcePath", path, "startLine", start(entry), "endLine", end(entry)));
            for (ThrowStmt thrown : callable.findAll(ThrowStmt.class)) if (thrown.findAncestor(CallableDeclaration.class).orElse(null) == callable)
                result.get("branches").add(row("methodId", id, "kind", "THROW", "condition", "", "effect", limit(thrown.toString(), 1600), "sourcePath", path, "startLine", start(thrown), "endLine", end(thrown)));
            for (AnnotationExpr annotation : callable.getAnnotations()) if (Set.of("Select", "Insert", "Update", "Delete").contains(simple(annotation.getNameAsString()))) {
                Object sql = values(annotation).getOrDefault("value", "");
                String statement = sql instanceof List<?> list ? String.join(" ", list.stream().map(Object::toString).toList()) : sql.toString();
                result.get("mapperStatements").add(row("namespace", qualified, "id", callable.getNameAsString(), "methodId", id, "sql", limit(statement, 12000), "dynamic", statement.contains("${") || statement.contains("<script>"), "sourcePath", path, "startLine", start(annotation), "endLine", end(annotation)));
            }
        }
        for (AnnotationExpr annotation : unit.findAll(AnnotationExpr.class)) {
            String name = simple(annotation.getNameAsString()); if (!CONSTRAINTS.contains(name)) continue;
            String target = annotation.findAncestor(Parameter.class).map(Parameter::getNameAsString).orElseGet(() -> annotation.findAncestor(FieldDeclaration.class).map(field -> String.join(",", field.getVariables().stream().map(VariableDeclarator::getNameAsString).toList())).orElse(""));
            String owner = annotation.findAncestor(CallableDeclaration.class).map(JavaCodeParser::methodId).orElseGet(() -> annotation.findAncestor(TypeDeclaration.class).map(JavaCodeParser::qualified).orElse(""));
            result.get("constraints").add(row("owner", owner, "target", target, "annotation", name, "value", values(annotation).getOrDefault("value", ""), "attributes", values(annotation), "sourcePath", path, "line", start(annotation)));
        }
        return result;
    }
    public static Map<String, List<Map<String, Object>>> empty() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String key : List.of("classes", "methods", "calls", "endpoints", "models", "constraints", "branches", "mapperStatements")) result.put(key, new ArrayList<>());
        return result;
    }
    static String qualified(TypeDeclaration<?> type) { return type.getFullyQualifiedName().orElse(type.getNameAsString()); }
    static String methodId(CallableDeclaration<?> callable) {
        String signature = callable.getSignature().asString();
        if (callable instanceof ConstructorDeclaration) signature = "<init>" + signature.substring(signature.indexOf('('));
        return callable.findAncestor(TypeDeclaration.class).map(JavaCodeParser::qualified).orElse("") + "#" + signature;
    }
    private static void addType(Map<String, Set<String>> types, String name, String type) { types.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(type); }
    private String inferType(Expression expression, Map<String, String> variables) {
        if (expression instanceof NameExpr name) return variables.getOrDefault(name.getNameAsString(), "?");
        if (expression instanceof StringLiteralExpr || expression instanceof TextBlockLiteralExpr) return "String";
        if (expression instanceof IntegerLiteralExpr) return "int";
        if (expression instanceof LongLiteralExpr) return "long";
        if (expression instanceof DoubleLiteralExpr number) return number.getValue().toLowerCase(Locale.ROOT).endsWith("f") ? "float" : "double";
        if (expression instanceof BooleanLiteralExpr) return "boolean";
        if (expression instanceof NullLiteralExpr) return "null";
        if (expression instanceof ObjectCreationExpr creation) return creation.getTypeAsString();
        if (expression instanceof CastExpr cast) return cast.getTypeAsString();
        return "?";
    }
}
