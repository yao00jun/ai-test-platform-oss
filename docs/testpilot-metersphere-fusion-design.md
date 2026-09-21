# 全息五维输入驱动：TestPilot-AI 与 MeterSphere 优势融合设计方案 (Fusion Architecture Blueprint)

> 本文保留融合设计目标与原始参考。诸如“100% 准确率”“零脱靶”等表述属于设计愿景，不是经过验证的产品保证；当前实现、证据边界和未验证项以 `architecture-design.md` 与 `acceptance/evidence.md` 为准。静态源码线索、运行事实及 AI 推断在产品中分别记录，人工裁决与两级反馈约束持续有效。

---

## 一、 方案背景与融合愿景

在传统自动化测试和敏捷测试实践中，行业面临两大痛点：
1. **传统测试平台（如 MeterSphere V3）的局限**：拥有极佳的测试用例管理、接口测试、测试计划和报表工作流，但本质上仍属于**黑盒视角**。系统不知道后端代码是怎么写的，不知道数据库表长什么样，不知道前端真实 DOM 结构，AI 往往只能根据抽象的文字描述“凭空猜测”生成测试数据，容易产生字段虚构、断言无力、脱离实际代码实现的问题。
2. **新兴代码级 AI 测试工具（如 TestPilot-AI）的局限**：具备深度的 AST 语法树解析、Git Diff 变更分析、影响爆炸半径推导和代码级根因诊断（RCA），但主要局限在单个 Pull Request 的命令行或简单后端服务，缺乏完整的企业级测试资产管理大盘、脑图用例库、端到端 Playwright 浏览器录制回放、测试计划定时巡检以及面向测试工程师的友好人机协同界面。

### 🌟 我们的终极愿景：1 + 1 > 2
将 **MeterSphere V3 的成熟业务工作流与人机协同基石** 与 **TestPilot-AI 的代码级深度洞察能力** 进行深度融合，并引入全新的**【全息五维输入矩阵（5-Dimensional Holographic Input Matrix）】**。

打造业界首个**既看得懂需求 PRD，又看得穿前后端源码与数据库表结构，还能闭环执行测试、诊断代码并自动提 Bug 修复 Patch 的 AI 原生持续测试工作台**。

```
+----------------------------------------------------------------------------------------------------+
|                         MeterSphere V3 优势 (业务工作流与人机协同基石)                                 |
|  - 八大功能菜单 (工作台、项目管理、用例、接口、SQL校验、UI自动化、测试计划、缺陷管理)                  |
|  - 4大通用能力 100% 覆盖 (AI生成 + 手工CRUD + 模板下载 + 多格式导入导出)                               |
|  - 人机协同最高裁决权 (AI生产草稿，人类自由双击编辑/调序，两级反馈就地重新生成)                       |
|  - 生产级多环境管理、全局无感 Token 保活刷新、成熟 Flexmark/TextCleaner 工具类                         |
+----------------------------------------------------------------------------------------------------+
                                                  ➕
+----------------------------------------------------------------------------------------------------+
|                         TestPilot-AI 优势 (代码级深度智能与根因诊断)                                |
|  - AST 语法树解析 (JavaParser 解析 Controller / Service / Mapper / DTO)                            |
|  - 代码变更与影响面爆炸半径 (Git Diff + Blast Radius Impact Analysis) ➔ 精准回归测试选集            |
|  - 代码级根因诊断 (RCA - Root Cause Analysis) ➔ 报错堆栈直溯源码行号，自动生成修复建议 Patch           |
|  - 智能体条件路由流水线 (State-Machine Conditional Routing) 与 EvalOps 效能大盘                     |
+----------------------------------------------------------------------------------------------------+
                                                  ⬇
+----------------------------------------------------------------------------------------------------+
|                         🎉 融合结晶：全息五维输入驱动的 AI-Test-Platform                            |
|   ① BA 原始需求 (PRD)  ② 接口契约 (Swagger)  ③ 后端源码 (Java)  ④ 前端源码 (Vue/React)  ⑤ 数据库 DDL   |
+----------------------------------------------------------------------------------------------------+
```

