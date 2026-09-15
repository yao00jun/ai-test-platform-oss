package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.JsonCodec;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aitest.exchange.ExchangeIO.*;

@Component
public class XMindCodec implements ExportCodec {
    private final JsonCodec json;
    private final PortableBundleCodec portable;
    public XMindCodec(JsonCodec json, PortableBundleCodec portable) { this.json = json; this.portable = portable; }
    @Override public Set<AssetType> assetTypes() { return Set.of(AssetType.FUNCTIONAL_CASE); }
    @Override public Set<String> formats() { return Set.of("xmind"); }
    @Override public ExportFile export(ExportContext context, String format) { return encode(context.bundle(), "functional_cases"); }
    public ExportFile encode(ExchangeBundle bundle, String name) {
        Map<String, List<ExchangeNode>> children = new LinkedHashMap<>();
        for (var node : bundle.nodes()) children.computeIfAbsent(node.parentKey() == null ? "" : node.parentKey(), ignored -> new ArrayList<>()).add(node);
        children.values().forEach(list -> list.sort(Comparator.comparingInt(ExchangeNode::position)));
        var root = topic("root", "功能测试用例"); root.put("children", Map.of("attached", children.getOrDefault("", List.of()).stream().map(node -> topic(node, children)).toList()));
        root.put("aitestBundle", Map.of("formatVersion", bundle.formatVersion(), "metadata", bundle.metadata(), "externalReferences", bundle.externalReferences(), "warnings", bundle.warnings()));
        var sheet = new LinkedHashMap<String, Object>(); sheet.put("id", "sheet_1"); sheet.put("class", "sheet"); sheet.put("title", "功能用例"); sheet.put("rootTopic", root);
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put("content.json", json.write(List.of(sheet)).getBytes(StandardCharsets.UTF_8));
        files.put("manifest.json", "{\"file-entries\":{\"content.json\":{},\"metadata.json\":{}}}".getBytes(StandardCharsets.UTF_8));
        files.put("metadata.json", "{\"creator\":{\"name\":\"AI Test Platform\",\"version\":\"1\"}}".getBytes(StandardCharsets.UTF_8));
        files.put("README.md", ("# XMind 功能用例 v1\n\n支持现代 content.json 与经典 content.xml。普通主题语法：模块: 名称 → 用例: 名称 → 步骤 1: 操作 → 预期: 结果。用例可附优先级: P0、前置条件: ...、备注: ...。导出的主题逐字段可编辑；aitest 元信息只记录本地键、顺序和引用。\n").getBytes(StandardCharsets.UTF_8));
        return new ExportFile(name + ".xmind", "application/vnd.xmind.workbook", ExchangeIO.zip(files));
    }
    private Map<String, Object> topic(ExchangeNode node, Map<String, List<ExchangeNode>> children) {
        Map<String, Object> topic = topic(node.key(), node.type().label() + ": " + node.name());
        var structure = new LinkedHashMap<String, Object>(); structure.put("key", node.key()); structure.put("type", node.type()); structure.put("parentKey", node.parentKey()); structure.put("position", node.position()); structure.put("references", node.references()); topic.put("aitest", structure);
        List<Map<String, Object>> attached = new ArrayList<>(); int index = 0;
        for (var field : node.data().entrySet()) {
            Object value = field.getValue(); boolean text = value instanceof String;
            String label = node.type().fields().stream().filter(f -> f.key().equals(field.getKey())).map(com.aitest.asset.FieldDefinition::label).findFirst().orElse(field.getKey());
            var child = topic(node.key() + "_field_" + ++index, label + ": " + (text ? value : json.write(value)));
            child.put("aitestField", field.getKey()); child.put("valueKind", text ? "text" : "json"); attached.add(child);
        }
        for (var child : children.getOrDefault(node.key(), List.of())) attached.add(topic(child, children));
        topic.put("children", Map.of("attached", attached)); return topic;
    }
    private static Map<String, Object> topic(String id, String title) { var topic = new LinkedHashMap<String, Object>(); topic.put("id", id); topic.put("class", "topic"); topic.put("title", title); return topic; }
    public ParsedExchange parse(String source, byte[] bytes) {
        var files = unzip(bytes, source); List<Map<String, Object>> roots;
        if (files.containsKey("content.json")) {
            Object content = ExchangeIO.json(utf8(files.get("content.json"), source), source);
            if (!(content instanceof List<?> sheets) || sheets.isEmpty()) throw error(source, 1, "content.json", "现代 XMind content.json 必须是非空 sheet 数组");
            roots = new ArrayList<>();
            for (Object item : sheets) roots.add(map(map(item, source, 1, "sheet").get("rootTopic"), source, 1, "rootTopic"));
        } else if (files.containsKey("content.xml")) roots = xml(files.get("content.xml"), source);
        else throw error(source, 1, "$xmind", "XMind 必须包含现代 content.json 或经典 content.xml；加密和其他变体不受支持");
        Map<String, Object> manifest = new LinkedHashMap<>(); List<Map<String, Object>> nodes = new ArrayList<>(); List<ExchangeIssue> errors = new ArrayList<>();
        manifest.put("formatVersion", ExchangeBundle.VERSION);
        for (var root : roots) {
            if (root.containsKey("aitestBundle")) manifest.putAll(map(root.get("aitestBundle"), source, 1, "aitestBundle"));
            for (var child : children(root, source)) try { readTopic(child, null, nodes, source, 0); } catch (ExchangeException e) { errors.add(e.issue()); }
        }
        manifest.put("nodes", nodes); var parsed = portable.parseObject(source, manifest); errors.addAll(parsed.errors()); return new ParsedExchange(parsed.bundle(), errors);
    }
    private void readTopic(Map<String, Object> topic, String parent, List<Map<String, Object>> nodes, String source, int depth) {
        if (depth > 40 || nodes.size() > MAX_NODES) throw error(source, nodes.size() + 1, "$xmind", "XMind 超过 40 层或 20000 个资产");
        String title = string(topic.get("title"), source, nodes.size() + 1, "title"); List<Map<String, Object>> children = children(topic, source);
        if (topic.containsKey("aitest")) {
            var node = new LinkedHashMap<>(map(topic.get("aitest"), source, nodes.size() + 1, "aitest"));
            int split = title.indexOf(": "); if (split < 0) throw error(source, nodes.size() + 1, "title", "版本化主题标题需要 类型: 名称");
            node.put("name", title.substring(split + 2)); Map<String, Object> data = new LinkedHashMap<>(); node.put("data", data); nodes.add(node);
            for (var child : children) {
                if (child.get("aitestField") instanceof String field) {
                    String text = string(child.get("title"), source, nodes.size(), "title"); int colon = text.indexOf(": ");
                    if (colon < 0 || data.containsKey(field)) throw error(source, nodes.size(), field, "字段主题缺少值或重复");
                    data.put(field, "json".equals(child.get("valueKind")) ? ExchangeIO.json(text.substring(colon + 2), source) : text.substring(colon + 2));
                } else readTopic(child, String.valueOf(node.get("key")), nodes, source, depth + 1);
            }
            if (parent != null && !parent.equals(node.get("parentKey"))) throw error(source, nodes.size(), "parentKey", "XMind 可见层级与本地父引用不一致");
            return;
        }
        String key = "topic_" + (nodes.size() + 1); Map<String, Object> data = new LinkedHashMap<>(); AssetType type; String name; int position = nodes.size(); List<Map<String, Object>> nested = new ArrayList<>();
        if (prefix(title, "模块")) { type = AssetType.MODULE; name = after(title); nested.addAll(children); }
        else if (prefix(title, "用例") || prefix(title, "功能用例")) {
            type = AssetType.FUNCTIONAL_CASE; name = after(title);
            for (var child : children) {
                String text = string(child.get("title"), source, nodes.size() + 1, "title");
                if (prefix(text, "优先级")) data.put("priority", after(text));
                else if (prefix(text, "前置条件")) data.put("precondition", after(text));
                else if (prefix(text, "备注")) data.put("remark", after(text));
                else if (text.matches("(?s)^步骤\\s*\\d+\\s*[:：].*")) nested.add(child);
                else throw error(source, nodes.size() + 1, "topic", "用例子主题支持优先级、前置条件、备注和编号步骤");
            }
            if (topic.get("notes") instanceof Map<?, ?> notes && notes.get("plain") instanceof Map<?, ?> plain && plain.get("content") instanceof String text) data.putIfAbsent("remark", text);
        } else if (title.matches("(?s)^步骤\\s*\\d+\\s*[:：].*")) {
            type = AssetType.FUNCTIONAL_STEP; var matcher = java.util.regex.Pattern.compile("^步骤\\s*(\\d+)\\s*[:：]\\s*(.*)$", java.util.regex.Pattern.DOTALL).matcher(title); matcher.matches();
            position = Integer.parseInt(matcher.group(1)) - 1; name = "步骤 " + (position + 1); data.put("step", matcher.group(2));
            for (var child : children) {
                String text = string(child.get("title"), source, nodes.size() + 1, "title");
                if (prefix(text, "预期") || prefix(text, "预期结果")) { if (data.containsKey("expected")) throw error(source, nodes.size() + 1, "expected", "一个步骤只能有一个预期主题"); data.put("expected", after(text)); }
                else throw error(source, nodes.size() + 1, "topic", "步骤子主题只支持预期: 结果");
            }
            if (!data.containsKey("expected")) throw error(source, nodes.size() + 1, "expected", "步骤缺少预期: 结果子主题");
        } else throw error(source, nodes.size() + 1, "title", "主题需要模块: 名称、用例: 名称或步骤 1: 操作前缀");
        var node = new LinkedHashMap<String, Object>(); node.put("key", key); node.put("type", type.name()); node.put("name", name); node.put("parentKey", parent); node.put("position", position); node.put("data", data); node.put("references", Map.of()); nodes.add(node);
        for (var child : nested) readTopic(child, key, nodes, source, depth + 1);
    }
    private static boolean prefix(String text, String prefix) { return text.startsWith(prefix + ":") || text.startsWith(prefix + "："); }
    private static String after(String text) { int colon = text.indexOf(':'); if (colon < 0) colon = text.indexOf('：'); return text.substring(colon + 1).stripLeading(); }
    private static List<Map<String, Object>> children(Map<String, Object> topic, String source) {
        if (!topic.containsKey("children")) return List.of(); var children = map(topic.get("children"), source, 1, "children");
        for (var entry : children.entrySet()) if (!entry.getKey().equals("attached") && entry.getValue() instanceof List<?> list && !list.isEmpty()) throw error(source, 1, "children." + entry.getKey(), "只支持 attached 层级主题；请先整理自由主题/摘要主题");
        Object attached = children.getOrDefault("attached", List.of()); if (!(attached instanceof List<?> list)) throw error(source, 1, "children.attached", "主题子节点必须是数组");
        return list.stream().map(v -> map(v, source, 1, "topic")).toList();
    }
    private static List<Map<String, Object>> xml(byte[] bytes, String source) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException { throw e; } });
            var document = builder.parse(new ByteArrayInputStream(bytes)); Element root = document.getDocumentElement();
            if (!local(root).equals("xmap-content")) throw error(source, 1, "content.xml", "经典 XMind 需要 xmap-content 根节点");
            List<Map<String, Object>> roots = new ArrayList<>();
            for (Element sheet : direct(root, "sheet")) for (Element topic : direct(sheet, "topic")) roots.add(xmlTopic(topic, source, 0));
            if (roots.isEmpty()) throw error(source, 1, "content.xml", "没有找到 XMind sheet/topic"); return roots;
        } catch (ExchangeException e) { throw e; }
        catch (Exception e) { throw error(source, 1, "content.xml", "XMind XML 无效；禁止 DTD、外部实体和外部 Schema"); }
    }
    private static Map<String, Object> xmlTopic(Element element, String source, int depth) {
        if (depth > 45) throw error(source, 1, "content.xml", "XMind 主题嵌套过深"); List<Element> titles = direct(element, "title");
        if (titles.size() != 1) throw error(source, 1, "title", "XMind 主题需要一个 title");
        var topic = topic(element.getAttribute("id"), titles.getFirst().getTextContent()); List<Map<String, Object>> children = new ArrayList<>();
        for (Element holder : direct(element, "children")) for (Element group : direct(holder, "topics")) {
            if (!group.getAttribute("type").isBlank() && !group.getAttribute("type").equals("attached")) throw error(source, 1, "topics.type", "经典 XMind 仅支持 attached 主题");
            for (Element child : direct(group, "topic")) children.add(xmlTopic(child, source, depth + 1));
        }
        topic.put("children", Map.of("attached", children)); return topic;
    }
    private static List<Element> direct(Element parent, String name) { List<Element> result = new ArrayList<>(); for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element element && local(element).equals(name)) result.add(element); return result; }
    private static String local(Element element) { return element.getLocalName() == null ? element.getTagName() : element.getLocalName(); }
}
