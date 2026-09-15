package com.aitest.analysis.ast;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.*;
import static com.aitest.analysis.ast.AstValues.*;

final class DataModelVisitor {
    List<Map<String, Object>> read(TypeDeclaration<?> owner, String qualified, String path) {
        AnnotationExpr table = owner.getAnnotations().stream().filter(annotation -> Set.of("TableName", "Table").contains(simple(annotation.getNameAsString()))).findFirst().orElse(null);
        if (table == null && owner.getAnnotations().stream().noneMatch(annotation -> simple(annotation.getNameAsString()).equals("Entity"))) return List.of();
        Map<String, Object> attrs = table == null ? Map.of() : values(table);
        String tableName = Objects.toString(attrs.getOrDefault("name", attrs.getOrDefault("value", owner.getNameAsString())));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (FieldDeclaration field : owner.getFields()) {
            if (field.hasModifier(Modifier.Keyword.STATIC) || field.getAnnotations().stream().anyMatch(annotation -> Set.of("Transient").contains(simple(annotation.getNameAsString())) || Boolean.FALSE.equals(values(annotation).get("exist")))) continue;
            AnnotationExpr column = field.getAnnotations().stream().filter(annotation -> Set.of("Column", "TableField", "TableId").contains(simple(annotation.getNameAsString()))).findFirst().orElse(null);
            Map<String, Object> columnAttrs = column == null ? Map.of() : values(column);
            for (VariableDeclarator variable : field.getVariables()) columns.add(row("field", variable.getNameAsString(), "column", columnAttrs.getOrDefault("name", columnAttrs.getOrDefault("value", variable.getNameAsString())), "type", variable.getTypeAsString(), "annotations", annotations(field), "implicitName", column == null || columnAttrs.isEmpty(), "line", start(field)));
        }
        return List.of(row("className", qualified, "table", tableName, "implicitName", table == null || attrs.isEmpty(), "columns", columns, "sourcePath", path, "startLine", start(owner), "endLine", end(owner)));
    }
}
