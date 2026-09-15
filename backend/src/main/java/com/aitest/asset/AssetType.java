package com.aitest.asset;

import java.util.List;
import java.util.Map;

import static com.aitest.asset.FieldDefinition.*;

/** Whitelisted domain schemas also drive forms/imports. Only structured fields are stored as JSON. */
public enum AssetType {
    PROJECT("project", "项目", List.of(area("description", "项目说明"), text("backendRepoPath", "后端源码路径或 Git URL"), text("frontendRepoPath", "前端源码路径或 Git URL"), text("sqlScriptPath", "数据库 DDL 路径"))),
    ENVIRONMENT("project_environment", "环境", List.of(required("baseUrl", "API 基础地址"), text("webUrl", "Web 基础地址"), json("headers", "公共请求头", Map.of()), json("variables", "环境变量", Map.of()), select("purpose", "用途", "TEST", "TEST", "STAGING", "PRODUCTION"), bool("allowSqlWrite", "允许受保护的 SQL 写入", false), json("httpOptions", "HTTP 配置", Map.of()), bool("autoRunGenerated", "自动运行生成的测试", false))),
    AUTH_CONFIG("project_global_auth", "全局鉴权", List.of(required("environmentId", "环境 ID"), bool("enabled", "启用", true), required("loginUrl", "登录接口"), select("loginMethod", "请求方法", "POST", "POST", "GET"), json("loginPayload", "登录参数", Map.of()), text("tokenJsonPath", "Token JSONPath"), text("headerKey", "Header 名"), text("headerPrefix", "Token 前缀"), number("ttlSeconds", "有效期（秒）", 1800), bool("retryUnauthorized", "401 后刷新并重试幂等请求", true), bool("retryNonIdempotent", "也重试非幂等认证失败请求", false))),
    DATABASE_SOURCE("project_database_source", "业务数据源", List.of(text("environmentId", "环境 ID"), select("dbType", "数据库", "MYSQL", "MYSQL", "POSTGRESQL"), required("jdbcUrl", "JDBC URL"), required("username", "用户名"), secret("password", "密码"), bool("safeMode", "只读模式", true), number("maxPoolSize", "最大连接数", 4))),
    MODULE("case_module", "模块", List.of(area("description", "说明"))),
    REQUIREMENT("requirement_document", "需求", List.of(area("content", "需求正文"), text("fileId", "原件 ID"), text("sourcePath", "来源路径"), json("sections", "章节", List.of()), json("analysis", "需求分析", Map.of()))),
    FUNCTIONAL_CASE("functional_case", "功能用例", List.of(select("priority", "优先级", "P1", "P0", "P1", "P2", "P3"), select("caseType", "用例类型", "FUNCTIONAL", "FUNCTIONAL", "BOUNDARY", "NEGATIVE", "SECURITY", "PERFORMANCE"), area("precondition", "前置条件"), area("remark", "备注"), text("requirementId", "关联需求 ID"), json("tags", "标签", List.of()))),
    FUNCTIONAL_STEP("functional_case_step", "功能步骤", List.of(required("step", "测试步骤"), area("expected", "预期结果"))),
    API_DEFINITION("api_definition", "接口定义", List.of(select("method", "方法", "GET", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), required("path", "接口路径"), json("headers", "请求头", Map.of()), json("queryParams", "Query 参数", Map.of()), select("bodyType", "请求体类型", "NONE", "NONE", "JSON", "FORM", "MULTIPART", "RAW"), json("body", "请求体", Map.of()), json("schema", "OpenAPI 定义", Map.of()), text("documentVersion", "文档版本"))),
    API_CASE("api_test_case", "接口用例", List.of(text("apiDefinitionId", "接口定义 ID"), select("method", "方法", "GET", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"), required("path", "接口路径"), json("headers", "请求头", Map.of()), json("queryParams", "Query 参数", Map.of()), select("bodyType", "请求体类型", "NONE", "NONE", "JSON", "FORM", "MULTIPART", "RAW"), json("body", "请求体", Map.of()), json("extractors", "变量提取器", List.of()), json("assertions", "断言", List.of()), number("timeoutMs", "超时（毫秒）", 30000), bool("useGlobalAuth", "使用全局鉴权", true))),
    SCENARIO("api_scenario", "场景", List.of(area("description", "说明"), json("variables", "初始变量", Map.of()), bool("continueOnFailure", "失败后继续", false), text("datasetId", "数据集 ID"))),
    SCENARIO_STEP("scenario_step", "场景步骤", List.of(select("stepType", "执行类型", "HTTP", "HTTP", "SQL", "WEB", "WAIT"), text("targetId", "引用资产 ID"), json("variables", "步骤变量", Map.of()), number("waitMs", "等待（毫秒）", 0))),
    SQL_VALIDATION("sql_validation", "SQL 校验", List.of(text("databaseSourceId", "数据源 ID（执行前绑定）"), new FieldDefinition("sql", "SQL", "sql", true, List.of(), null), json("parameters", "参数", Map.of()), json("assertions", "SQL 断言", List.of()), json("exports", "结果变量映射", Map.of()), bool("allowWrite", "允许受保护写入", false), bool("dryRun", "执行后回滚", true), number("timeoutSeconds", "超时（秒）", 30), number("maxRows", "返回行数上限", 1000), number("maxAffectedRows", "写入行数上限", 1000))),
    DATASET("data_driven_dataset", "数据集", List.of(json("columns", "列名", List.of()), json("rows", "数据行", List.of()), text("fileId", "原件 ID"))),
    UI_SCENARIO("web_test_scenario", "UI 场景", List.of(area("description", "说明"), text("baseUrl", "站点地址"), select("browser", "浏览器", "CHROMIUM", "CHROMIUM", "FIREFOX", "WEBKIT"), number("viewportWidth", "宽度", 1440), number("viewportHeight", "高度", 900), bool("headless", "无头模式", true), number("timeoutMs", "运行超时（毫秒）", 120000), bool("continueOnFailure", "失败后继续", false), bool("ignoreHttpsErrors", "忽略目标站点 TLS 错误", false))),
    UI_STEP("web_test_step", "UI 步骤", List.of(select("action", "动作", "navigate", "navigate", "click", "dblclick", "fill", "press", "select", "selectOption", "check", "uncheck", "hover", "dragAndDrop", "upload", "wait", "waitFor", "assertText", "assertVisible", "assertHidden", "assertUrl", "assertValue", "assertCount", "screenshot", "extract", "popup", "download", "closePage"), text("selector", "定位器"), bool("exactMatch", "精确匹配定位文本", true), text("value", "输入值"), text("url", "URL"), text("expected", "预期值"), number("timeoutMs", "超时（毫秒）", 15000), text("frame", "Frame 定位器"), text("pageAlias", "页面别名"), text("saveAs", "保存变量或页面别名"), text("attribute", "属性"), text("targetSelector", "拖放目标定位器"), json("fileIds", "上传附件 ID", List.of()), select("waitState", "等待状态", "visible", "visible", "hidden", "attached", "detached"))),
    TEST_PLAN("test_plan", "测试计划", List.of(area("description", "说明"), text("environmentId", "环境 ID"), text("datasetId", "数据集 ID"), text("sourceSnapshotId", "固定源码快照 ID"), text("impactId", "影响报告 ID"), text("cronExpression", "Cron 表达式"), text("timezone", "时区"), bool("scheduleEnabled", "启用定时", false), select("overlapPolicy", "重叠策略", "SKIP", "SKIP", "QUEUE"), select("misfirePolicy", "错过触发策略", "SKIP", "SKIP", "FIRE_ONCE"), number("concurrency", "并发数", 4), bool("diagnoseFailures", "AI 诊断失败", true))),
    PLAN_ITEM("test_plan_item", "计划项", List.of(required("targetId", "测试资产 ID"), select("executionMode", "执行方式", "AUTO", "AUTO", "MANUAL"), text("datasetId", "数据集 ID"), json("variables", "变量", Map.of()))),
    BUG("bug_issue", "缺陷", List.of(select("severity", "严重度", "MAJOR", "BLOCKER", "CRITICAL", "MAJOR", "MINOR"), select("status", "状态", "OPEN", "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "REOPENED"), area("reproduceSteps", "重现步骤"), area("actualResult", "实际结果"), area("expectedResult", "预期结果"), area("rootCauseAnalysis", "根因推测与依据"), area("suggestion", "修复建议"), json("codeDiagnosis", "代码诊断", Map.of()), text("associatedCaseId", "关联用例 ID"), text("runId", "运行 ID"), json("attachments", "附件 ID", List.of()), text("fingerprint", "失败指纹"))),
    DASHBOARD("dashboard_layout", "看板布局", List.of(json("cards", "卡片配置", List.of()), area("notes", "备注"))),
    QUALITY_BRIEF("quality_brief", "质量简报", List.of(area("content", "简报正文"), text("runId", "运行 ID"), json("metrics", "统计快照", Map.of()))),
    WEBHOOK("project_webhook_notice", "群通知", List.of(select("platform", "平台", "DINGTALK", "DINGTALK", "WECHAT_WORK", "FEISHU"), secret("webhookUrl", "Webhook URL"), secret("secret", "签名密钥"), bool("enabled", "启用", false), bool("failOnly", "仅失败通知", true), number("maxRetries", "自动重试上限", 3), number("timeoutSeconds", "发送超时（秒）", 10)));

    private final String table;
    private final String label;
    private final List<FieldDefinition> fields;
    AssetType(String table, String label, List<FieldDefinition> fields) { this.table = table; this.label = label; this.fields = fields; }
    public String table() { return table; }
    public String label() { return label; }
    public List<FieldDefinition> fields() {
        if (!supportsSourceEvidence()) return fields;
        var result = new java.util.ArrayList<>(fields);
        if (this != TEST_PLAN) result.add(text("sourceSnapshotId", "固定源码快照 ID"));
        result.add(json("generationEvidence", "生成依据", Map.of()));
        return List.copyOf(result);
    }
    public boolean supportsSourceEvidence() {
        return switch (this) {
            case REQUIREMENT, FUNCTIONAL_CASE, FUNCTIONAL_STEP, API_DEFINITION, API_CASE, SCENARIO, SCENARIO_STEP, SQL_VALIDATION, UI_SCENARIO, UI_STEP, TEST_PLAN, PLAN_ITEM, BUG -> true;
            default -> false;
        };
    }
    public List<AssetType> childTypes() {
        return switch (this) {
            case FUNCTIONAL_CASE -> List.of(FUNCTIONAL_STEP);
            case API_CASE -> List.of(SQL_VALIDATION);
            case SCENARIO -> List.of(SCENARIO_STEP, SQL_VALIDATION);
            case SCENARIO_STEP -> List.of(SQL_VALIDATION);
            case UI_SCENARIO -> List.of(UI_STEP);
            case TEST_PLAN -> List.of(PLAN_ITEM);
            case MODULE -> List.of(MODULE, FUNCTIONAL_CASE, API_DEFINITION, API_CASE, SCENARIO, UI_SCENARIO);
            default -> List.of();
        };
    }
    public boolean requiresParent() { return List.of(FUNCTIONAL_STEP, SCENARIO_STEP, UI_STEP, PLAN_ITEM).contains(this); }
    public List<String> formats() {
        return switch (this) {
            case FUNCTIONAL_CASE -> List.of("json", "xlsx", "md", "xmind", "pdf");
            case API_DEFINITION, API_CASE -> List.of("json", "yaml", "xlsx", "curl", "har", "postman");
            case SCENARIO, SQL_VALIDATION -> List.of("json", "yaml", "zip");
            case UI_SCENARIO, UI_STEP -> List.of("json", "yaml", "java", "zip");
            case DATASET -> List.of("json", "csv", "xlsx");
            case BUG -> List.of("json", "xlsx", "csv", "md");
            case REQUIREMENT -> List.of("md", "docx", "pdf", "xlsx", "json");
            case QUALITY_BRIEF, DASHBOARD -> List.of("json", "md", "html", "pdf");
            default -> List.of("json", "xlsx");
        };
    }
}
