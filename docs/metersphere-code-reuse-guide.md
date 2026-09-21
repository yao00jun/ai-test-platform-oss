# MeterSphere 源码核心复用与借鉴指南

在自研 **AI-Test-Platform** 过程中，本地已有项目 `e:\WorkSpace\metersphere` 蕴含了大量工业级经过验证的成熟代码。我们可以**“去其糟粕（去除多租户与复杂微服务依赖），取其精华（提炼纯工具类与Prompt工程）”**，直接将以下代码移植到新项目中。

---

## 一、 可直接全量/轻量移植的 Java 工具类

### 1. `MdUtil.java`：AI Markdown 格式用例解析器
* **原代码位置**：
  [backend/services/case-management/src/main/java/io/metersphere/functional/utils/MdUtil.java](https://github.com/metersphere/metersphere/tree/main/backend/services/case-management/src/main/java/io/metersphere/functional/utils/MdUtil.java)
* **核心价值**：
  大模型生成的用例通常是 Markdown 格式（带有 `## 标题`，`### 前置条件`，`| 步骤 | 预期 |`）。`MdUtil` 基于 `com.vladsch.flexmark` 库将 Markdown 语法树自动解析为 Java DTO 对象（用例名、前置条件、步骤列表），使得 AI 输出能够 100% 自动化入库。
* **所需依赖（pom.xml）**：
  ```xml
  <dependency>
      <groupId>com.vladsch.flexmark</groupId>
      <artifactId>flexmark-all</artifactId>
      <version>0.64.8</version>
  </dependency>
  ```
* **精简改造建议**：
  * 去掉其原代码中的 `io.metersphere.sdk.exception.MSException`，替换为新项目的标准 `RuntimeException`。
  * 将 `JSON.toJSONString(...)` 替换为新项目所用的 Jackson 或 Fastjson2。

---

### 2. `TextCleaner.java`：大模型回答文本清洗规整器
* **原代码位置**：
  [backend/framework/ai-engine/src/main/java/io/metersphere/ai/engine/utils/TextCleaner.java](https://github.com/metersphere/metersphere/tree/main/backend/framework/ai-engine/src/main/java/io/metersphere/ai/engine/utils/TextCleaner.java)
* **核心价值**：
  大模型生成的文本常常夹杂不可见控制字符、多余空白行、HTML标签或未闭合定界符。该工具内置预编译正则，提供：
  * `cleanMdTitle(String content)`：自动补全缺失的 Markdown 标题空格（`##标题` 转为 `## 标题`）。
  * `unifySymbols(String input)`：全角中文标点统一转半角英文标点（如 `【】` 转 `[]`，`“”` 转 `""`），防止 JSON 解析失败。
  * `normalizeWhitespace(String input)`：规整多余空白字符。
* **所需依赖**：仅需 `org.apache.commons:commons-lang3`。
* **直接复用**：该类无任何项目业务耦合，可 100% 原样复制到新工程。

---

### 3. `KeywordDeduplication.java` 与 `TextRankSummarizer.java`
* **原代码位置**：
  [backend/framework/ai-engine/src/main/java/io/metersphere/ai/engine/utils](https://github.com/metersphere/metersphere/tree/main/backend/framework/ai-engine/src/main/java/io/metersphere/ai/engine/utils)
* **核心价值**：
  用于在将大段需求文档丢给大模型前进行关键词去重与快速摘要提取，有效降低消耗的 Token 数量，提高响应速度。

---

## 二、 提示词工程（Prompt Engineering）直接借鉴

MeterSphere 团队调优过的提示词模板经过了大量场景检验，可以直接从 MeterSphere 拷贝并作为新项目的默认模板：

1. **功能用例生成模板**：
   * 对应路径：`e:\WorkSpace\metersphere\backend\services\case-management\src\main\resources\prompts\generate_step.st`
   * 借鉴点：强制使用 `featureCaseStart` 和 `featureCaseEnd` 定界符；强制表格输出步骤与预期；动态注入测试设计方法（等价类、边界值、场景法）。
2. **接口测试用例生成模板**：
   * 对应逻辑：`e:\WorkSpace\metersphere\backend\services\api-test\src\main\java\io\metersphere\api\utils\ApiCasePromptTemplateCache.java`
   * 借鉴点：自动提取 API 定义中的 Headers、Query、Body 结构填入 Prompt，让模型针对字段类型进行正逆向变异。

---

## 三、 前端组件与交互设计借鉴

### 1. 全局 AI 抽屉交互 (`ms-ai-drawer`)
* **原代码位置**：
  [frontend/src/components/business/ms-ai-drawer](https://github.com/metersphere/metersphere/tree/main/frontend/src/components/business/ms-ai-drawer)
* **借鉴点**：
  * 左侧历史会话列表，右侧多轮聊天对话。
  * 底部提供“同步到当前用例库”按钮。
  * 支持接收后端 Server-Sent Events (SSE) 流式打字机效果。

### 2. 侧边栏菜单布局 (`ms-menu`)
* **原代码位置**：
  [frontend/src/components/business/ms-menu/index.vue](https://github.com/metersphere/metersphere/tree/main/frontend/src/components/business/ms-menu/index.vue)
* **借鉴点**：
  * Arco Design 侧边栏折叠与响应式逻辑。
  * 底部常驻“系统设置”与“个人中心”的布局定位。