---

## 二、 全息五维输入矩阵设计 (5-Dimensional Holographic Input Matrix)

在本项目中，用户在项目管理或一键启动流水线时，可以自由提供以下 5 个维度的输入源。**输入形式极大简化：不仅支持文件上传/粘贴，更全面支持输入开发机本地绝对路径（秒级直读，零上传等待）**。

| 维度编号 | 输入维度名称 | 支持格式 / 录入方式 | 系统捕获的关键信息 | 赋能的核心价值 |
| :--- | :--- | :--- | :--- | :--- |
| **Dim 1** | **📄 BA 原始需求文档 (PRD)** | 本地绝对路径 (如 `D:\PRD.docx`)、拖拽 Word/PDF/Excel/MD、文本粘贴 | 业务规则、业务角色、边界条件、业务流程图、期望验收结果 | 决定**“系统应该做什么”**，提供功能用例与业务逻辑的基准准则。 |
| **Dim 2** | **🔌 开发接口契约文档** | Swagger / OpenAPI 3.0 / cURL / HAR / Postman 集合 | API Path、HTTP Method、Query/Body Schema、Header 约束、Response 样例 | 决定**“通信契约是什么”**，消除接口字段命名的幻觉。 |
| **Dim 3** | **💻 后端源码目录 / Git 仓库** | 本地代码绝对路径 (如 `E:\project\backend`) 或 Git Repo URL | Controller 映射、Service 业务逻辑、MyBatis Mapper XML / JPA 实体、校验注解 (`@Valid`, `@NotNull`) | 决定**“底层逻辑如何实现”**。揭示 PRD 未提及的代码隐藏分支与内部异常。 |
| **Dim 4** | **🎨 前端源码目录 / Git 仓库** | 本地代码绝对路径 (如 `E:\project\frontend`) 或 Git Repo URL | Vue/React 组件、路由配置、真实 DOM 属性 (`data-testid`, `id`, `class`, `placeholder`)、前端表单校验规则 | 决定**“用户界面如何呈现”**。实现 100% 命中真实 DOM 的 Playwright 脚本生成，彻底杜绝选择器脱靶。 |
| **Dim 5** | **🗄️ 数据库 SQL 脚本 / DDL** | 本地绝对路径 (如 `D:\sql\schema.sql`)、DDL 文本粘贴、或动态直连库 | 数据表名、字段名、数据类型、主外键关联、唯一索引约束、字段注释 (`COMMENT`)、状态枚举取值 | 决定**“数据状态如何持久化”**。消除数据库断言幻觉，生成 100% 确定性的真实 SQL 校验语句。 |

---

## 三、 深度吸收 TestPilot-AI 的完整五大核心优势设计

通过引入源码与 DDL 脚本，平台彻底打破了黑盒测试的局限，深度吸收并复刻了 **TestPilot-AI 最硬核的五大核心能力**：

### 1. 核心吸收优势 1：SQL 数据校验不再靠“猜”，100% 确定性生成
* **传统黑盒痛点**：以往 AI 只能根据接口名称（如 `POST /order/refund`）和模糊常识去“猜测”该接口可能修改了 `t_order` 表的 `status`，经常虚构不存在的表名或字段名，导致 SQL 校验运行时全线报错。
* **吸收后解决方案**：
  - AI 结合**维度三（后端源码）**与**维度五（数据库 DDL 脚本）**，直接扫描 Spring Boot 后端 Controller ➔ Service ➔ MyBatis Mapper XML / JPA Entity；
  - 读取真实业务代码（如 `RefundServiceImpl.java` 中调用的 `orderMapper.updateStatus(orderId, "REFUNDED")`、`walletMapper.increaseBalance(userId, refundAmount)`、`walletLogMapper.insert(...)`）；
  - **落地效果**：AI 能够 **100% 分毫不差地确定该接口修改了哪几张表、哪几个字段、状态枚举值的准确取值**，并自动生成多表联合校验 SQL：
    ```sql
    SELECT o.status, w.balance, l.amount 
    FROM t_order o 
    JOIN t_wallet w ON o.user_id = w.user_id 
    JOIN t_wallet_log l ON o.order_no = l.order_no 
    WHERE o.order_no = '${orderNo}';
    ```
    断言准确率从 80% 盲猜跃升至 **100% 确定性**！

