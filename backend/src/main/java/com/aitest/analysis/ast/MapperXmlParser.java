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

/**
 * Reads MyBatis mapper statements. Dynamic SQL is rendered the way MyBatis would with every condition true
 * ({@code <include>} expanded, {@code <where>/<set>/<trim>} applied, loops once), so later stages see a parseable
 * statement with its tables instead of tag-stripped text. Only ${...} splicing leaves the SQL genuinely unknown.
 */
@Component
public final class MapperXmlParser {
    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete", "resultMap");
    private static final Set<String> CONDITIONAL = Set.of("if", "choose", "when", "otherwise", "foreach", "where", "set", "trim", "bind");

    /** {@code <sql>} fragments of every mapper in one source set, so an include may point at another file. */
    public static final class Fragments {
        private final Map<String, Element> byQualifiedId = new HashMap<>();
        public void collect(String source) {
            try {
                Element root = document(source).getDocumentElement();
                if (!root.getTagName().equals("mapper")) return;
                String namespace = root.getAttribute("namespace");
                for (Element fragment : children(root, "sql")) byQualifiedId.putIfAbsent(namespace + "." + fragment.getAttribute("id"), fragment);
            } catch (Exception unreadable) { /* The statement pass reports unreadable mappers. */ }
        }
    }

