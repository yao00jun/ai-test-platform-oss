package com.aitest.exchange;

import com.aitest.asset.AssetType;
import com.aitest.common.Problem;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateCatalog {
    public static final String VERSION = "1.1.0";
    public record Variant(String format, String importFormat, AssetType type, String filename, String instructions) { }
    public record Family(String family, String label, String version, List<Variant> variants) { }
    private final PortableBundleCodec portable;
    private final LedgerCodec ledger;
    private final DatasetCodec datasets;
    private final XMindCodec xmind;
    private final RequirementExportCodec requirements;
    private final ApiExportCodec api;
    public TemplateCatalog(PortableBundleCodec portable, LedgerCodec ledger, DatasetCodec datasets, XMindCodec xmind, RequirementExportCodec requirements, ApiExportCodec api) {
        this.portable = portable; this.ledger = ledger; this.datasets = datasets; this.xmind = xmind; this.requirements = requirements; this.api = api;
    }
    public List<Family> list() {
        return List.of(
                family("PRD", "需求规格说明", variants(AssetType.REQUIREMENT, "prd", "填写业务目标、角色、主流程、边界与验收准则；保留可检索正文。", "md", "docx")),
                family("PROJECT", "项目与配置", variants(AssetType.PROJECT, "project", "metadata.project 保存项目说明；导入向当前项目追加资产，不覆盖当前项目名称或配置。", "json", "xlsx")),
                family("FUNCTIONAL", "功能测试用例", variants(AssetType.FUNCTIONAL_CASE, "functional", "模块与步骤均为独立记录。XMind 使用模块:、用例:、步骤 1:、预期: 主题语法；XLSX 每类一个工作表。", "xlsx", "xmind", "json")),
                family("API", "接口定义与测试", List.of(new Variant("json", "json", AssetType.API_DEFINITION, "api.openapi.json", "OpenAPI 3 示例，支持 Swagger 2；仅解析内联引用，不访问 servers 或远程 $ref。"), new Variant("yaml", "yaml", AssetType.API_DEFINITION, "api.openapi.yaml", "OpenAPI YAML 中数字状态码键需加引号；替换请求示例并预览。"), new Variant("xlsx", "xlsx", AssetType.API_CASE, "api.xlsx", "接口定义与测试用例通过本地 key 关联；headers/query/body/assertions 使用合法 JSON。"))),
                family("SCENARIO", "接口与 SQL 场景", variants(AssetType.SCENARIO, "scenario", "包含本地 API、SQL 与数据源依赖。example.invalid 与 ${DB_USERNAME} 都是待配置占位；导入不会连接目标或执行 SQL。", "json", "yaml")),
                family("UI", "浏览器场景", variants(AssetType.UI_SCENARIO, "ui", "每个浏览器步骤独立一行/节点；替换 example.invalid 为测试站点并核对定位器后运行。", "json", "yaml")),
                family("DATASET", "数据驱动数据集", variants(AssetType.DATASET, "dataset", "CSV 默认将普通单元格作为字符串，\\N 表示 null；版本化模板使用逐单元格 JSON 编码。XLSX 保留文本与数值类型，不计算公式。", "csv", "xlsx")),
                family("PLAN", "测试计划", variants(AssetType.TEST_PLAN, "plan", "计划附本地功能用例、环境和计划项；首次导入不启用定时任务。", "json", "xlsx")),
                family("BUG", "缺陷台账", variants(AssetType.BUG, "bug", "填写重现步骤、实际/预期结果与严重度；关联用例可用本地引用或预览时映射已有资产。", "xlsx", "json")),
                family("WORKBENCH", "工作台布局与质量简报", List.of(new Variant("layout-json", "json", AssetType.DASHBOARD, "workbench.layout.json", "cards 是布局卡片数组，notes 保存说明；本地布局不需要已有资产 ID。"), new Variant("brief-md", "md", AssetType.QUALITY_BRIEF, "quality-brief.md", "填写范围、执行结果、风险和发布建议；示例统计仅供结构演示。"), new Variant("brief-json", "json", AssetType.QUALITY_BRIEF, "quality-brief.json", "质量简报的正文与 metrics 使用规范资产字段。")))
        );
    }
    private static Family family(String family, String label, List<Variant> variants) { return new Family(family, label, VERSION, variants); }
    private static List<Variant> variants(AssetType type, String filename, String instructions, String... formats) { return java.util.Arrays.stream(formats).map(f -> new Variant(f, f, type, filename + "." + f, instructions)).toList(); }
    public ExportFile download(String familyName, String requestedFormat) {
        Family family = list().stream().filter(f -> f.family().equalsIgnoreCase(familyName)).findFirst().orElseThrow(Problem::missing);
        Variant variant = requestedFormat == null || requestedFormat.isBlank() ? family.variants().getFirst() : family.variants().stream().filter(v -> v.format().equalsIgnoreCase(requestedFormat)).findFirst().orElseThrow(() -> Problem.invalid("此模板没有所选格式"));
        ExportFile file;
        if (family.family().equals("PRD")) file = requirements.encode(prd(), "prd", variant.format());
        else if (family.family().equals("WORKBENCH") && variant.format().equals("brief-md")) file = new ExportFile(variant.filename(), "text/markdown; charset=utf-8", brief().getBytes(StandardCharsets.UTF_8));
        else {
            ExchangeBundle bundle = example(family.family(), variant);
            if (family.family().equals("API") && !variant.format().equals("xlsx")) {
                var data = new LinkedHashMap<>(bundle.nodes().stream().filter(n -> n.type() == AssetType.API_DEFINITION).findFirst().orElseThrow().data()); data.put("name", "查询健康状态");
                file = api.encode(List.of(data), "openapi-" + variant.format(), "api");
            } else file = switch (variant.importFormat()) {
                case "json", "yaml" -> portable.encode(bundle, family.family().toLowerCase(java.util.Locale.ROOT), variant.importFormat());
                case "xlsx", "csv" -> variant.type() == AssetType.DATASET ? datasets.encode(bundle, "dataset", variant.importFormat()) : ledger.encode(bundle, family.family().toLowerCase(java.util.Locale.ROOT), variant.importFormat());
                case "xmind" -> xmind.encode(bundle, "functional");
                default -> throw new IllegalStateException("Template codec is not registered");
            };
        }
        return new ExportFile(variant.filename(), file.mediaType(), file.bytes());
    }
    public ExchangeBundle example(String family, Variant variant) {
        List<ExchangeNode> nodes = new ArrayList<>();
        switch (family) {
            case "FUNCTIONAL" -> functional(nodes);
            case "PROJECT" -> {
                functional(nodes); nodes.add(node("environment", AssetType.ENVIRONMENT, null, "测试环境（请配置）", 0, Map.of("baseUrl", "https://example.invalid", "purpose", "TEST", "allowSqlWrite", false), Map.of()));
            }
            case "API" -> api(nodes);
            case "SCENARIO" -> {
                api(nodes);
                nodes.add(node("db", AssetType.DATABASE_SOURCE, null, "示例只读数据源（需配置）", 0, Map.of("jdbcUrl", "jdbc:mysql://example.invalid:3306/example", "username", "${DB_USERNAME}", "password", "", "safeMode", true), Map.of()));
                nodes.add(node("sql", AssetType.SQL_VALIDATION, null, "只读连通性校验", 0, Map.of("sql", "SELECT 1 AS ready", "exports", Map.of("ready", "ready"), "dryRun", true), Map.of("databaseSourceId", "db")));
                nodes.add(node("scenario", AssetType.SCENARIO, null, "服务健康与只读数据库校验", 0, Map.of("description", "导入仅建立资产；配置站点与数据源后再运行。", "continueOnFailure", false), Map.of()));
                nodes.add(node("http_step", AssetType.SCENARIO_STEP, "scenario", "检查 HTTP 服务", 0, Map.of("stepType", "HTTP"), Map.of("targetId", "api_case")));
                nodes.add(node("sql_step", AssetType.SCENARIO_STEP, "scenario", "检查只读 SQL", 1, Map.of("stepType", "SQL"), Map.of("targetId", "sql")));
            }
            case "UI" -> {
                nodes.add(node("ui", AssetType.UI_SCENARIO, null, "打开登录页", 0, Map.of("baseUrl", "https://example.invalid", "description", "将占位域名改为真实测试站点。"), Map.of()));
                nodes.add(node("navigate", AssetType.UI_STEP, "ui", "访问登录页", 0, Map.of("action", "navigate", "url", "https://example.invalid/login"), Map.of()));
                nodes.add(node("visible", AssetType.UI_STEP, "ui", "验证页面可见", 1, Map.of("action", "assertVisible", "selector", "body"), Map.of()));
            }
            case "DATASET" -> {
                var first = new LinkedHashMap<String, Object>(); first.put("编号", "001"); first.put("名称", "中文,订单\n第二行"); first.put("数量", 2); first.put("日期", "2026-09-14"); first.put("备注", null);
                nodes.add(node("dataset", AssetType.DATASET, null, "订单参数示例", 0, Map.of("columns", List.of("编号", "名称", "数量", "日期", "备注"), "rows", List.of(first, Map.of("编号", "002", "名称", "引号\"示例", "数量", 0, "日期", "2026-09-15", "备注", ""))), Map.of()));
            }
            case "PLAN" -> {
                functional(nodes); nodes.add(node("environment", AssetType.ENVIRONMENT, null, "计划测试环境（请配置）", 0, Map.of("baseUrl", "https://example.invalid"), Map.of()));
                nodes.add(node("plan", AssetType.TEST_PLAN, null, "登录冒烟计划", 0, Map.of("description", "导入后人工复核用例；定时默认关闭。", "scheduleEnabled", false), Map.of("environmentId", "environment")));
                nodes.add(node("plan_item", AssetType.PLAN_ITEM, "plan", "人工验证登录", 0, Map.of("executionMode", "MANUAL"), Map.of("targetId", "case")));
            }
            case "BUG" -> {
                functional(nodes); nodes.add(node("bug", AssetType.BUG, null, "输入空账号后没有明确提示", 0, Map.of("severity", "MAJOR", "status", "OPEN", "reproduceSteps", "1. 打开登录页\n2. 账号留空并提交", "actualResult", "页面未提示必填项", "expectedResult", "账号输入框显示必填提示", "suggestion", "在客户端和服务端补充必填校验"), Map.of("associatedCaseId", "case")));
            }
            case "WORKBENCH" -> {
                if (variant.type() == AssetType.DASHBOARD) nodes.add(node("layout", AssetType.DASHBOARD, null, "项目质量工作台", 0, Map.of("cards", com.aitest.workbench.DashboardCardSchema.normalize(List.of(
                        Map.of("id", "quality", "type", "quality", "title", "最近 24 小时质量", "fields", List.of("runCount", "itemCount", "passRatePercent", "failureCount")),
                        Map.of("id", "assets", "type", "assets", "title", "当前测试资产", "fields", List.of("FUNCTIONAL_CASE", "API_CASE", "UI_SCENARIO", "TEST_PLAN")),
                        Map.of("id", "bugs", "type", "bugs", "title", "最近更新的缺陷", "size", "full"),
                        Map.of("id", "evalops", "type", "evalops", "title", "模型效能", "fields", List.of("invocations", "totalTokens", "correctRatePercent")))), "notes", "模板 v" + VERSION + "：在卡片编辑器中调整标题、字段、尺寸、可见性和顺序。数值来自项目实时统计。"), Map.of()));
                else nodes.add(node("brief", AssetType.QUALITY_BRIEF, null, "迭代质量简报", 0, Map.of("content", brief(), "metrics", Map.of("planned", 10, "executed", 8, "passed", 7, "failed", 1)), Map.of()));
            }
            default -> throw Problem.invalid("未知模板家族");
        }
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("template", Map.of("family", family, "version", VERSION, "instructions", variant.instructions()));
        if (family.equals("PROJECT")) metadata.put("project", Map.of("name", "示例测试项目", "data", Map.of("description", "导入会在当前项目中新建模块、用例与示例环境；当前项目保持原名。")));
        return new ExchangeBundle(ExchangeBundle.VERSION, metadata, nodes, Map.of(), List.of());
    }
    private static void functional(List<ExchangeNode> nodes) {
        nodes.add(node("module", AssetType.MODULE, null, "用户中心", 0, Map.of("description", "用户基础能力"), Map.of()));
        nodes.add(node("login", AssetType.MODULE, "module", "登录模块", 0, Map.of(), Map.of()));
        nodes.add(node("case", AssetType.FUNCTIONAL_CASE, "login", "已注册用户可以登录", 0, Map.of("priority", "P0", "precondition", "用户已注册且账号状态正常", "remark", "冒烟正向主流程"), Map.of()));
        nodes.add(node("step_1", AssetType.FUNCTIONAL_STEP, "case", "步骤 1", 0, Map.of("step", "打开登录页面", "expected", "账号与密码输入框可见"), Map.of()));
        nodes.add(node("step_2", AssetType.FUNCTIONAL_STEP, "case", "步骤 2", 1, Map.of("step", "输入测试账号并提交", "expected", "显示登录成功并进入首页"), Map.of()));
    }
    private static void api(List<ExchangeNode> nodes) {
        var data = new LinkedHashMap<String, Object>(); data.put("method", "GET"); data.put("path", "/health"); data.put("headers", Map.of("Accept", "application/json")); data.put("queryParams", Map.of("detail", "true")); data.put("bodyType", "NONE");
        data.put("schema", Map.of("sourceFormat", "openapi", "sourceVersion", "3.0.3", "document", Map.of("openapi", "3.0.3", "info", Map.of("title", "API 模板 v" + VERSION, "version", VERSION, "description", "替换 example.invalid 后使用；导入过程不会请求服务器。"), "servers", List.of(Map.of("url", "https://example.invalid"))), "operation", Map.of("responses", Map.of("200", Map.of("description", "服务正常", "content", Map.of("application/json", Map.of("schema", Map.of("type", "object", "properties", Map.of("status", Map.of("type", "string")))))))))); data.put("documentVersion", VERSION);
        nodes.add(node("api_definition", AssetType.API_DEFINITION, null, "查询健康状态", 0, data, Map.of()));
        var test = new LinkedHashMap<>(data); test.remove("schema"); test.remove("documentVersion"); test.put("assertions", List.of(Map.of("type", "status_code", "expected", 200)));
        nodes.add(node("api_case", AssetType.API_CASE, null, "健康检查返回 200", 0, test, Map.of("apiDefinitionId", "api_definition")));
    }
    private static ExchangeNode node(String key, AssetType type, String parent, String name, int position, Map<String, Object> data, Map<String, String> refs) { return new ExchangeNode(key, type, parent, name, position, data, refs); }
    private static String prd() { return """
            # 产品需求规格说明书

            模板版本：1.0.0
            使用说明：替换示例文字；明确角色、输入、主流程、异常和可验证验收准则。

            ## 目标与范围
            已注册用户可以通过账号登录，并在输入错误时收到明确提示。

            ## 用户角色
            访客、已注册用户、管理员。

            ## 主流程
            1. 用户打开登录页面。
            2. 用户填写账号与密码并提交。
            3. 系统验证后进入首页。

            ## 边界与异常
            账号为空时禁止提交；凭证无效时显示统一提示；超时后允许重试。

            ## 验收准则
            - 有效测试账号登录成功。
            - 必填项缺失时展示准确提示。
            - 日志与导出文件不暴露凭证。
            """; }
    private static String brief() { return """
            # 迭代质量简报

            模板版本：1.0.0
            使用说明：以下是示例结构，请以当前迭代的实际测试结果替换统计与结论。

            ## 测试范围
            登录、账号校验与服务健康检查。

            ## 执行结果
            计划 10 项，已执行 8 项，通过 7 项，失败 1 项。

            ## 风险与行动
            仍有 2 项待执行；修复登录空值提示后完成回归。

            ## 发布建议
            完成关键路径验证并由责任人确认后再决定发布。
            """; }
}
