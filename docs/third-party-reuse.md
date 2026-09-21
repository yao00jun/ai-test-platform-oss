# 第三方源码复用与借鉴登记

## MeterSphere

来源目录：MeterSphere 上游仓库的本地检出副本。该目录无 Git 元数据，按本地路径与 SHA-256 标识。核验日期：2026-09-14。

上游许可全文保存在 [MeterSphere-LICENSE](../licenses/MeterSphere-LICENSE)，包含 GPLv3 和上游附加版权/标识条款。移植文件保留来源、原作者和修改说明；发行需携带许可与相应源码。

上游文件通过链接引用的 GNU GPL 第 3 版完整文字另存 [GPL-3.0.txt](../licenses/GPL-3.0.txt)。原始来源为本机 Git 发行包 `mingw64/share/licenses/gcc-libs/COPYING3`，按字节复制，SHA-256 为 `8CEB4B9EE5ADEDDE47B31E975C1D90C73AD27B6B165A1DCD80C7C545EB65B903`。发行 `source.zip` 同时携带本次构建对应的源码、脚本和依赖锁定文件。

| 上游文件 | 本项目落点 | 改动 |
| --- | --- | --- |
| backend/services/case-management/src/main/java/io/metersphere/functional/utils/MdUtil.java | backend/src/main/java/com/aitest/ai/text/MdUtil.java | 保留 Flexmark AST 和表格处理；适配 DTO；步骤标题优先；成对定界符；截断拒绝；HTML 转义 |
| backend/framework/ai-engine/src/main/java/io/metersphere/ai/engine/utils/TextCleaner.java | backend/src/main/java/com/aitest/ai/text/TextCleaner.java | 保留原清洗方法；修正标题正则与边界换行；新增保留业务符号的 normalizeDocument |
| 同目录 KeywordDeduplication.java | backend/src/main/java/com/aitest/ai/text/KeywordDeduplication.java | 包名/许可适配；保留 Ansj 算法，原文导入不走语义去重 |
| 同目录 TextRankSummarizer.java | backend/src/main/java/com/aitest/ai/text/TextRankSummarizer.java | 包名/许可适配；保留 TextRank 辅助摘要算法 |
| backend/services/case-management/src/main/resources/prompts/generate_step.st | backend/src/main/resources/prompts/metersphere_generate_step.st | 保留参考模板与 featureCaseStart/featureCaseEnd 协议 |
| backend/services/api-test/src/main/resources/apiCaseAiTemplate.vm | backend/src/main/resources/prompts/metersphere_api_case.vm | 保留请求参数组织模板；不移植返回 null 键值的原缓存实现 |
| frontend/src/components/business/ms-ai-drawer | frontend/src/components/ai | 移植会话/结果交互，替换组织、权限和请求层，使用持久化任务 SSE |
| frontend/src/components/business/ms-menu | frontend/src/app | 复用 Arco 菜单折叠与选中交互，适配八大路由 |

已核验关键 SHA-256：

- MdUtil.java：`195E5430DE36F02C8BB4ED0989E11F1CD08E5663BA3E2010F99BBA4369F776B1`
- TextCleaner.java：`9BA3A363559E18B36F4957273023B97C6BE7630BF997DA7F02B4C5CF26599486`
- ms-ai-drawer/components/conversation.vue：`F94FE1F2A3EDC93619404C24BB4548EB4BB28F871BA4E7C2E9031EFFD774CBCF`
- ms-menu/index.vue：`59D720D518AD42C09279BB990DB0B68509D70C5BE3086F123BF62EC78ADB828A`

项目输入的七份提示词已复制为运行时资源，并补齐 Markdown 表格分隔行。运行时模板版本以内容 SHA-256 记录。需求原文、SQL、JSON 与 `${token}` 保持原义，不使用 fullClean。

群通知还参考了上游 DingClient、WeComClient 和 LarkClient 的消息结构。发送实现使用本项目的有界 HttpTransport、持久化投递历史及未知结果处理，没有照搬上游无总期限的网络调用。

## TestPilot-AI

来源目录：TestPilot-AI 上游仓库的本地检出副本。核验日期：2026-09-15。MIT 许可全文按原始字节保存在 [TestPilot-AI-LICENSE](../licenses/TestPilot-AI-LICENSE)，版权为 `Copyright (c) 2026 Shikhar Srivastava`。许可 SHA-256：`815E485CD3C9F32156F2CF213E5E2B6414D2D4A68B4F6555E2F13E0022AE483F`。

以下为设计和算法借鉴，未引入 Python 服务或执行其项目脚本。JavaParser 调用解析、固定版本证据校验和持久化任务由本项目实现；Python import 依赖遍历不等同于 Java 方法调用图。所有 EvalOps 指标来自本项目实际模型响应、运行和人工评价，参考项目的演示数字未移植。

| 参考文件 | 本项目采用范围 | 上游 SHA-256 |
| --- | --- | --- |
| backend/app/services/ast_parser.py | `analysis/ast`：AST 结构与代码范围提取思路；使用 JavaParser 解析 Java | `883047222FA3AD022693774145605EA286212EBE276A1643AC3DCAF7492E3042` |
| backend/app/services/dependency_graph_builder.py | `analysis/impact/CallGraphBuilder`：有向依赖表示；另行处理 Java 类型、重载与歧义 | `099CDE3256F626F3D443D9CDF8AB7ABFA30E8E55E09B3C6ABF3FD0A02CF465B5` |
| backend/app/services/impact_analyzer.py | `BlastRadiusService/ImpactCandidates`：反向遍历与测试影响关联 | `7DA91252DDB0647CE5403C520236C682AD3AB5345C10164A115966AAEB357197` |
| backend/app/services/evalops_collector.py | `workbench/EvalOpsService`：效能指标维度；分母、缺失 usage 和价格均按实际数据处理 | `561AFC77F59E0E47100CFB1DB874EC2CCDCE0D0FBF67FE7C2F1687FFDCD3DFBB` |
| backend/app/agents/diff_agent.py | `analysis/diff/GitDiffAnalyzer`：变更上下文；使用真实 unified diff 旧/新区间与 AST 相交 | `C87D25BDF7AA2489057691290D1B65D74A17C4E004CB775979A9C3C4CD289090` |
| backend/app/agents/failure_analysis_agent.py | `analysis/rca`：代码根因与补丁建议结构；证据由不可变运行快照提供 | `048FC284BA468F0F50B0E1A43B0A02D4BABD5E5B59476D4DCF730030F7432437` |
| backend/app/agents/impact_agent.py | 定向回归交互与建议范围；最终测试选择、CAS 和计划创建在本项目完成 | `A530F5391C1ABC069B0F8E18FF41D6C654C9979750E1E966CB6B67E16D922B9F` |

参考代码的许可与本项目已移植 MeterSphere 代码的许可分别随发行包提供；MIT 文件不替代 MeterSphere 的适用许可要求。
