# 前后端接口契约

所有业务接口位于 `/api`，请求/响应为 JSON，登录表单、下载和 SSE 除外。时间为 ISO-8601 UTC；所有版本为十进制字符串，避免 JavaScript 整数精度损失。接口不套多层 response 包装。错误返回 `{code,message,details?,requestId}`，冲突为 HTTP 409，字段问题 422，跨项目/不存在为 404，模型未配置 503。

## 平台登录与会话

平台支持配置一个工作空间账号，登录后访问本实例项目；项目与资产引用仍经过领域层的 `projectId` 校验。`security.enabled=false` 仅允许绑定明确的回环 IP，以兼容已有本机实例。远程监听必须启用认证并配置有效凭据，未配置时启动失败。

| 接口 | 请求与结果 |
| --- | --- |
| `GET /auth/session` | 公开、`Cache-Control: no-store`；返回 `{enabled,authenticated,username,csrfToken,csrfHeader:"X-CSRF-TOKEN"}`。未登录不暴露账号名；本机免登录模式返回 `enabled:false,authenticated:true,username:null,csrfToken:null` |
| `POST /auth/login` | `application/x-www-form-urlencoded` 的 `username,password`，同时携带会话 Cookie 和 CSRF 头；成功 200 `{authenticated:true}`，账号或密码错误 401 `INVALID_CREDENTIALS` |
| `POST /auth/logout` | 需要当前 CSRF 头；成功 204，作废服务端会话并关闭该会话已打开的 SSE。已提交的后台任务不因退出而取消 |

登录前读取会话接口，登录成功后再次读取，取得轮换后的 CSRF 令牌。会话使用 `AI_TEST_SESSION` Cookie，设置 HttpOnly、SameSite=Lax，默认空闲 30 分钟失效，HTTPS 部署可启用 Secure。服务端不接受 URL 会话 ID，不向前端发放持久 JWT。

启用认证时，所有业务 API、模板/报告/附件下载和 SSE 均要求登录。无会话为 401 `AUTHENTICATION_REQUIRED`，不会跳转 HTML 登录页；有会话但写请求的 CSRF 缺失或失效为 403 `CSRF_INVALID`。登录也受 CSRF 保护。静态应用路由与健康检查公开，内部停止接口继续单独检查真实回环连接及每实例随机令牌。SSE 的异步完成允许正常返回，但持续输出会重新核对原会话及空闲期限。

客户端收到会话错误后不得自动重放写请求。会话失效时保留当前标签页的人工草稿，重新登录后由用户明确重试；主动退出会清理页面状态并提示未保存编辑。客户端不会持续轮询会话接口来延长会话。

## 资产

```typescript
type Asset = {
  id: string; projectId: string; type: AssetType; parentId: string | null;
  name: string; version: string; position: number; source: 'MANUAL'|'AI'|'IMPORT';
  confirmed: boolean; createdAt: string; updatedAt: string;
  data: Record<string, unknown>;
};
type Page<T> = {items: T[]; total: number};
```

类型：`PROJECT, ENVIRONMENT, AUTH_CONFIG, DATABASE_SOURCE, MODULE, REQUIREMENT, FUNCTIONAL_CASE, FUNCTIONAL_STEP, API_DEFINITION, API_CASE, SCENARIO, SCENARIO_STEP, SQL_VALIDATION, DATASET, UI_SCENARIO, UI_STEP, TEST_PLAN, PLAN_ITEM, BUG, DASHBOARD, QUALITY_BRIEF, WEBHOOK`。

自动晨报使用独立的人工排期接口 `/api/projects/{projectId}/morning-brief/schedule`（GET/PUT）、`/history`（GET）与 `/occurrences/{id}/retry`（POST）；字段、CAS、同日期去重和固定统计约定见 [自动晨报](morning-brief.md)。生成结果仍为普通 `QUALITY_BRIEF`，支持既有局部和全局反馈。