### 2. 核心吸收优势 2：隐藏代码分支全透视，生成“直击灵魂”的边界用例
* **传统黑盒痛点**：Swagger 接口文档通常仅简陋标注 `amount: number` 或 `name: string`，根本不会写具体的业务内部边界，测试人员无法设计出真正触达底层逻辑的分支用例。
* **吸收后解决方案**：
  - 基于 `JavaParser` 扫描 DTO / Entity 入参上的 JSR-303 校验注解（`@DecimalMin("0.01")`, `@Size(max = 20)`, `@Pattern`）以及 Service 实现类中的业务 `if / else` 拦截分支（如 `if (user.getVipLevel() < 3) throw new BusinessException("仅限VIP3以上");`）；
  - **落地效果**：自动挖掘隐藏在代码深处的拦截逻辑，针对性生成极端边界测试数据：
    - 边界用例 A：充值金额输入 `0.009`（精准触发 `@DecimalMin` 框架级校验拦截）；
    - 边界用例 B：非 VIP3 账号携带合法 Token 发包（精准验证业务权限拦截分支）；
    **测试用例的代码分支覆盖率（Branch Coverage）直接拉满！**

### 3. 核心吸收优势 3：精准回归测试与爆炸半径分析 (Blast Radius & Impact-Driven Selection)
* **传统黑盒痛点**：开发提交 Git 代码后，要么只测开发口头交代的单一接口（容易漏掉下游连锁反应），要么耗费数小时跑全量上千条自动化用例。
* **吸收后解决方案（TestPilot-AI 王牌算法移植）**：
  - 计算 Git Diff 变更差异，定位变更的类和方法；
  - 依赖关系向上追溯（Upstream Callers）：方法 ➔ 被哪些 Service 调用 ➔ 暴露在哪个 Controller 接口；
  - 依赖关系向下推导（Downstream Data）：涉及哪些数据库表状态变更；
  ```
  [开发提交代码] ➔ Git Diff 定位: UserServiceImpl.updateUserStatus()
         │
         ├─► 向上反查接口: POST /api/user/disable, POST /api/order/submit
         └─► 向下推导影响: t_user.status 状态变更
  ```
  - **落地效果**：在 2 秒内计算出“影响面爆炸半径”，精准圈选受影响的 3~5 个高风险接口场景链发起定向回归，**回归耗时从 30 分钟骤降至 15 秒，缺陷捕获率提升 10 倍**！