    public List<Map<String, Object>> parse(String path, String source, List<SourceDiagnostic> diagnostics) { return parse(path, source, diagnostics, new Fragments()); }
    public List<Map<String, Object>> parse(String path, String source, List<SourceDiagnostic> diagnostics, Fragments shared) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Document document = document(source);
            Element root = document.getDocumentElement();
            if (!root.getTagName().equals("mapper")) return result;
            if (document.getDoctype() != null && document.getDoctype().getEntities().getLength() > 0) {
                diagnostics.add(SourceDiagnostic.warning("MAPPER_ENTITY_REFERENCE", "BACKEND", path, "Mapper 声明了自定义实体，未展开或省略实体后推断 SQL"));
                return result;
            }
            String namespace = root.getAttribute("namespace");
            Map<String, Element> local = new HashMap<>();
            for (Element fragment : children(root, "sql")) local.putIfAbsent(fragment.getAttribute("id"), fragment);
            for (Element element : children(root, null)) {
                if (!STATEMENTS.contains(element.getTagName())) continue;
                String id = element.getAttribute("id");
                int offset = source.indexOf("id=\"" + id + "\"");
                if (offset < 0) offset = source.indexOf("id='" + id + "'");
                int line = offset < 0 ? 0 : (int) source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
                List<Map<String, Object>> mappings = new ArrayList<>();
                if (element.getTagName().equals("resultMap")) {
                    NodeList descendants = element.getChildNodes();
                    for (int child = 0; child < descendants.getLength(); child++) if (descendants.item(child) instanceof Element field && field.hasAttribute("column")) mappings.add(row("column", field.getAttribute("column"), "property", field.getAttribute("property")));
                }
                Renderer renderer = new Renderer(namespace, local, shared.byQualifiedId);
                String sql = element.getTagName().equals("resultMap") ? element.getTextContent().strip() : renderer.render(element, 0).replaceAll("\\s+", " ").strip();
                boolean substitution = sql.contains("${");
                result.add(row("namespace", namespace, "id", id, "kind", element.getTagName(), "sql", limit(sql, 12000), "dynamic", renderer.dynamic,
                        "stringSubstitution", substitution, "unresolvedIncludes", List.copyOf(renderer.unresolved), "resultType", element.getAttribute("resultType"), "resultMap", element.getAttribute("resultMap"),
                        "mappings", mappings, "sourcePath", path, "startLine", line, "endLine", line));
                // Conditional SQL is normal MyBatis; it is noted, not counted as an analysis gap.
                if (renderer.dynamic) diagnostics.add(new SourceDiagnostic("INFO", "DYNAMIC_MAPPER_SQL", "BACKEND", path, line, "语句含 <if>/<foreach> 等动态标签，已按条件全部成立展开用于分析；实际执行的 SQL 随参数变化"));
                if (substitution) diagnostics.add(new SourceDiagnostic("WARNING", "MAPPER_STRING_SUBSTITUTION", "BACKEND", path, line, "语句用 ${...} 直接拼接 SQL 文本，表名或列要到运行时才能确定，且有 SQL 注入风险，需要结合执行参数验证"));
                if (!renderer.unresolved.isEmpty()) diagnostics.add(new SourceDiagnostic("WARNING", "MAPPER_INCLUDE_UNRESOLVED", "BACKEND", path, line, "引用的 SQL 片段 " + String.join("、", renderer.unresolved) + " 不在已采集的 Mapper 文件中，语句内容不完整"));
            }
        } catch (Exception invalid) { diagnostics.add(new SourceDiagnostic("ERROR", "MAPPER_PARSE_ERROR", "BACKEND", path, 0, "无法安全解析 Mapper XML：" + invalid.getClass().getSimpleName())); }
        return result;
    }

    private static Document document(String source) throws Exception {
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
        return builder.parse(new InputSource(new StringReader(source)));
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && (tag == null || element.getTagName().equals(tag))) result.add(element);
        return result;
    }

    /** One statement's rendering; records whether conditional tags occurred and which includes could not be found. */
    private static final class Renderer {
        private final String namespace;
        private final Map<String, Element> local, shared;
        private final Set<String> unresolved = new LinkedHashSet<>();
        private boolean dynamic;
        private Renderer(String namespace, Map<String, Element> local, Map<String, Element> shared) { this.namespace = namespace; this.local = local; this.shared = shared; }

        private String render(Node parent, int depth) {
            StringBuilder out = new StringBuilder();
            for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) out.append(node(node, depth));
            return out.toString();
        }
        private String node(Node node, int depth) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) return node.getNodeValue();
            if (!(node instanceof Element element)) return "";
            String tag = element.getTagName();
            if (CONDITIONAL.contains(tag)) dynamic = true;
            return switch (tag) {
                case "include" -> include(element, depth);
                // selectKey is a separate statement run before or after this one; bind only declares a variable.
                case "bind", "selectKey" -> " ";
                case "choose" -> {
                    List<Element> branches = children(element, "when");
                    if (branches.isEmpty()) branches = children(element, "otherwise");
                    yield branches.isEmpty() ? " " : render(branches.getFirst(), depth);
                }
                case "where" -> clause("WHERE", overrides(render(element, depth), "AND|OR", ""));
                case "set" -> clause("SET", overrides(render(element, depth), ",", ","));
                case "trim" -> {
                    String body = overrides(render(element, depth), element.getAttribute("prefixOverrides"), element.getAttribute("suffixOverrides"));
                    yield body.isBlank() ? " " : " " + element.getAttribute("prefix") + " " + body + " " + element.getAttribute("suffix") + " ";
                }
                case "foreach" -> " " + element.getAttribute("open") + render(element, depth) + element.getAttribute("close") + " ";
                default -> render(element, depth);
            };
        }
        private String include(Element element, int depth) {
            String refid = element.getAttribute("refid");
            Element fragment = depth < 8 ? fragment(refid) : null;
            if (fragment == null) { unresolved.add(refid); return " "; }
            return " " + render(fragment, depth + 1) + " ";
        }
        private Element fragment(String refid) {
            Element found = local.get(refid);
            if (found == null && refid.startsWith(namespace + ".")) found = local.get(refid.substring(namespace.length() + 1));
            if (found == null) found = shared.get(refid.contains(".") ? refid : namespace + "." + refid);
            return found;
        }
        private static String clause(String keyword, String body) { return body.isBlank() ? " " : " " + keyword + " " + body + " "; }
        /** MyBatis prefix/suffix overrides: '|'-separated, case-insensitive, and a word only matches as a whole word. */
        private static String overrides(String body, String prefixes, String suffixes) {
            String result = body.strip();
            for (String token : prefixes.split("\\|")) {
                String value = token.strip();
                if (value.isEmpty() || !result.regionMatches(true, 0, value, 0, value.length())) continue;
                boolean word = Character.isLetter(value.charAt(value.length() - 1));
                if (word && result.length() > value.length() && (Character.isLetterOrDigit(result.charAt(value.length())) || result.charAt(value.length()) == '_')) continue;
                result = result.substring(value.length()).strip(); break;
            }
            for (String token : suffixes.split("\\|")) {
                String value = token.strip();
                if (value.isEmpty() || result.length() < value.length() || !result.regionMatches(true, result.length() - value.length(), value, 0, value.length())) continue;
                result = result.substring(0, result.length() - value.length()).strip(); break;
            }
            return result;
        }
    }
}
