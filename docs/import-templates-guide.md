# 导入模板、数据集与便携交换

模板从实际运行的应用下载。`GET /api/templates` 返回模板家族、版本、文件名、导入格式和使用说明；当前目录版本为 1.1.0。各资产支持的导入和导出格式以 `GET /api/exchange/capabilities` 为准，界面据此展示可用选项。下表列出模板下载格式，不代表所有格式都能反向导入。

| 家族 | 内容 | 模板格式 |
| --- | --- | --- |
| PRD | 需求目标、角色、主流程、异常与验收准则 | Markdown、DOCX |
| PROJECT | 项目说明及附属模块、用例、环境 | JSON、XLSX |
| FUNCTIONAL | 模块、功能用例及独立步骤 | XLSX、XMind、JSON |
| API | OpenAPI 定义及接口测试用例 | OpenAPI JSON/YAML、XLSX |
| SCENARIO | HTTP、SQL、数据源及场景引用 | JSON、YAML |
| UI | 浏览器场景及独立步骤 | JSON、YAML |
| DATASET | 数据驱动测试参数 | CSV、XLSX |
| PLAN | 计划、用例、环境和计划项 | JSON、XLSX |
| BUG | 缺陷台账、重现过程与用例关联 | XLSX、JSON |
| WORKBENCH | 可配置看板、质量简报 | 布局 JSON、简报 Markdown/JSON |

工作台的下载格式键分别为 `layout-json`、`brief-md`、`brief-json`；导入时使用返回的 `importFormat`，不要将下载键当成文件解析格式。模板中的 `example.invalid`、示例数据源和示例统计需要替换为本项目实际内容；导入本身不请求示例站点、不运行 SQL、不执行源码。

## 下载、预览与应用

在模块页面打开「模板」下载样例，在「导入」中选择类型和文件。预览显示将新增的独立节点、引用、错误位置与警告；补齐外部资源映射或数据集列映射后重新预览，再应用 READY 结果。

接口流程与返回结构见 [api-contract.md](api-contract.md)：

1. `GET /api/templates/{family}?format=...` 下载所选模板。
2. `POST /api/projects/{projectId}/imports/preview` 上传 multipart 字段 `file`、`type`、`format`，可选 `parentId`、`referenceMappings`、`columnMappings`、`columnTypes`；映射字段使用 JSON 对象字符串。
3. `GET /api/projects/{projectId}/imports/{importId}` 恢复持久化预览。
4. `POST /api/projects/{projectId}/imports/{importId}/apply`，请求体 `{}`，将整批资产原子追加；响应包含 `keyToId`、`assetIds`、`createdCount`。

应用只支持 APPEND，为新资产分配新 ID，保留包内父子关系、顺序和引用，不覆盖现有人工资产。同一预览重复应用返回原结果。修改文件或映射后需要新预览；错误不会被静默丢弃后部分写入。相同名称不能作为覆盖已有记录的依据。

PRD 的路径、上传和粘贴入口也支持 Word、PDF、Excel、Markdown 文档解析；这些输入是需求正文接入，与可往返的结构化资产包是不同契约。两份文档流水线及五类来源见 [architecture-design.md](architecture-design.md)。

## JSON/YAML 资产包

推荐下载模板或导出已有资产后编辑，保留 `aitest.exchange/v1` 结构。每个用例、SQL 和 UI 步骤都是独立节点；包内使用本地 `key` 和 `parentKey`，引用写入 `references`，导入后统一映射为新 ID。

下面是可独立预览的功能用例 JSON：

```json
{
  "formatVersion": "aitest.exchange/v1",
  "metadata": {},
  "nodes": [
    {
      "key": "login_case",
      "type": "FUNCTIONAL_CASE",
      "name": "账号为空时提示必填",
      "position": 0,
      "data": { "priority": "P0", "precondition": "已打开登录页面" },
      "references": {}
    },
    {
      "key": "login_step",
      "type": "FUNCTIONAL_STEP",
      "parentKey": "login_case",
      "name": "提交空账号",
      "position": 0,
      "data": { "step": "账号留空并提交", "expected": "显示账号必填提示" },
      "references": {}
    }
  ],
  "externalReferences": {},
  "warnings": []
}
```

场景模板使用独立 `API_CASE`、`SQL_VALIDATION`、`SCENARIO_STEP` 和 `DATABASE_SOURCE`，步骤通过 `targetId` 引用目标。SQL 使用命名参数，例如 `SELECT status FROM orders WHERE order_no = :orderNo`，由执行上下文预编译绑定。不要把 `${orderNo}` 拼接进 SQL 字面量。HTTP 头等文本字段可使用 `Bearer ${token}`，完整变量表达式也可保留数值或对象类型。