- `GET /catalog` → `{types:[{type,label,fields:[{key,label,kind,required,options?,defaultValue?}],childTypes:[],formats:[]} ]}`。kind 为 `text,textarea,number,boolean,select,json,sql,code,password`。
- `GET /projects` → `Asset[]`。
- `POST /projects` `{name,data:{description?}}` → Asset。
- `GET /projects/{projectId}/assets?type=&parentId=&q=&offset=0&limit=100` → Page<Asset>。不提供 parentId 时返回该类型的所有记录；parentId=ROOT 表示根节点。
- `GET /projects/{projectId}/assets/{id}` → Asset。
- `POST /projects/{projectId}/assets` `{type,name,parentId?,data}` → Asset。
- `PATCH /projects/{projectId}/assets/{id}` `{baseVersion,name?,data?,confirmed?}` → Asset。data 为字段级补丁，不允许修改身份；子节点单独 CRUD。
- `DELETE /projects/{projectId}/assets/{id}?baseVersion=` → 204。外部引用阻止删除并列出使用处；删除父节点连同自身子节点原子处理。
- `POST /projects/{projectId}/assets/reorder` `{type,parentId?,items:[{id,baseVersion}]}` → Asset[]。提交同一范围完整有序列表。
- `GET /projects/{projectId}/assets/{id}/history` → `[{id,assetId,version,operation,source,createdAt,snapshot:Asset}]`。
- `POST /projects/{projectId}/assets/{id}/undo` `{baseVersion,revisionId}` → Asset，撤销也是新版本。
- `GET /projects/{projectId}/assets/{id}/children` → Asset[]。

## AI 和任务

- `GET /settings/model` → `{baseUrl,modelName,hasApiKey,temperature,timeoutSeconds,trustSelfSigned}`。
- `PUT /settings/model` `{baseUrl,modelName,apiKey?,temperature?,timeoutSeconds?,trustSelfSigned?}` → 同上；空 apiKey 保留现有密钥。`trustSelfSigned=true` 时模型调用跳过证书链与主机名校验，只用于公司内网自签名/私有 CA 的 https 网关。
- `POST /settings/model/test` → `{ok,message}`，真实请求，不写资产。
- 流水线 S3 每次模型调用只带 `aitest.pipeline.api-batch-size`（默认 8，环境变量 `AI_TEST_API_BATCH_SIZE`）个接口定义，且只发送该操作、pathItem 与其引用的 components，不发送整份 OpenAPI 文档；existingAssets 只含既有接口用例/场景摘要与功能用例名称。一批最多 3 轮生成，每轮只补仍未覆盖的接口，已通过的用例立即保存。各阶段的 `sourceEvidence` 按用途裁剪：S1–S3 只带 backend，S4 带 backend+database，S5 带 frontend+backend 与静态分析告警；FORMAT_REPAIR 只回传契约相关字段与无效输出，不重复发送资料。
- `POST /settings/model/models` `{baseUrl,apiKey?,trustSelfSigned?}` → `{models:[...]}`，向服务商的 OpenAI 兼容 `GET {baseUrl}/models` 取模型列表，用于填写模型名称时下拉选择；空 apiKey 使用已保存密钥，服务商不提供该接口时返回 502 `MODEL_LIST_UNSUPPORTED`，此时手填模型名称即可。
- `POST /ai/generate` `{projectId,type,parentId?,instruction,sourceIds?,conversationId?,idempotencyKey}` → `{jobId,conversationId}`。显式新会话 ID 隔离生成历史，已有 ID 必须匹配项目、父级和生成类型；同一次提交响应不确定时重试使用原幂等键。
- `POST /ai/refine-item` `{projectId,targetType,targetId,baseVersion,conversationId?,feedback,targetFields?,applyMode:'REPLACE_ON_SUCCESS'|'PREVIEW',idempotencyKey}` → `{jobId,conversationId}`。
- `GET /ai/conversations/{id}?projectId=` → `{id,scope,targetId,messages:[{id,role,content,status,createdAt,baseVersion?,appliedVersion?,candidate?,validation?}]}`。
- `POST /ai/feedback` `{projectId,pipelineId?,conversationId?,feedback,assetIds?,idempotencyKey}` → `{jobId,conversationId}`，生成全局候选。
- `GET /ai/change-sets/{id}?projectId=` → `{id,status,items:[{id,operation,targetType,targetId,baseVersion,before,after,validation}]}`。
- `POST /ai/change-sets/{id}/apply` `{projectId,itemIds}` → `{status,assets}`，全选项短事务，冲突全部回滚。
- `POST /ai/change-sets/{id}/reject` `{projectId}` → 204。
- `GET /jobs/{id}?projectId=` → `{id,projectId,kind,status,progress,message,result,error,createdAt,updatedAt}`。状态 `QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELLED,INTERRUPTED`。
- `POST /jobs/{id}/cancel` `{projectId}` → Job。
- `GET /jobs/{id}/events?projectId=&after=` 为 SSE，event 名 `progress,token,asset,result,error,done`，id 为持久化序号，data 为 JSON；支持 Last-Event-ID。