### 4. 核心吸收优势 4：源码级失败根因诊断 (True RCA) 与自动生成修复补丁
* **传统黑盒痛点**：接口在自动化巡检中报错 HTTP 500 时，普通平台只能复制粘贴一堆杂乱的前端错误报文，开发拿到 Bug 单仍需花大量时间打断点找原因。
* **吸收后解决方案（复刻 TestPilot-AI `failure_analysis_agent.py`）**：
  - 测试执行器抓取真实 Java 异常堆栈：`at com.aitest.service.impl.OrderServiceImpl.calcPrice(OrderServiceImpl.java:142)`；
  - 智能体直接顺着本地源码路径读取 `OrderServiceImpl.java` 第 142 行前后的真实代码上下文；
  - 调用大模型输出结构化 `FailureAnalysisResult`：
    ```json
    {
      "root_cause": "在计算优惠券折扣时，未对 coupon.getMinAmount() 为 null 的情况做判空处理，导致 NullPointerException。",
      "affected_code_path": "OrderServiceImpl.java:142",
      "suggested_fix": "```diff\n- if (orderAmount >= coupon.getMinAmount()) {\n+ if (coupon.getMinAmount() != null && orderAmount >= coupon.getMinAmount()) {\n```",
      "is_regression": true,
      "confidence": 0.95
    }
    ```
  - **落地效果**：自动提单到【缺陷管理】，开发点开 Bug 单直接看到一键复制的代码修复补丁（Diff），**10 分钟内即可完成代码修复合入**！

### 5. 核心吸收优势 5：Playwright UI 自动化定位器“绝对精准”（直接读前端源码）
* **传统黑盒痛点**：UI 自动化测试脚本编写繁琐，依赖手工 F12 审查元素或由 AI 瞎猜选择器，前端稍有改动脚本就全部报错脱靶。
* **吸收后解决方案**：
  - AI 读取**维度四（前端 Vue/React 源码）**；
  - 静态扫描前端组件与页面模板，直接提取最稳健、最规范的属性（如 `<button data-testid="btn-login-submit">`、`<input id="mobile" placeholder="请输入手机号">`）；
  - **落地效果**：生成的 Playwright 脚本直接采用官方推荐的高鲁棒性定位器：
    ```java
    page.getByTestId("btn-login-submit").click();
    page.getByPlaceholder("请输入手机号").fill("13800138000");
    ```
    彻底杜绝 UI 自动化选择器脱靶问题，一次生成，一次跑通！

---

### 6. 底层工程底座：AST 语法树解析器与 EvalOps 效能大盘
为稳健支撑上述五大核心优势，系统在底层构建了两大坚实工程底座：
* **原生 `JavaParser` AST 解析引擎**：免 Python / Tree-sitter 依赖，单体 Java 21 直接毫秒级递归解析类、方法签名、注解与调用链关系图；
* **EvalOps 智能体评估度量大盘**：工作台实时统计**用例生成通过率、回归缺陷拦截率、RCA 诊断准确率与 Token 效能比**，质量数据一目了然。

---

## 四、 坚守 MeterSphere V3 的工作流与人机协同基石

在融入 TestPilot-AI 强大代码智能的同时，我们必须**完整保留并传承 MeterSphere V3 成熟、清晰、高可用的人机工程设计**，绝不搞只有黑盒命令行的玩具系统：

### 1. 经典八大功能菜单架构 (UI/UX 导航骨架)
平台前端统一遵循清爽现代的侧边栏导航，层级结构清晰分明：
* 📊 **1. 综合工作台 (Workbench)**：
  - AI 每日自动化晨报、测试资产健康度大盘、EvalOps 效能分析、待办缺陷快速入口。
* 📁 **2. 项目管理 (Project Management)**：
  - 项目基础配置；
  - **五维输入源配置面板**：后端源码路径、前端源码路径、SQL 脚本路径、Swagger 文档关联；
  - 运行环境管理 (Dev/Test/Prod)、全局环境变量、全局前置鉴权 (Token 自动无感保活刷新)。
* 📝 **3. 功能测试用例 (Functional Test Case)**：
  - 模块树组织架构；
  - 脑图 (Mindmap) 与列表双向无缝切换视图（复用 MeterSphere `MdUtil`）；
  - 需求规格与用例双向关联。
* ⚡ **4. 接口与场景测试 (API & Scenario Test)**：
  - 单接口管理与变异参数调试；
  - 业务流场景链编排：支持请求间上下文变量引用（`${token}`, `${orderId}`）；
  - HTTP 虚拟线程执行器（Java 21 原生高并发，无需庞大 JMeter）。
* 🗄️ **5. 动态 SQL 安全校验核 (SQL Assertion Sandbox)**：
  - 动态 HikariCP 直连多数据源；
  - SQL 安全沙箱护栏：严禁无 WHERE 的危险写操作，只读与事务回滚保护。
* 🎭 **6. Web UI 自动化测试 (Playwright for Java)**：
  - 微软官方原生 Playwright 支持；
  - 页面对象步骤编排、自动等待、失败瞬间自动捕获高清截图并关联用例。
* 🗓️ **7. 测试计划与巡检 (Test Plan & Automation Patrol)**：
  - 定时巡检 Cron 任务调度；
  - 执行报告沉淀、企微/钉钉/飞书 Webhook 机器人 Markdown 告警卡片推送。
* 🐞 **8. 缺陷管理 (Defect Management)**：
  - Bug 生命周期流转；
  - 测试执行失败一键提单，自动携带堆栈、UI 截图及 **AI 代码级根因诊断与修复建议 Patch**。

### 2. 全模块 100% 具备“四位一体”通用能力
系统的所有功能模块，均无死角支持：
* **① AI 一键自动生成**：录入五维信息后，一键生成资产；
* **② 纯手工自由 CRUD**：100% 支持测试人员手动点击新增、双击修改、拖拽排序、批量删除；
* **③ 一键下载标准模板**：点击直接下载该模块专用的 `.xlsx` / `.csv` / `.json` 模板；
* **④ 多格式导入导出**：支持 Excel、CSV、JSON、XMind 导入与导出，满足传统敏捷交付要求。

### 3. 人机协同最高裁决权与两级重新生成 (Human-in-the-Loop)
* **AI 生产草稿，人类拥有终审裁决权**：
  - AI 生成的任何资产在数据库中与手工创建资产结构完全同构（仅 `ai_generated = 1` 标识）；
  - 界面上提供清晰视觉标签，测试人员可以任意微调入参、修改断言、拖动调整步骤顺序。
* **两级反馈与重新生成机制**：
  - **全局链路层级**：用户对生成的一整套场景链或用例集不满意，在底部输入全局意见（如“补充库存为0的异常流”），AI 携带上下文重新生成整套资产；
  - **单步骤就地微调 (🪄 局部 AI 调优)**：对某一条用例的断言或某句 SQL 不满意，点击该卡片右上角【🪄 局部 AI 调优】，输入具体要求，系统通过专用接口**就地单独重新生成该条目并原地无缝替换**，绝不破坏其他已经满意的步骤！

---

## 五、 五维全息输入带来的“1 + 1 > 2”质变场景

通过将 **五维输入** 与 **TestPilot-AI + MeterSphere** 深度融合，平台在测试生成和质量保障上产生了真正的化学反应：

### 场景 1：PRD 需求 vs. 后端代码“阴阳不一致”智能审查
* **现状痛点**：BA 写的 PRD 往往只有理想主流程；开发在代码里默默写了诸如“当用户连续失败3次锁死账户2小时”或“内部状态为99时抛出特定错误码”，但 PRD 里完全没写，测试人员黑盒压根测不到。
* **融合解法**：
  - AI 同时读取 PRD 与后端 Controller/Service 源码；
  - 智能比对两者的逻辑差集（PRD vs. Code Diff）；
  - 自动在用例列表前列出【⚠️ 隐性逻辑盲区预警】：“检测到后端 `AccountService.java` 中包含账户锁定与降级逻辑，但 PRD 未定义该需求。AI 已自动为您补充对应的边界测试用例！”

### 场景 2：100% 确定性的 SQL 校验注入（告别瞎猜表名）
* **现状痛点**：普通 AI 生成 SQL 断言时，由于不知道数据库实际表结构，经常胡乱捏造表名（如猜成 `user_order` 或 `t_order_info`），字段名也经常猜错，导致自动化运行时全线抛出 SQL 语法错误。
* **融合解法**：
  - AI 结合**维度五 (SQL DDL 脚本)** 与 **维度三 (后端 MyBatis Mapper XML / JPA Entity)**；
  - 精确获取真实表名 `t_order`、主键 `order_no`、状态字段 `pay_status` 及其枚举注释 `COMMENT '0-待支付 1-已支付 2-已退款'`；
  - 生成的断言 SQL 具备绝对确定性：
    `SELECT pay_status FROM t_order WHERE order_no = '${orderNo}' AND deleted = 0;`
  - 断言预期值精准比对：`pay_status == 1`。

### 场景 3：零脱靶的 Playwright UI 脚本生成
* **现状痛点**：传统 UI 自动化工具由 AI 猜测生成的 XPath 或 CSS 选择器极易脱靶，前端样式一改全部失效。
* **融合解法**：
  - AI 读取**维度四 (前端 Vue/React 源码)**；
  - 精确提取登录页、表单页组件中的真实属性：如 `<a-button data-testid="btn-submit-order">`、`<input id="username" placeholder="请输入手机号" />`；
  - 生成的 Playwright 脚本直接采用最稳定的高鲁棒性选择器：
    ```java
    page.getByTestId("btn-submit-order").click();
    page.getByPlaceholder("请输入手机号").fill("13800138000");
    ```
  - 零脱靶、零维护成本。

### 场景 4：失败即附带代码修复补丁的“秒级 Bug 单”
* **现状痛点**：测试提 Bug，开发通常嫌弃“没有重现步骤”、“没有报错日志”；开发拿到 Bug 还要花半天在 IDE 里单步调试寻找出错行。
* **融合解法**：
  - 接口跑测失败 ➔ 抓取 500 异常堆栈 ➔ 命中 `OrderServiceImpl.java:142`；
  - 系统根据后端源码抓取代码上下文，结合 TestPilot-AI 的 RCA 算法，自动生成带有 Diff 修复代码的缺陷单；
  - 开发人员在【缺陷管理】点击该 Bug，不仅有重现请求、实际返回值，更有现成的代码补丁。开发点击“复制补丁”并在 IDE 中合入，10 分钟完成缺陷修复。

---

## 六、 数据库表结构增量扩展方案

为支撑上述融合特性，我们在已有 `docs/db-schema.sql` 基础上进行精准增量扩展：

### 1. `project` 表增加源码与脚本路径配置
```sql
ALTER TABLE `project` 
ADD COLUMN `backend_repo_path` VARCHAR(255) NULL COMMENT '后端源码本地绝对路径或Git地址，如 E:\\workspace\\backend',
ADD COLUMN `frontend_repo_path` VARCHAR(255) NULL COMMENT '前端源码本地绝对路径或Git地址，如 E:\\workspace\\frontend',
ADD COLUMN `sql_script_path` VARCHAR(255) NULL COMMENT '数据库SQL脚本本地绝对路径，如 E:\\workspace\\db\\schema.sql';
```

### 2. `bug_issue` 缺陷表增加代码级 RCA 根因与修复建议字段
```sql
ALTER TABLE `bug_issue`
ADD COLUMN `root_cause` TEXT NULL COMMENT 'AI 代码级根因分析',
ADD COLUMN `affected_code_path` VARCHAR(255) NULL COMMENT '问题定位源码路径与代码行，如 OrderServiceImpl.java:142',
ADD COLUMN `suggested_fix` TEXT NULL COMMENT 'AI 生成的具体修复代码补丁 (Diff/Patch)',
ADD COLUMN `is_regression` TINYINT(1) DEFAULT 0 COMMENT '是否为代码变更引起的退化问题：0-否，1-是',
ADD COLUMN `confidence` DECIMAL(3,2) DEFAULT 0.00 COMMENT 'AI 诊断置信度 (0.00 - 1.00)';
```

### 3. `ai_pipeline_record` 流水线表增加代码分析元数据
```sql
ALTER TABLE `ai_pipeline_record`
ADD COLUMN `backend_repo_path` VARCHAR(255) NULL COMMENT '本次流水线使用的后端源码路径',
ADD COLUMN `frontend_repo_path` VARCHAR(255) NULL COMMENT '本次流水线使用的前端源码路径',
ADD COLUMN `sql_script_path` VARCHAR(255) NULL COMMENT '本次流水线使用的数据库SQL脚本路径',
ADD COLUMN `git_diff_summary` TEXT NULL COMMENT '代码变更差异摘要',
ADD COLUMN `impact_blast_radius` JSON NULL COMMENT '受影响API与调用链爆炸半径分析结果';
```

---

## 七、 后端代码架构与新增类划分 (Codex 开发规范)

在 Spring Boot 3.4 后端工程中，Codex 应按照以下模块划分组织代码：

```
backend/src/main/java/com/aitest/
├── ai/
│   ├── client/                   # Spring AI ChatClient 包装与企业大模型适配
│   ├── pipeline/                 # 全自动 Agent 编排流水线 (State-Machine 驱动)
│   │   ├── PipelineOrchestrator.java
│   │   ├── PipelineStage.java
│   │   └── steps/                # 各阶段具体执行器
│   │       ├── BaDocParseStep.java
│   │       ├── ApiContractMatchStep.java
│   │       ├── TestCaseGenerateStep.java
│   │       ├── SqlInjectStep.java
│   │       └── PlaywrightGenerateStep.java
│   └── text/                     # 文本清洗与解析 (复用 MeterSphere)
│       ├── MdUtil.java           # Flexmark AST 解析 (已复用)
│       └── TextCleaner.java      # 文本清洗与定界符 (已复用)
│
├── analysis/                     # 🌟 新增：吸收 TestPilot-AI 核心智能
│   ├── ast/                      # AST 语法树解析 (基于 JavaParser)
│   │   ├── JavaCodeParser.java   # 解析 Controller, Service, DTO
│   │   └── SpringEndpointVisitor.java
│   ├── diff/                     # Git Diff 解析
│   │   └── GitDiffAnalyzer.java  # 提取本次变更类与方法区间
│   ├── impact/                   # 爆炸半径与调用链分析
│   │   ├── BlastRadiusService.java
│   │   └── CallGraphBuilder.java
│   ├── rca/                      # 失败现场与代码级根因诊断
│   │   ├── FailureDiagnosisAgent.java
│   │   └── model/FailureAnalysisResult.java
│   └── schema/                   # DDL 与数据库表元数据解析
│       └── SqlSchemaParser.java  # 基于 JSqlParser 解析 schema.sql 提取表结构与枚举
│
├── engine/                       # 四大测试执行引擎 (轻量高效)
│   ├── http/                     # Java 21 HttpClient + 虚拟线程并发执行器
│   ├── sql/                      # 动态 HikariCP + SQL 安全沙箱拦截器
│   ├── playwright/               # 微软官方 Playwright 浏览器自动化执行器
│   └── ddt/                      # 数据驱动测试引擎 (CSV/Excel 回放)
│
├── modules/                      # 八大业务模块 (纯单体 CRUD 与业务逻辑)
│   ├── workbench/                # 工作台与 EvalOps 效能统计
│   ├── project/                  # 项目配置与五维输入管理
│   ├── functional/               # 功能用例管理 (脑图/列表)
│   ├── api/                      # 接口管理与场景编排
│   ├── plan/                     # 测试计划与定时巡检
│   └── defect/                   # 缺陷管理与 Bug 单流转
│
└── common/                       # 通用返回体、全局异常拦截与工具类
```

---

## 八、 总结与开发指引

通过本方案的设计，**AI-Test-Platform** 具备了前所未有的工程深度与实用性：
* 对业务：它完整继承了 **MeterSphere V3** 的全部人机工程优点，提供极致顺畅的测试资产管理、自由编辑与多轮微调体验；
* 对技术：它深度吸收了 **TestPilot-AI** 的代码级 AST 洞察、影响面分析与智能诊断，将黑盒测试推向真正意义上的“全息代码白盒自动化”；
* 对开发者：通过输入本地路径，秒级读取 PRD、接口契约、前后端代码与 SQL 脚本，让 AI 在具备完备事实依据的前提下进行推导，彻底根除“幻觉”，实现“所测即所写，所错即能修”。

Codex 在实施开发时，请严格遵照本设计方案，将上述模块与机制逐一精准落地。
