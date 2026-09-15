package com.aitest.analysis.impact;

import com.aitest.analysis.SourceDiagnostic;
import com.aitest.analysis.ast.*;
import com.aitest.analysis.source.SourceFile;
import com.aitest.execution.Values;
import org.springframework.stereotype.Component;
import java.util.*;

/** Older snapshots keep their bytes and original parse result; a new impact report may apply a newer parser to those same bytes. */
@Component
public final class CapturedBackendReader {
    private final JavaCodeParser java; private final MapperXmlParser xml;
    public CapturedBackendReader(JavaCodeParser java, MapperXmlParser xml) { this.java = java; this.xml = xml; }
    public Map<String, Object> read(Map<String, Object> snapshot, String member, List<SourceFile> files, String version, List<Map<String, Object>> diagnostics, Runnable checkpoint) {
        if (JavaCodeParser.FORMAT_VERSION.equals(snapshot.get("javaAstFormatVersion"))) return Values.map(snapshot.get(member));
        Map<String, List<Map<String, Object>>> ast = JavaCodeParser.empty();
        for (SourceFile file : files) {
            checkpoint.run(); List<SourceDiagnostic> errors = new ArrayList<>();
            if (file.path().toLowerCase(Locale.ROOT).endsWith(".java")) java.parse(file.path(), file.content(), errors).forEach((key, values) -> ast.get(key).addAll(values));
            else ast.get("mapperStatements").addAll(xml.parse(file.path(), file.content(), errors));
            for (SourceDiagnostic error : errors) diagnostics.add(Map.of("code", error.code(), "sourceVersion", version, "sourcePath", error.path(), "line", error.line(), "message", error.message()));
        }
        return new LinkedHashMap<>(ast);
    }
}
