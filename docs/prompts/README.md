# 提示词、输出协议与多轮反馈

实际加载目录是 `backend/src/main/resources/prompts/`。本目录的原始七套 `.st` 文档已同步为运行时对应版本；实施前原文保存在 源码仓库的设计归档。模型调用记录模板内容的 SHA-256，可追溯一次输出所使用的规则。模板变化需重新构建后端。

| 模板 | 用途 |
| --- | --- |
| `ba_requirement_parser.st` | 需求规则、歧义与遗漏提取 |
| `functional_case_generation.st` | 功能用例与步骤，以 MeterSphere Markdown 定界协议输出 |
| `api_case_generation.st` | 接口用例、请求参数与场景上下文 |
| `sql_validation_generation.st` | 批量生成后置 SQL 校验建议 |
| `bug_auto_creation.st` | 基于实际运行证据的缺陷诊断 |
| `test_summary_report.st` | 基于冻结统计与证据的质量总结 |
| `ui_case_generation.st` | 页面证据约束下的 UI 场景与步骤 |
| `asset_generation.st` | 各领域资产统一候选契约 |
| `local_refinement.st` | 单目标、允许字段、当前版本及用户反馈 |
| `pipeline_feedback.st` | 全局多轮反馈的选择性变更集 |
| `pipeline_stage_contract.st` | 阶段化生成的结构、范围和来源契约 |
| `metersphere_generate_step.st`、`metersphere_api_case.vm` | 保留来源与许可的上游参考协议 |

## 解析与应用约束

功能用例保留成对 `featureCaseStart`/`featureCaseEnd` 定界符与表格结构，通过移植的 MdUtil 解析；截断或无效结构不能被默默采纳。JSON 资产候选、局部字段和变更集使用各自的结构校验，并由领域校验器再次检查引用、SQL、UI、项目及来源范围。提示词本身不能替代这些后端检查。

需求、代码、接口文档和执行日志是待分析资料，不是可以提升权限或改变输出范围的指令。原始 SQL、JSON、Markdown 中的业务符号和 `${token}` 保持原义；导入不使用会破坏它们的语义清洗。

局部反馈每轮携带当前资产、允许字段、稳定 ID、版本及必要上下文。会话持久化，旧响应不能覆盖新人工版本；单个 SQL/UI 步骤之外的资产不可作为隐式修补目标。全局反馈返回候选变更集，经用户选择、依赖校验与 CAS 原子应用后才修改正式资产。

使用固定源码时，生成、反馈与 RCA 同时携带快照身份和可校验的来源位置；未知表列、越界路径和不受支持的 SQL 语法会被拒绝。模型缺少有效结束信号、响应截断或被取消时保留失败记录，原资产保持不变。

## 模型适配与验证

模型通过 Spring AI 接入标准 OpenAI 兼容在线服务，配置 `baseUrl`、`apiKey`、`modelName`。没有路径的服务地址会补 `/v1`；已有路径原样保留。明确不支持流式接口的服务可回退普通响应，重试有截止时间及可重放范围限制。usage 缺失不被填成零。

`PromptRenderContractTest`、`MdUtilCompatibilityTest`、模型协议、全局/局部反馈、固定源码及 RCA 集成测试验证输出契约和应用不变量。真实公司服务的输出质量、业务覆盖率与价格需要独立环境验收，不能从 fixture 推断。
