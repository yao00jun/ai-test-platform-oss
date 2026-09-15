package com.aitest.analysis.ast;

import com.aitest.analysis.SourceDiagnostic;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import static com.aitest.analysis.ast.AstValues.*;

@Component
public final class MapperXmlParser {
    public List<Map<String, Object>> parse(String path, String source, List<SourceDiagnostic> diagnostics) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false); factory.setXIncludeAware(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            });
            Document document = builder.parse(new InputSource(new StringReader(source)));
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("mapper")) return result;
            if (document.getDoctype() != null && document.getDoctype().getEntities().getLength() > 0) {
                diagnostics.add(SourceDiagnostic.warning("MAPPER_ENTITY_REFERENCE", "BACKEND", path, "Mapper 声明了自定义实体，未展开或省略实体后推断 SQL"));
                return result;
            }
            String namespace = root.getAttribute("namespace");
            NodeList nodes = root.getChildNodes();
            for (int index = 0; index < nodes.getLength(); index++) if (nodes.item(index) instanceof Element element && Set.of("select", "insert", "update", "delete", "resultMap").contains(element.getTagName())) {
                String id = element.getAttribute("id");
                boolean dynamic = element.getElementsByTagName("if").getLength() > 0 || element.getElementsByTagName("choose").getLength() > 0 || element.getElementsByTagName("foreach").getLength() > 0 || element.getElementsByTagName("include").getLength() > 0 || element.getTextContent().contains("${");
                int offset = source.indexOf("id=\"" + id + "\"");
                if (offset < 0) offset = source.indexOf("id='" + id + "'");
                int line = offset < 0 ? 0 : (int) source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
                List<Map<String, Object>> mappings = new ArrayList<>();
                if (element.getTagName().equals("resultMap")) {
                    NodeList descendants = element.getChildNodes();
                    for (int child = 0; child < descendants.getLength(); child++) if (descendants.item(child) instanceof Element field && field.hasAttribute("column")) mappings.add(row("column", field.getAttribute("column"), "property", field.getAttribute("property")));
                }
                result.add(row("namespace", namespace, "id", id, "kind", element.getTagName(), "sql", limit(element.getTextContent().strip(), 12000), "dynamic", dynamic, "resultType", element.getAttribute("resultType"), "resultMap", element.getAttribute("resultMap"), "mappings", mappings, "sourcePath", path, "startLine", line, "endLine", line));
                if (dynamic) diagnostics.add(new SourceDiagnostic("WARNING", "DYNAMIC_MAPPER_SQL", "BACKEND", path, line, "Mapper 包含条件、引用或动态 SQL，需要结合执行参数验证"));
            }
        } catch (Exception invalid) { diagnostics.add(new SourceDiagnostic("ERROR", "MAPPER_PARSE_ERROR", "BACKEND", path, 0, "无法安全解析 Mapper XML：" + invalid.getClass().getSimpleName())); }
        return result;
    }
}
