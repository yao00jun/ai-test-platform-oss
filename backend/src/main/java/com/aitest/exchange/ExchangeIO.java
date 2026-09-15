package com.aitest.exchange;

import com.aitest.common.JsonCodec;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.resolver.Resolver;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ExchangeIO {
    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final int MAX_EXPANDED_BYTES = 64 * 1024 * 1024;
    public static final int MAX_FILES = 256;
    public static final int MAX_NODES = 20_000;
    public static final int MAX_ROWS = 100_000;
    private ExchangeIO() { }

    public static String utf8(byte[] bytes, String source) {
        checkSize(bytes, source);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (CharacterCodingException e) { throw error(source, 1, "$file", "文本文件必须使用 UTF-8 编码"); }
    }
    public static void checkSize(byte[] bytes, String source) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw error(source, 1, "$file", "文件必须为 1 字节至 32 MB");
    }
    public static Object json(String text, String source) {
        try {
            ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(8_000_000).build());
            return mapper.readValue(text, Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            int row = e.getLocation() == null ? 1 : Math.max(1, e.getLocation().getLineNr());
            throw error(source, row, "$json", "JSON 语法无效、键重复、层级过深或存在尾随内容");
        }
    }
    public static Object yaml(String text, String source) {
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(0); options.setNestingDepthLimit(100); options.setCodePointLimit(MAX_BYTES);
        try {
            // SafeConstructor prohibits object construction. Exclude YAML 1.1 dates/booleans that silently change IDs and strings.
            Resolver resolver = new Resolver() {
                @Override protected void addImplicitResolvers() {
                    addImplicitResolver(Tag.BOOL, java.util.regex.Pattern.compile("^(?:true|false)$"), "tf");
                    addImplicitResolver(Tag.NULL, NULL, "~nN\0");
                    addImplicitResolver(Tag.INT, java.util.regex.Pattern.compile("^[-+]?(?:0|[1-9][0-9]*)$"), "-+0123456789");
                    addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
                    addImplicitResolver(Tag.MERGE, MERGE, "<");
                }
            };
            var yaml = new Yaml(new SafeConstructor(options), new org.yaml.snakeyaml.representer.Representer(new DumperOptions()), new DumperOptions(), options, resolver);
            Object value = yaml.load(text);
            return normalizeYaml(value, source, 0);
        } catch (ExchangeException e) { throw e; }
        catch (org.yaml.snakeyaml.error.MarkedYAMLException e) {
            int row = e.getProblemMark() == null ? 1 : e.getProblemMark().getLine() + 1;
            throw error(source, row, "$yaml", "YAML 语法无效、键重复，或使用了不支持的标签/别名");
        } catch (RuntimeException e) { throw error(source, 1, "$yaml", "YAML 格式、别名或层级超出安全限制"); }
    }
    private static Object normalizeYaml(Object value, String source, int depth) {
        if (depth > 100) throw error(source, 1, "$yaml", "YAML 嵌套超过 100 层");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw error(source, 1, "$yaml", "YAML 对象键必须是字符串（状态码等数字键请加引号）");
                result.put(entry.getKey().toString(), normalizeYaml(entry.getValue(), source, depth + 1));
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(v -> normalizeYaml(v, source, depth + 1)).toList();
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw error(source, 1, "$yaml", "YAML 仅接受 JSON 兼容的值类型");
    }
    public static String yaml(Object value, JsonCodec json) {
        DumperOptions options = new DumperOptions(); options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true); options.setAllowUnicode(true); options.setWidth(120); options.setSplitLines(false);
        return new Yaml(options).dump(json.tree(json.write(value)));
    }
    public static Map<String, byte[]> unzip(byte[] bytes, String source) {
        checkSize(bytes, source);
        Map<String, byte[]> files = new LinkedHashMap<>(); int total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++entries > MAX_FILES || name.isBlank() || name.startsWith("/") || name.contains("\\") || name.indexOf(':') >= 0
                        || java.util.Arrays.stream(name.split("/")).anyMatch(part -> part.equals("..") || part.equals(".")) || name.chars().anyMatch(Character::isISOControl))
                    throw error(source, 1, "$zip", "ZIP 文件条目过多或包含非法路径");
                if (entry.isDirectory()) continue;
                if (files.containsKey(name)) throw error(source, 1, "$zip", "ZIP 内存在重复文件名");
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_EXPANDED_BYTES || out.size() + count > MAX_BYTES) throw error(source, 1, "$zip", "ZIP 解压后总量超过 64 MB 或单文件超过 32 MB");
                    out.write(buffer, 0, count);
                }
                files.put(name, out.toByteArray());
            }
        } catch (IOException e) { throw error(source, 1, "$zip", "ZIP 文件损坏或使用了不支持的压缩/加密格式"); }
        if (files.isEmpty()) throw error(source, 1, "$zip", "ZIP 没有可读取的文件");
        return files;
    }
    public static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var file : entries.entrySet()) { var entry = new ZipEntry(file.getKey()); entry.setTime(0); zip.putNextEntry(entry); zip.write(file.getValue()); zip.closeEntry(); }
            zip.finish(); return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException("Cannot build exchange archive", e); }
    }
    @SuppressWarnings("unchecked") public static Map<String, Object> map(Object value, String source, int row, String field) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String))) throw error(source, row, field, "此字段必须是对象");
        return (Map<String, Object>) value;
    }
    public static String string(Object value, String source, int row, String field) {
        if (!(value instanceof String text)) throw error(source, row, field, "此字段必须是文本"); return text;
    }
    public static ExchangeException error(String source, int row, String field, String message) { return new ExchangeException(source, row, field, message); }
}