## 文件交换、执行与工作台

- `GET /templates` → `[{family,label,version,variants:[{format,importFormat,type,filename,instructions}]}]`。
- `GET /templates/{family}?format=` 下载模板。family 使用模板清单返回的值。
- `GET /exchange/capabilities` → `[{type,importFormats,exportFormats}]`。前端据此展示实际支持的方向与格式；`catalog.formats` 仅为旧的规划字段。
- `POST /projects/{projectId}/exports` `{type,format,assetIds?,options?}` → 二进制下载，文件名在 Content-Disposition。
- `POST /projects/{projectId}/imports/preview` multipart `file,type,parentId?,format?,referenceMappings?,columnMappings?,columnTypes?`，三个 mappings/types 字段为 JSON 字符串 → ImportPreview。
- `GET /projects/{projectId}/imports/{id}` → ImportPreview；重新进入页面可读取持久化预检。
- 预览 `metadata.referenceMappings/columnMappings/columnTypes` 返回资源与列绑定；DDT 的 `originalColumns` 保留映射前的列名。界面重开时回填这些值，空映射使用原名，修改映射后必须重新预检。
- DDT 预览的 `metadata.mappingCapabilities:{renameColumns,convertTypes}` 根据实际文件决定可用变换。普通 CSV/XLSX 支持改名与类型转换；平台导出的带类型 CSV/XLSX 仅支持改名并保留单元格类型；便携 JSON/YAML/ZIP 不支持列变换。非空的不支持选项返回 INVALID。多工作表声明按列名并集校验、分别应用于相关表。旧 READY 预览若含此前被忽略的变换，读取时显示 INVALID，应用端也独立拒绝；已 APPLIED 历史保持原状态。
- `POST /projects/{projectId}/imports/{id}/apply` `{}` → `{importId,keyToId,assetIds,createdCount}`。仅 READY 可提交，整批原子新增，不覆盖已有记录；重复提交返回同一结果。映射变化后重新预检。
- `POST /projects/{projectId}/documents` multipart `file,idempotencyKey?` → Requirement Asset。
- `POST /projects/{projectId}/documents/read` `{path?,text?,name?,idempotencyKey?}` → Requirement Asset。重试相同键和输入返回已创建的同一需求及当前人工版本；不同输入返回 409。路径读取一旦成功，重试不会重新读取变化或已消失的源文件；已删除的结果返回 410 DOCUMENT_RESULT_DELETED。键在项目内隔离，解析失败不占用键。新向导始终传键，旧客户端不传键时保留原有新增语义。
- `POST /projects/{projectId}/runs` `{assetId,environmentId?,datasetId?,idempotencyKey}` → `{jobId,runId}`。
- `GET /projects/{projectId}/runs` → Page<Run>。
- `GET /projects/{projectId}/runs/{id}` → Run，包含 items、steps、snapshot、统计。
- `POST /projects/{projectId}/runs/{id}/manual-result` `{itemId,baseVersion,status,notes}` → Run。baseVersion 使用对应 item.manualVersion；支持 PASSED/FAILED/BLOCKED/SKIPPED，拒绝覆盖并发人工结果。
- `GET /projects/{projectId}/summary` → `{counts,runSummary,recentRuns,recentBugs}`。
- `GET /projects/{projectId}/databases/{id}/schema` → `{tables:[{name,columns:[]} ]}`。
- `POST /projects/{projectId}/databases/{id}/validate-query` `{sql}` → `{valid,databaseSourceId,sourceVersion,parameterCount}`。先过只读 SQL AST 策略，再使用实际业务库 EXPLAIN 校验标识符，参数绑定 null；不执行测试查询或写入。
- `POST /projects/{projectId}/assets/{id}/validate-execution` `{environmentId?,datasetId?}` → `{valid,errors:[{assetId,name,field,variable,message,rowIndex?}],availableVariables,checkedItems,errorsTruncated}`。按实际场景顺序、数据行和步骤变量寿命检查，不发送请求。
- `POST /projects/{projectId}/environments/{id}/page-evidence` `{idempotencyKey}` → `{jobId}`。独立浏览器进程采集当前 Web 地址，任务结果包含真实页面标题/文本/元素定位器、Frame 信息、截断标记、环境版本和 `fileId`。不读取表单值，不创建 UI 测试资产；受管 JSON 可下载。
- `POST /projects/{projectId}/databases/{id}/query` `{sql,variables?,allowWrite?,dryRun?}` → SQL 执行结果。
- `GET /projects/{projectId}/files/{id}` 下载受管附件。
- `POST /projects/{projectId}/runs/{id}/diagnose` `{idempotencyKey}` → `{jobId,conversationId}`。使用保存的 HTTP/SQL/UI/人工失败证据；同一失败发生记录幂等，相同指纹关联已有缺陷，不改人工内容、版本或状态；已删除缺陷仅记录抑制事件。取消后不创建新缺陷。
- `GET /projects/{projectId}/runs/{id}/diagnoses` → `{items:[{id,bugId,jobId,status,evidence,diagnosis,modelStamp,promptVersion,createdAt,...}]}`。
- `GET /projects/{projectId}/bugs/{id}/occurrences` → `{items,occurrenceCount}`；状态为 CREATED、RECURRENCE 或 SUPPRESSED_DELETED。缺陷通用资产新增 `rootCauseAnalysis`，可手工编辑和局部调优。
- 测试计划的 `diagnoseFailures=true` 会在运行结果提交时持久化自动诊断任务。模型未配置或输出无效时准确记录失败与会话历史，客观运行结果不受影响。诊断故障重试使用新的幂等键，原有失败发生记录不会重复。

