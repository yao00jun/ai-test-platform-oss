package com.aitest.exchange.codegen;

import com.aitest.asset.*;
import com.aitest.common.JsonCodec;
import com.aitest.common.SecretProtector;
import com.aitest.exchange.*;
import com.aitest.storage.FileStorageService;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

@Component
public final class JavaProjectExporter implements ExportCodec {
    private final JsonCodec json;
    private final FileStorageService files;
    public JavaProjectExporter(JsonCodec json, FileStorageService files) { this.json = json; this.files = files; }
    public Set<AssetType> assetTypes() { return Set.of(AssetType.UI_SCENARIO, AssetType.UI_STEP); }
    public Set<String> formats() { return Set.of("java", "java-project"); }
    public ExportFile export(ExportContext context, String format) {
        boolean project = format.equals("java-project");
        Map<String, byte[]> archive = new LinkedHashMap<>();
        List<Map<String, Object>> scenarios = new ArrayList<>(); Set<String> inputs = new TreeSet<>();
        var selected = new HashSet<>(context.selectedIds());
        List<Asset> parents = context.assets().stream().filter(asset -> asset.type() == AssetType.UI_SCENARIO)
                .filter(asset -> context.type() == AssetType.UI_STEP || selected.isEmpty() || selected.contains(asset.id())).toList();
        if (parents.isEmpty()) throw ExchangeIO.error("export", 1, "assetIds", "请至少选择一个 UI 场景或该场景中的步骤");
        for (Asset parent : parents) {
            List<Asset> children = context.assets().stream().filter(asset -> asset.type() == AssetType.UI_STEP && parent.id().equals(asset.parentId()))
                    .filter(asset -> context.type() == AssetType.UI_SCENARIO || selected.isEmpty() || selected.contains(asset.id()))
                    .sorted(Comparator.comparingInt(Asset::position).thenComparing(Asset::id)).toList();
            if (children.isEmpty()) throw ExchangeIO.error("export", 1, "steps", "空 UI 场景不能导出为可执行测试");
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Asset child : children) {
                Map<String, Object> data = new LinkedHashMap<>(child.data());
                if ("upload".equals(data.get("action"))) {
                    List<Map<String, Object>> uploads = new ArrayList<>();
                    for (Object raw : (List<?>) data.getOrDefault("fileIds", List.of())) {
                        String id = raw.toString(); var file = files.get(context.projectId(), id); String entry = "attachments/" + id + "/content";
                        if (project) try {
                            if (archive.size() >= 240 || archive.values().stream().mapToLong(bytes -> bytes.length).sum() + file.size() > ExchangeIO.MAX_EXPANDED_BYTES)
                                throw ExchangeIO.error("export", 1, "fileIds", "附件超过 240 项或 64 MB，请拆分工程");
                            archive.putIfAbsent(entry, Files.readAllBytes(file.path()));
                        } catch (IOException failure) { throw ExchangeIO.error("export", 1, "fileIds", "受管上传附件无法读取，请重新绑定"); }
                        uploads.add(Map.of("id", id, "name", file.name(), "mediaType", file.mediaType(), "path", project ? entry : ""));
                    }
                    data.put("uploads", uploads); data.remove("fileIds");
                }
                var safe = replaceMasks(data, "secret_" + child.id(), inputs, new int[]{0});
                steps.add(Map.of("id", child.id(), "name", child.name(), "data", safe));
            }
            scenarios.add(Map.of("id", parent.id(), "name", parent.name(), "data", replaceMasks(parent.data(), "secret_" + parent.id(), inputs, new int[]{0}), "steps", steps));
        }
        String payload = Base64.getEncoder().encodeToString(json.write(scenarios).getBytes(StandardCharsets.UTF_8));
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < payload.length(); start += 16000) chunks.add("        \"" + payload.substring(start, Math.min(payload.length(), start + 16000)) + "\"");
        String template;
        try (var stream = getClass().getResourceAsStream("/export/StandalonePlaywright.java.tpl")) {
            if (stream == null) throw new IllegalStateException("Missing standalone runner template");
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Cannot read standalone runner", failure); }
        byte[] source = template.replace("/*__RECORDED_SCENARIOS__*/", String.join(",\n", chunks)).getBytes(StandardCharsets.UTF_8);
        if (!project) return new ExportFile("ExportedTest.java", "text/x-java-source; charset=utf-8", source);
        archive.put("src/main/java/ExportedTest.java", source);
        archive.put("bundle.json", bytes(json.write(context.bundle())));
        archive.put("pom.xml", bytes("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion><groupId>com.aitest.export</groupId><artifactId>exported-ui-test</artifactId><version>1.0.0</version>
                  <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                  <dependencies><dependency><groupId>com.microsoft.playwright</groupId><artifactId>playwright</artifactId><version>1.62.0</version></dependency></dependencies>
                  <build><plugins>
                    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                    <plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><version>3.6.3</version></plugin>
                  </plugins></build>
                </project>
                """));
        String inputNotes = inputs.isEmpty() ? "" : "\n导出中已移除的凭证需要以下运行变量（不包含原值）：\n" + String.join("\n", inputs.stream().map(input -> "- `" + input + "`").toList()) + "\n";
        archive.put("README.md", bytes("""
                # 独立 Playwright Java 测试

                此工程使用 Java 21 和官方 Playwright 1.62.0，不依赖 AI-Test-Platform 服务、Spring 或其数据库。
                在工程根目录执行：

                ```powershell
                mvn compile
                mvn exec:java '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
                mvn exec:java '-Dexec.mainClass=ExportedTest' '-Daitest.var.username=测试员'
                ```

                如场景选择 Firefox/WebKit，请将安装命令中的 chromium 换为相应浏览器。
                也可用 Maven 构造依赖 classpath 后通过 java ExportedTest 直接启动。

                `${name}` 从 `-Daitest.var.name=value` 或 `-Daitest.variables=variables.json` 读取；JSON 支持嵌套对象和类型。
                敏感值宜放在仅当前用户可读的 variables.json，避免进入终端历史。未提供必需变量会明确失败。
                `-Daitest.baseUrl=http://localhost:3000` 可覆盖所有场景基础地址。
                `-Daitest.headers=headers.json` 可提供运行所需的公共 HTTP 请求头。
                `-Daitest.headless=false` 可显示浏览器。保持在工程根目录运行以读取随包附件。
                单文件 Java 导出的上传步骤需要 `-Daitest.file.附件ID=本机文件路径`；工程包已包含可用附件。

                每个场景使用独立 BrowserContext，步骤使用原 ID、顺序、页面别名、frame 和精确匹配选项。
                任一失败使进程非零退出；失败后是否继续按场景设置。场景总超时会终止此次独立运行并清理浏览器子进程。
                结果和失败截图写入 `test-results/`（可用 `-Daitest.results=目录` 覆盖）。截图会遮罩密码及已识别的敏感定位器。
                导出是当前资产版本的快照；在平台继续编辑不会改变本工程。
                `bundle.json` 是可重新导入平台的资产结构；Java 源码是独立运行器，不属于 Codegen 录制语法子集。
                """ + inputNotes));
        return new ExportFile("playwright-java-project.zip", "application/zip", ExchangeIO.zip(archive));
    }
    private Object replaceMasks(Object value, String prefix, Set<String> inputs, int[] index) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, item) -> result.put(key.toString(), replaceMasks(item, prefix, inputs, index))); return result; }
        if (value instanceof List<?> list) return list.stream().map(item -> replaceMasks(item, prefix, inputs, index)).toList();
        if (value instanceof String text && text.contains(SecretProtector.MASK)) {
            String input = prefix + "_" + index[0]++; inputs.add(input); return text.replace(SecretProtector.MASK, "${" + input + "}");
        }
        return value;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