UI 模板由 `UI_SCENARIO` 和 `UI_STEP` 构成，动作使用实际 DSL 名称，例如 `navigate`、`fill`、`click`、`waitFor`、`assertVisible`、`assertText`、`screenshot`。导入后先核对站点、定位器及来源证据，再执行。详情、录制与独立 Java 导出见 [api-contract.md](api-contract.md)。

## XLSX、CSV 与 XMind

版本化 XLSX 用 Apache POI 生成，包含「说明」、`__bundle` 元信息，以及按资产类型分开的工作表。结构列为 `_key`、`_type`、`_parentKey`、`_position`、`name`、`_references`；其余列对应该资产的领域字段。`headers`、`body`、断言和其他结构字段使用合法 JSON，正文和换行按文本保存，公式不执行。

兼容的普通功能用例工作簿支持所属模块、用例名称、优先级、前置条件、步骤描述、预期结果、备注；模块使用 `/` 分层，步骤与预期逐行对应。需要精确保留独立节点、引用、空值和复杂字段时使用应用下载的版本化工作簿。

XMind 模板使用 `模块:`、`用例:`、`步骤 1:`、`预期:` 的主题语法。导入会恢复独立模块、用例与步骤，任意自由文本脑图不被假定为完整可执行用例。

## DDT 数据集

普通 CSV 第一行是列名；普通单元格默认保留字符串，`001` 不会被自动改成数字。空单元格保留空字符串，`\N` 表示 null，`\\N` 表示字面文本 `\N`。逗号、引号和换行按 CSV 引号规则编码。XLSX 保留文本、数值和布尔类型，公式拒绝解析。

普通 CSV 示例：

```csv
caseName,quantity,expectedStatus,note
正向下单,2,CREATED,
数量为零,0,REJECTED,\N
```

预览可以将原列映射为测试变量，并显式选择字符串、数值、布尔或 JSON 转换。映射键必须属于文件原列或对应合法目标列；空名、重复名、不支持的类型与转换错误会显示在预览。重开预览保留已选映射，修改后重新校验。

平台导出的版本化 CSV 使用逐单元格 JSON 编码，XLSX 使用 `__dataset` 和 `__types` 保存类型；应保留这些元信息。带类型文件只支持改列名，不允许导入时再次转换类型；JSON/YAML/ZIP 数据集按既有结构导入，不接受列变换。前端以预览的 `mappingCapabilities` 控制可用操作。

将 DATASET 绑定到场景或运行时，每行产生独立执行上下文，提取的 token 和返回值不串到其他行。执行先检查变量、顺序与依赖；结果按实际展开行数统计。编辑数据集不会修改已提交运行的快照。容量实测见 源码仓库的容量验收记录。

## 导出、反馈与完整迁移

`POST /api/projects/{projectId}/exports` 使用 `{type,format,assetIds?,options?}` 下载资产。JSON/YAML/ZIP 和版本化表格用于资产交换；HTML/PDF 用于阅读报告，不作为原结构的导入格式。格式能力和子项依赖由服务器实际注册的编码器决定。

导入后的资产仍支持手工编辑、排序、删除和两级反馈。单条用例、SQL 或 UI 步骤的局部调优只改变指定目标，保留稳定 ID、位置和其他资产；全局反馈先生成候选，再由用户选择并原子采纳。资产文件不导入 AI 会话或执行记录来冒充事实。

项目包中的说明和路径保存在 `metadata.project`，向当前项目追加子资产，保留当前项目名称与配置。导入不会读取归档路径或执行仓库代码。

固定源码快照、影响报告及服务器生成依据属于原项目。导出将来源信息脱敏归档到 `metadata.detachedSourceEvidence`，移除签章和待导入资产的旧绑定；SQL、UI、RCA 文本及补丁仍保留。导入后重新分析并绑定本项目快照，预览会列出相关警告。

通知包去掉地址和密钥并关闭启用开关；计划保留排期设置但关闭自动排期，由用户核对后启用。原项目不因导出而改变。固定源码正文、附件、修订、RCA 人工评价、模型配置、通知投递及历史运行需要 [完整实例备份与恢复](operations.md)，不能仅依靠可编辑资产导出文件迁移。

历史设计原文保存在 源码仓库的设计归档；其中旧 DSL 和 EasyExcel 样例不作为当前接口契约。