前端只更新服务端返回的目标 ID；SSE 事件按 jobId/projectId 过滤。切换项目应取消前端订阅，不取消服务器已接受任务。资产名称或数组位置不能代替身份。

```typescript
type ImportPreview = {
  id: string; projectId: string; type: AssetType; parentId?: string; format: string;
  status: 'READY'|'INVALID'|'APPLIED'; fileId: string; filename: string; checksum: string;
  metadata: Record<string, unknown>;
  nodes: {key: string; type: AssetType; parentKey?: string; name: string; position: number; data: Record<string, unknown>; references: Record<string, string>}[];
  errors: {source: string; row: number; field: string; message: string}[];
  warnings: unknown[]; result?: {importId: string; keyToId: Record<string,string>; assetIds: string[]; createdCount: number};
  createdAt: string;
};
type StepResult = {
  status: string; durationMs: number; request: Record<string,unknown>; actual: Record<string,unknown>;
  assertions: {type:string; path:string; operator:string; expected:unknown; actual:unknown; passed:boolean; message?:string}[];
  exports: Record<string,unknown>; artifactIds: string[]; error?: string;
};
```

运行详情包含 `summary:{total,counts,caseCount,dataRows}` 和 `items:[{id,assetId,name,assetType,position,rowIndex,variables,status,durationMs,error,notes,manualVersion,steps:[{id,assetId,name,engine,status,position,result:StepResult}]}]`。`total` 是展开 DDT 后的运行项数，`caseCount` 是去重资产数；缺少人工结果不计为通过。运行快照与测试时配置绑定，当前资产编辑不能改写历史事实。

## 六阶段流水线与重试

- `POST /ai/pipelines` `{projectId,requirementIds,apiDefinitionIds?,environmentId?,databaseSourceIds?,uiEvidenceIds?,execute?,idempotencyKey}` → `{pipelineId,jobId,conversationId,status}`。至少一份需求；缺失 API、业务库或页面证据会明确记录阶段缺口。环境缺省时仅在项目恰好有一个环境时选用它；execute 缺省采用环境 autoRunGenerated。
- `GET /ai/pipelines?projectId=` → 最近 100 条 `{id,status,currentStage,progress,createdAt,updatedAt}`。
- `GET /ai/pipelines/{id}?projectId=` → `{id,projectId,jobId,conversationId,status,currentStage,progress,revision,config,assetIds,runId,error,steps,attempts,createdAt,updatedAt}`。steps 为每阶段最新尝试，attempts 为完整历史；每项含 `{id,stage,status,attempt,jobId,input,output,error,startedAt,completedAt}`。
- `POST /ai/pipelines/{id}/resume` `{projectId,stage?,idempotencyKey,environmentId?,apiDefinitionIds?,databaseSourceIds?,uiEvidenceIds?,execute?}` → `{pipelineId,jobId,activeJobId,conversationId,status}`。完成阶段不能重建，使用全局反馈调整。重试相同请求键返回原尝试 jobId 和当前流水线状态；失败尝试再次执行要使用新键。补充配置只用于后续尝试，历史运行保持快照。
- `POST /ai/pipelines/{id}/cancel` `{projectId}` → Pipeline。停止当前阶段及后续编排，已保存资产仍可编辑；迟到结果不能写入。请使用流水线取消接口，而不是仅取消启动时的某一个阶段任务。

阶段为 S1 需求分析、S2 功能用例、S3 接口场景链、S4 SQL、S5 UI、S6 计划执行与诊断。流水线状态包括 RUNNING、COMPLETED、COMPLETED_WITH_GAPS、FAILED、CANCELLED、INTERRUPTED；阶段另有 QUEUED、BLOCKED、WAITING_RUN、WAITING_DIAGNOSIS。COMPLETED 表示编排和记录完成，不代表所有测试通过；实际通过/失败/待人工由 runSummary 表示。

每阶段独立持久化任务，jobId 随阶段/运行/诊断变化。前端以流水线 ID 获取权威进度，按当前 jobId 订阅现有 SSE，终止旧订阅后接入新任务；不能将第一个阶段的 done 视作整条流水线完成。无需执行时 S6 仍建立真实计划并返回 execution=NOT_REQUESTED。

分片/批次保存检查点。失败重试不重复已提交批次；部分保存后来源版本发生变化，返回 PIPELINE_SOURCE_CHANGED 并保留已有资产，改用全局反馈处理。SQL 校验依据真实 Schema/EXPLAIN，UI 定位器和导航地址依据真实页面或录制资产。S6 诊断失败重试沿用原 runId，不重放 HTTP/SQL/UI 副作用。

使用流水线返回的 conversationId 和 pipelineId 调用 `/ai/feedback` 可连续进行全链路反馈；当前人工编辑进入下一轮上下文，已删除的流水线资产不会阻断反馈。GLOBAL 会话必须对应项目全局，PIPELINE 会话必须对应同一条流水线；LOCAL、GENERATE、DIAGNOSE 会话不能复用到全局反馈。所有候选仍通过既有变更集接口选择采纳，局部调优继续使用 refine-item。

- `GET /projects/{projectId}/runs/{id}/report?format=html|pdf|json|zip|csv|xlsx` 下载真实运行报告，默认 html。使用响应 Content-Disposition 文件名；非 2xx 按 JSON 错误处理。HTML/PDF 包含实际统计、DDT 行、逐步请求/实际/断言、中文截图及人工状态，ZIP 另含原始附件和 JSON。CSV/XLSX 为逐运行项矩阵，CSV 使用 BOM 和公式前缀转义，XLSX 将文本保留为文本单元格。报告读取固定资产快照及导出时已保存的人工结果。
- 所有资产类型的 `exportFormats` 增加 `html/pdf`，复用现有 `/exports`。PDF 单独进程渲染，繁忙、超时、文件过大等返回明确错误，可改用 HTML/JSON 或缩小范围。展示 HTML 时下载或隔离预览，不将内容直接注入应用 DOM。

UI 交换新增方向：`UI_SCENARIO/UI_STEP` 可导入 Codegen `java/js/ts`，导出 `java` 单文件或 `java-project` 工程 ZIP。Codegen 导入限制为可无损转换的录制语法，错误返回实际行号，INVALID 不可提交。独立 Java 运行器不是 Codegen 语法；工程中的 bundle.json 用于重新导入。UI_STEP 新增 `exactMatch:boolean`，既有资产默认 true；录制源的默认非精确匹配保留为 false。
# API 文档变更与定向修复

- `POST /api/projects/{projectId}/api-diffs/preview`：`{importId,definitionMappings?:{[importKey]:definitionId}}`。使用尚未采纳且预检通过的 `API_DEFINITION` 导入；不会执行通用追加导入。按唯一 operationId、方法/路径匹配，歧义由显式映射解决。
- `GET /api/projects/{projectId}/api-diffs?offset=0&limit=30`：`{items:[{id,importId,filename,status,createdAt,changeCount}],total}`，limit 为 1–100。
- `GET /api/projects/{projectId}/api-diffs/{id}`：`{id,projectId,importId,filename,status,createdAt,items}`。status 为 `READY/UNCHANGED/PARTIAL/APPLIED`。
- 每项包括 `id,kind,match,importKey,definitionId,before,candidate,matchCandidates,fields,affectedAssetIds,links,originalAffectedAssetIds,originalLinks,status,appliedVersion,current`。kind 为 `ADDED/REMOVED/CHANGED/UNCHANGED/AMBIGUOUS`；match 为 `EXPLICIT/OPERATION_ID/METHOD_PATH/NONE/ABSENT`。`before` 为原定义快照，`candidate={key,name,parentId,data}` 为导入候选，`current` 为当前真实定义；三者版本语义分开。fields 为 `{path,kind,before,after}` 数组，path 使用 JSON Pointer 转义。links 为 `{fromId,toId,field}`；original 前缀记录预览时依赖，普通 affected/links 根据当前资产重算。
- `POST /api/projects/{projectId}/api-diffs/{id}/apply`：`{itemIds,idempotencyKey}` → `{diffId,assets,removedIds,acceptedItemIds}`。每项只能采纳一次，可分批选择；同一项目的幂等键固定 diff 与选择，重复恢复同一结果。全部选定项在同一事务检查/写入，版本、身份或引用冲突返回 409，零部分写入。接口用例不会随定义采纳而自动覆盖。
- `POST /api/projects/{projectId}/api-diffs/{id}/heal`：`{instruction,conversationId?,idempotencyKey}` → `{jobId,conversationId}`。必须先采纳接口变更。会话固定 `scope=API_DIFF,targetId=diffId`；跨对比/项目拒绝。每轮读取已采纳接口的当前版本和当前受影响资产，保留人工确认资产，禁止新增、删除、移动、改引用或编造凭证。
- 修复任务结果为 `{changeSetId,status:'PREVIEW'}`，沿用 `/api/ai/change-sets/{id}` 的查看、选择采纳与拒绝；无需修改时为 `{status:'NO_CHANGES',message}`。已记录的接口依据若随后变化，采纳返回 `CHANGE_EVIDENCE_CONFLICT`，必须基于当前内容再反馈。原始导入和内部对比快照受加密保护，公开差异与模型上下文脱敏。

## 基于实际运行的质量简报

- `GET /api/projects/{projectId}/quality-metrics?runId=` 返回 `aitest.quality-metrics/v1`。指定 runId 时读取当前项目该次运行；省略时覆盖最近 24 小时内创建的运行。包含完整状态计数、展开 DDT 后的运行项总数、去重用例、数据行、通过率、真实耗时样本、失败样本及截断标记、采集时间与快照哈希。零运行项的通过率为 null。
- `/api/ai/generate` 对 `QUALITY_BRIEF` 新增可选 `runId`，不接受 quality sourceIds；其他类型不能使用 runId。GENERATE 会话按项目、资产类型和运行范围隔离。模型正文由 `test_summary_report` 渲染，服务器将同一份实际统计注入候选及最终简报。
- 全局 ADD 质量简报同样使用服务器事实快照。局部调优与全局 MODIFY 禁止改写 metrics/runId，保留名称及正文编辑；历史快照不随以后运行结果改变。详见 `docs/quality-reports.md`。

## 持久化巡检排期

- `GET /api/projects/{projectId}/plans/{planId}/schedule?offset=0&limit=25`：计划配置、有效时区、下一触发点、等待数/上限和触发历史。`limit` 为 1–100；返回 `items/total`。历史含 `scheduledFor/misfireThrough`、`planVersion/dispatchedPlanVersion`、`status/reason`、`runId/jobId/runStatus`。所有时间为带时区的 ISO 时间；`misfireThrough` 是合并触发区间的截止点。
- `PUT /api/projects/{projectId}/plans/{planId}/schedule`：`{baseVersion,cronExpression?,timezone?,scheduleEnabled?,overlapPolicy?,misfirePolicy?}` → 更新后的 Asset。仅接受这些字段，沿用资产版本冲突与历史机制。策略分别为 `SKIP|QUEUE` 和 `SKIP|FIRE_ONCE`。
- `POST /api/projects/{projectId}/plans/{planId}/schedule/preview`：`{cronExpression,timezone?,from?}` → `{timezone,from,nextFireTimes}`。`from` 可指定 ISO 时间，默认当前时间。只预览、不写入。

状态包括 WAITING、SUBMITTED、SKIPPED_OVERLAP、SKIPPED_MISFIRE、SKIPPED_CAPACITY、CANCELLED、FAILED。配置变更/禁用/删除只取消未派发触发。AI 的生成、局部反馈、全局变更集创建与历史候选采纳均不能修改启用开关。完整语义见 `docs/plan-scheduling.md`。

## 不可变源码分析

- `POST /api/projects/{projectId}/source-analyses`：`{backendRepoPath?,frontendRepoPath?,sqlScriptPath?,ddlText?,backendRef?,frontendRef?,baselineRef?,idempotencyKey}` → `{analysisId,jobId}`。未知字段或非文本值返回 400；相对路径、无效版本等返回 422；同幂等键不同请求返回 409。省略路径读取提交时项目设置，空字符串显式不使用该来源。
- `GET /api/projects/{projectId}/source-analyses?offset=0&limit=25` → `{items,total}`；`limit` 为 1–500。包含项目版本、任务、文件数、总字节、清单哈希和状态。状态为 QUEUED/RUNNING/READY/PARTIAL/FAILED/CANCELLED/INTERRUPTED。
- `GET .../source-analyses/{id}` 返回配置、诊断及 `aitest.source-evidence/v1` 结果：`origins/backend/baselineBackend/frontend/database`。源码路径和 Git revision 是提交/采集时依据；不返回粘贴 DDL 原文。
- `GET .../{id}/files?offset=0&limit=100` → 固定文件清单。`GET .../{id}/file?kind=BACKEND&path=...&from=1&to=100` → 固定且脱敏的源码片段及 SHA-256、行范围。kind 为 BACKEND/BASELINE/FRONTEND/DDL。未完成快照返回 409，跨项目返回 404。
- `POST .../{id}/cancel` → 取消后的分析元数据。重复取消或对终态取消不会重放任务。

项目 Asset 数据增加 `backendRepoPath/frontendRepoPath/sqlScriptPath`，依然使用普通版本校验与修订。源码只读采集、Git 参数/资源限制和静态解析边界见 `docs/source-analysis.md`。

## 源码影响分析与定向回归

- `POST /api/projects/{projectId}/source-analyses/{sourceId}/impact`：`{baselineSnapshotId?,idempotencyKey}` → `{impactId,jobId}`。空基线使用 sourceId 采集时保存的 Git baseline；否则比较两份同项目固定快照。需要可用后端源码。同键同输入返回原任务，同键不同输入返回 409。
- `GET .../source-analyses/{sourceId}/impacts?offset=0&limit=25` → `{items,total}`；limit 为 1–100。
- `GET /api/projects/{projectId}/source-impacts/{impactId}` → `{id,projectId,sourceSnapshotId,baselineSnapshotId,jobId,status,error,createdAt,completedAt,result}`。READY 结果包含 `files/changedMethods/classChanges/baselineGraph/headGraph/affectedEndpoints/affectedTables/diagnostics/candidates` 及固定来源绑定；未完成结果为空对象。图内 `nodes/edges/unresolvedCalls` 保留静态歧义，`completeRuntimeCoverage=false`。
- `POST .../source-impacts/{impactId}/cancel`：取消尚未完成的分析，已经发布的报告保持不可变。
- `POST .../source-impacts/{impactId}/regression-plan`：`{assetIds,name,environmentId?,idempotencyKey}` → `{planId,impactId}`。显式选择合法候选，复核依赖版本和子项成员；空、重复、跨项目、非候选或已过期选择拒绝。按普通 TEST_PLAN/PLAN_ITEM 创建，不自动执行。

TEST_PLAN 可带 `sourceSnapshotId/impactId`。运行公共/加密快照的 `graph.sourceEvidence` 保存相应固定文件清单哈希、版本和报告绑定；旧运行缺少该字段按空列表读取。来源 ID 不是 AssetReferences。原始报告加密保存，输出片段遵循源码脱敏。具体边界见 `docs/source-impact.md`。

## 固定来源生成与两级反馈

- `/api/ai/pipelines`、`/api/ai/pipelines/{id}/resume`、`/api/ai/generate` 和 `/api/ai/feedback` 的输入增加可选 `sourceSnapshotId`。只允许同项目 READY/PARTIAL 快照。流水线已经绑定时拒绝更换或移除来源；已有资产的 AI 修改保持各自绑定。
- 全局反馈新增资产继承父来源或显式选定快照；未指定且 manifest 只有一个来源时继承该来源。多个来源且新增无来源根资产时，任务以 `SOURCE_GENERATION_EVIDENCE_INVALID` 失败，不产生候选写入；纯修改仍分别按各目标的来源处理。
- REQUIREMENT、FUNCTIONAL_CASE/STEP、API_DEFINITION/CASE、SCENARIO/STEP、SQL_VALIDATION、UI_SCENARIO/STEP、TEST_PLAN/PLAN_ITEM、BUG 数据新增 `sourceSnapshotId`、`generationEvidence`。后者是服务器认证的只读生成依据，包含 `formatVersion`、清单哈希、SQL/UI 校验和配置缺口。模型不可构造或改写它，前端不把它混入 UI 步骤 DSL。
- SQL 的 `databaseSourceId` 允许为空以保存 DDL 草稿。调用 `/runs` 发现未绑定数据源时，在创建运行及任何副作用前返回 422 `EXECUTION_CONFIGURATION_REQUIRED`，details 中对应 `DATABASE_SOURCE_REQUIRED`。源码 UI 缺地址对应 `WEB_BASE_URL_REQUIRED`。流水线 S6 保存相同 validation 并提供补配置恢复入口。
- 局部 `refine-item`、全局变更集创建/采纳及通用生成使用相同来源校验；SQL 有 live 数据源时另外在事务外执行 Schema/EXPLAIN。执行插值与依赖解析不消费生成证据元数据。详见 [固定来源生成](source-grounding.md)。

## 固定源码 RCA 与人工评价

- BUG 的 `codeDiagnosis` 默认为 `{}`，非空使用 `aitest.code-rca/v1`：`formatVersion/root_cause/affected_code_path/suggested_fix/is_regression/confidence`。详细限制见 [源码诊断](code-diagnosis.md)。局部代码调优传 `targetFields=["codeDiagnosis"]`；普通文字诊断保持兼容。
- `GET /api/projects/{projectId}/bugs/{bugId}/code-evidence`：返回原始发生记录中 `aitest.failure-source-evidence/v1` 的 `bindings/locations/diffs/diagnostics/complete`。无来源或旧记录给出空集合与明确诊断；不读取后来改变的业务文件。
- `GET .../rca-evaluations?offset=0&limit=25`：`{current,items,total,diagnosisHash}`。分页上限 100，current 按当前诊断内容摘要查询。
- `POST .../rca-evaluations`：`{baseVersion,verdict,regression,note?,idempotencyKey}`。verdict 为 CORRECT/PARTIAL/INCORRECT/UNREVIEWED；regression 为 CONFIRMED/NOT_REGRESSION/UNREVIEWED。服务器保存 actor=LOCAL_USER、source=MANUAL、资产版本、内容摘要与时间。版本过期或同键不同输入返回 409，同键同输入恢复原评价。
- 人工评价属于独立不可变历史，不是 BUG 可编辑字段，也不进入 AI 输出 schema。全局采纳和旧候选应用仍重新检查补丁/源码范围；源码证据不由候选提供。补丁只展示、复制和下载，不执行业务仓库写入。

## EvalOps 与历史计价

- `GET /api/projects/{projectId}/evalops?from=...&to=...`：返回真实 `usage/generation/execution/rca/byModel`，缺少样本、用量或计价资料为 null。时间区间采用 `[from,to)`；`byModel` 按请求模型和配置版本分组。
- `GET .../evalops/invocations?offset=0&limit=25&modelName=...&modelVersion=...&from=...&to=...`：分页上限 100，保存 HTTP 尝试数、真实用量、耗时、请求/响应模型、模板版本与调用时价目，费用不会随新价目回算。
- `GET /api/settings/model/pricing?modelName=...` 和 `PUT /api/settings/model/pricing`：价目包含 `modelName/baseVersion/enabled/currency/inputPerMillion/outputPerMillion`，首次版本为 0，后续 CAS 冲突返回 409。`currency` 为 1–16 位字母、数字或 `. _ -` 的自由代码（保存时转大写），不限于 ISO 4217，服务商自定义的积分、额度单位同样可用，费用按代码分别汇总。
- 精确分母、原始响应采集及已知统计边界见 [AI 效能与模型计价](evalops.md)。
