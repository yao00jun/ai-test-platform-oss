# AI-Test-Platform

Java 21 与 Vue 3.5 持续测试工作台。需求文档、接口契约、固定源码和 DDL 可以共同生成测试资产；HTTP、SQL 与 Playwright 负责实际执行，运行快照、附件和缺陷发生记录保留证据。采用 Spring Boot 单体服务、MySQL 8.4 和本地受管文件，浏览器及 PDF 使用有界独立工作进程。

**AI 草稿由人裁决。** 全局反馈产生可选择的变更集；单条用例、单句 SQL、单个 UI 步骤的「🪄 局部 AI 调优」可连续多轮原地替换。每轮基于当前版本，保留目标 ID、位置与关联，版本冲突时保留人工修改，非目标资产不被重写。支持纯人工编辑、排序、删除及导入导出。

源码 `3333116e10a7` 的前端 60 项单元、后端 110 项单元、241 项真实集成，以及 64 项普通和 3 项认证浏览器流程全部通过。同一发行包通过启用认证的安装/备份恢复演练和保留原数据的实例升级。

可直接运行的发行包（内嵌前端的 JAR、脚本、迁移、SBOM 与校验清单）见 [GitHub Releases](https://github.com/yao00jun/ai-test-platform-oss/releases)。公司模型与实际业务环境需自行配置，协议 fixture 的通过不代表具体模型的生成质量。

## 八个模块

| 模块 | 主要能力 |
| --- | --- |
| 工作台 | 真实质量指标、可配置卡片、AI 质量报告、自动晨报、EvalOps |
| 项目管理 | 项目与环境、需求文档、源码/DDL 固定快照、模型与数据源配置 |
| 测试用例 | 列表、模块树、脑图、独立步骤、人工评审与多轮反馈 |
| 接口测试 | OpenAPI/Swagger、Postman、cURL、HAR，断言、提取、鉴权与契约 Diff |
| 场景自动化 | HTTP→SQL 场景链、变量作用域、数据集及 DDT、SQL 校验 |
| Playwright UI | 步骤与 DSL、语义定位器、frame/popup、附件、录制导入与独立 Java 导出 |
| 测试计划 | 混合执行、排期、不可变报告、人工项录入、定向回归 |
| 缺陷管理 | 手工录单、失败去重与发生历史、代码 RCA、修复建议、人工评价 |

八模块的 AI、人工 CRUD、模板与多格式往返均支持。导入先预检再原子提交。历史运行、评价及投递事实由系统记录，不通过可编辑资产导入文件伪造。

## 一键启动（源码目录）

在仓库根目录双击 `scripts\dev.cmd`，或在 PowerShell 7 中执行：

```powershell
.\scripts\dev.ps1            # 等同 up：MySQL → 配置 → JAR（缺失时自动打包）→ 浏览器内核 → 后端 → 打开 http://127.0.0.1:8080
.\scripts\dev.ps1 down       # 停止 Vite、后端与项目 MySQL
.\scripts\dev.ps1 restart    # 保留 MySQL，重启后端
.\scripts\dev.ps1 status     # 查看 MySQL / 后端 / 模型 / Vite 状态与日志位置
.\scripts\dev.ps1 logs       # 实时跟踪后端日志，Ctrl+C 退出
```

常用开关：`up -Dev` 额外启动 Vite 热更新并改为打开 5173；`up -Build` 先重新打包 JAR；`-NoBrowser` 不弹浏览器；`down -KeepMysql` 保留数据库；`logs -Errors` 看错误输出。`dev.cmd` 双击等于 `up`，也可 `dev.cmd down`。这个入口只面向源码目录，不进入发行包。

**首次启动会自动下载什么。** 需要预装的只有 Java 21、PowerShell 7.4+、Node.js 24+ 和 `pnpm@11.24.0`。其余工具由脚本按需从官方源下载到 Git 忽略的 `.tools/`：MySQL 8.4 压缩包约 270 MB（解压后 1.2 GB，仅在本机没有项目 MySQL 时下载）、Playwright Chromium 约 400 MB、Maven 3.9 约 9 MB。已装有 MySQL 8.4 的机器可用 `bootstrap-mysql.ps1 -MySqlHome '<安装目录>'` 直接复用；已有 Playwright 浏览器可把 `instance/config.json` 的 `paths.browsers` 指向它。使用发行包时不需要 Node.js 和 Maven，也不会下载 MySQL，需自备一个 MySQL 8.4 数据库。

## 脚本一览

`scripts/` 里共 12 个脚本。日常只需要 `dev.ps1`；它在内部调用运维脚本。

**日常开发**

| 脚本 | 作用 |
| --- | --- |
| `dev.ps1` / `dev.cmd` | 本机一键入口，子命令 `up`、`down`、`restart`、`status`、`logs`，见上文 |
| `bootstrap-mysql.ps1` | 下载官方 MySQL 8.4 到 `.tools/`，在 `.runtime/mysql/` 初始化并启动本项目专用实例（端口 3307），创建平台库、测试库和 `aitest` 账号。`dev.ps1 up` 会自动调用 |
| `maven.ps1` | 选定 JDK 21 后调用仓库内的 Maven Wrapper，所有 Maven 命令都经它执行 |

**构建与验证**

| 脚本 | 作用 |
| --- | --- |
| `build.ps1` | 完整发行构建：前端 lint、单元测试、打包，后端 `clean verify`，把前端嵌入 JAR，输出到 `artifacts/releases/` 并生成 ZIP、`source.zip`、`release.json` 与 `SHA256SUMS`。`-SkipTests` 只用于待验收包 |
| `verify.ps1` | 不打包，只跑全部检查：前端 lint、单元、构建，后端单元与集成测试。`-IncludeBrowser` 再以独立端口和测试库跑 Playwright 端到端流程 |
| `tests/release-smoke.mjs` | 对一个发行目录做完整发行演练：随机库、中文路径、登录、生成、执行、备份与恢复 |
| `tests/*-contract.ps1` | 运维脚本的契约测试：MySQL 客户端调用、运维脚本行为、平台登录。独立手动执行，结果记录在验收文档中 |

**运维（随发行包分发，面向单个实例）**

每个脚本都接受 `-InstanceDirectory`，用于操作默认 `instance/` 之外的实例目录。

| 脚本 | 作用 |
| --- | --- |
| `check.ps1` | 启动前体检：确认 Java 21、MySQL 8.4 可连接、存储目录可写、Chromium 是否安装、模型配置是否填写。`-TestModel` 向正在运行实例的实际模型发一次短请求 |
| `install-browsers.ps1` | 用 JAR 内匹配版本的 Playwright CLI 安装浏览器内核到配置的目录，默认 Chromium。`-Browsers chromium,firefox,webkit` 可多选，`-DryRun` 只预览 |
| `start.ps1` | 以隐藏子进程启动后端 JAR，口令和模型 key 经环境变量传入而非命令行；等待健康检查通过，记录 PID、日志路径与停止令牌到 `run/state.json`。首次启动由 Flyway 建表。端口被占用时直接报错，不结束他人进程 |
| `stop.ps1` | 通过本机停止令牌请求后端优雅关闭，最长等待 90 秒。`-Force` 仅结束身份核对通过的本实例进程树 |
| `backup.ps1` | 先优雅停止实例，再用 `mysqldump` 导出平台库、复制受管文件与主密钥，生成带 SHA-256 的 `manifest.json`。默认备份后自动重启，`-LeaveStopped` 保持停止。备份含解密密钥，需按数据库备份同等保护 |
| `restore.ps1` | 把一份完整备份恢复到一个新的空实例：校验哈希、路径、密钥和迁移版本后导入。不覆盖原实例；失败会写 `run/restore-incomplete.json` 并阻止启动 |
| `operations-common.ps1` | 上述脚本共用的函数库，不直接运行 |

## 本地运行

```powershell
.\scripts\up.ps1          # MySQL → 配置 → JAR（缺失时自动打包）→ 浏览器内核 → 后端 → 自动打开 http://127.0.0.1:8080
.\scripts\down.ps1        # 停止 Vite、后端与项目 MySQL
.\scripts\restart.ps1     # 保留 MySQL，重启后端（可加 -Build 先重新打包）
.\scripts\status.ps1      # 查看 MySQL / 后端 / 模型 / Vite 状态与日志位置
.\scripts\logs.ps1        # 实时跟踪后端日志；-Errors 看错误输出，-Vite 看前端日志
```

`up.ps1 -Dev` 额外启动 Vite 热更新并改为打开 5173；`-NoBrowser` 不弹浏览器；`-Build` 强制重新打包。每个脚本都有同名 `.cmd`，可直接双击。这组脚本只面向源码目录的本机开发体验，不进入发行包；发行包仍使用下面的运维脚本。

## 脚本一览

`scripts/` 里的脚本分三组。一键脚本在内部调用运维脚本，日常只需记住第一组。

**一键脚本（源码目录专用，不进发行包）**

| 脚本 | 作用 |
| --- | --- |
| `up.ps1` | 按顺序拉起项目 MySQL、生成实例配置、缺 JAR 时打包、缺 Chromium 时安装、启动后端并打开浏览器；重复执行会跳过已在运行的部分 |
| `down.ps1` | 依次停止 Vite、后端、项目 MySQL。`-KeepMysql` 保留数据库，`-Force` 在优雅停止失败时强制结束后端 |
| `restart.ps1` | 保留 MySQL，重启后端；`-Build` 先重新打包，`-Dev` 同时启动 Vite |
| `status.ps1` | 显示 MySQL、后端、模型配置、Vite 的运行状态、PID、地址和日志路径 |
| `logs.ps1` | 实时跟踪后端日志。`-Errors` 看错误输出，`-Vite` 看前端日志 |

**运维脚本（随发行包分发，面向单个实例）**

每个脚本都接受 `-InstanceDirectory`，用于操作默认 `instance/` 之外的实例目录。

| 脚本 | 作用 |
| --- | --- |
| `check.ps1` | 启动前体检：确认 Java 21、MySQL 8.4 可连接、存储目录可写、Chromium 是否安装、模型配置是否填写。`-TestModel` 向正在运行实例的实际模型发一次短请求，验证模型连通 |
| `install-browsers.ps1` | 用 JAR 内匹配版本的 Playwright CLI 安装浏览器内核到配置的目录，默认 Chromium。`-Browsers chromium,firefox,webkit` 可多选，`-DryRun` 只预览安装位置 |
| `start.ps1` | 以隐藏子进程启动后端 JAR，把数据库口令和模型 key 通过环境变量传入而非命令行；等待健康检查通过，记录 PID、日志路径与停止令牌到 `run/state.json`。首次启动由 Flyway 建表。端口被占用时直接报错，不结束他人进程 |
| `stop.ps1` | 通过本机停止令牌请求后端优雅关闭，最长等待 90 秒，让执行队列、连接池和浏览器工作进程收尾。`-Force` 仅结束身份核对通过的本实例进程树 |
| `backup.ps1` | 先优雅停止实例，再用 `mysqldump` 导出平台库、复制受管文件与主密钥，生成带 SHA-256 的 `manifest.json`。默认备份后自动重启，`-LeaveStopped` 保持停止。备份含解密密钥，需按数据库备份同等保护 |
| `restore.ps1` | 把一份完整备份恢复到一个新的空实例：校验哈希、路径、密钥和迁移版本后导入数据库和文件。不覆盖原实例；失败会写 `run/restore-incomplete.json` 并阻止启动，需换新目标重来 |
| `operations-common.ps1` | 上述脚本共用的函数库（读配置、找 Java、调用 MySQL 客户端、识别受管进程），不直接运行 |

**开发与构建脚本（源码目录）**

| 脚本 | 作用 |
| --- | --- |
| `bootstrap-mysql.ps1` | 下载官方 MySQL 8.4 压缩包到 `.tools/`，在 `.runtime/mysql/` 初始化并启动本项目专用实例（端口 3307），创建平台库、测试库和 `aitest` 账号。凭据写入 Git 忽略的 `connection.json` |
| `maven.ps1` | 选定 JDK 21 后调用仓库内的 Maven Wrapper，所有 Maven 命令都经它执行 |
| `dev.ps1` | 打包后端、安装前端依赖、启动受管后端，然后在当前终端前台运行 Vite。`-NoBuild` 复用已有 JAR。已被 `up.ps1 -Dev` 覆盖，保留以兼容旧文档 |
| `build.ps1` | 完整发行构建：前端 lint、单元测试、打包，后端 `clean verify`，把前端嵌入 JAR，输出到 `artifacts/releases/` 并生成 ZIP、`source.zip`、`release.json` 与 `SHA256SUMS`。`-SkipTests` 只用于待验收包 |
| `verify.ps1` | 不打包，只跑全部检查：前端 lint、单元、构建，后端单元与集成测试。`-IncludeBrowser` 再以独立端口和测试库跑 Playwright 端到端流程 |
| `run-backend.ps1` / `run-compiled.ps1` | 不打 JAR、直接以 Maven `spring-boot:run` 或已编译 class 前台运行后端，供手动调试；一键脚本和验证流程都不依赖它们 |
| `tests/release-smoke.mjs` | 对一个发行目录做完整发行演练：随机库、中文路径、登录、生成、执行、备份与恢复 |
| `tests/*-contract.ps1` | 运维脚本的契约测试：MySQL 客户端调用、运维脚本行为、平台登录。独立手动执行，结果记录在验收文档中 |
| `dev-common.ps1` | 一键脚本共用的函数库，不直接运行 |

## 本地运行

Windows 使用 PowerShell 7.4+、Java 21、MySQL 8.4。从源码构建还需要 Node.js 24+ 与 `pnpm@11.24.0`。发行 JAR 已包含前端，运行时不需要 Node.js 或 Maven。

发行目录先复制 `config.example.json` 为 `instance/config.json`，配置一个空的专用 MySQL 数据库及独立账号。然后执行：

```powershell
.\scripts\check.ps1
.\scripts\install-browsers.ps1
.\scripts\start.ps1
# 浏览器打开 http://127.0.0.1:8080
.\scripts\stop.ps1
```

`start.ps1` 默认监听本机。首次启动由 Flyway 应用 V1–V23 表结构与索引；不要手动执行历史 SQL 或修改已应用的迁移。日志、进程身份与配置均归属于所选实例。中文和含空格的路径使用 PowerShell 引号。

远程监听需在实例配置中启用 `security.enabled` 并设置工作空间账号和密码。会话过期时保留当前标签页的未保存编辑，重新登录后可继续；主动退出会提示清理这些草稿。配置字段见 [deploy/config.example.json](deploy/config.example.json) 与下文「模型、输入与反馈」。

源码开发可以使用已有 MySQL，或通过 `scripts/bootstrap-mysql.ps1` 创建本项目专用的本地 8.4 实例；本地凭据保存在 Git 忽略的 `.runtime/mysql/`。构建和联调命令：

```powershell
.\scripts\build.ps1
.\scripts\dev.ps1
# 完整后端与前端检查；浏览器检查使用独立测试库和模型协议 fixture
.\scripts\verify.ps1 -IncludeBrowser
```

`-SkipTests` 仅用于制作待验收包，产物会明确记录跳过状态。完整构建移除该参数。完整测试需要独立 MySQL 测试库及已安装 Chromium。

## 模型、输入与反馈

在界面「模型设置」或实例配置中填写公司的 `baseUrl`、`apiKey`、`modelName`；只连接 OpenAI 兼容的在线服务，无需本地 Ollama。配置文件示例字段见 [deploy/config.example.json](deploy/config.example.json)。未配置模型时仍可手工维护、导入和执行资产；AI 操作会留下明确的缺配置状态。

需求支持本地路径、拖拽上传或粘贴。绝对路径指**后端运行机器**上的文件；远程浏览器使用上传。源码与 DDL 先导入成固定版本，随后用于影响分析、生成、反馈和诊断。源文件改变不会改写历史证据。缺少数据库、页面或可信源码证据时，相关阶段显示配置缺口，支持补充后恢复。

全局反馈先预览新增/修改/删除和关联依赖，再选择性原子采纳。局部调优仅允许目标字段变更；迟到响应、取消、重复提交、并发人工修改和撤销都有持久化版本保护。SQL 或 UI 改动涉及依赖不兼容时，要求使用包含关联项的全局变更集。

静态源码线索与模型建议仍需实际执行验证；反射、运行时路由、动态 SQL 和未知语法会保留未解析信息或被校验拒绝，不承诺绝对准确率。代码 RCA 的补丁是供人工审阅的建议，不自动修改业务源码。

## 当前锁定版本

| 范围 | 实际采用 |
| --- | --- |
| 后端 | Java 21.0.11、Spring Boot 4.1.1、Spring AI 2.0.1 |
| 执行与存储 | MySQL 8.4.10、Playwright Java 1.62.0、POI 5.5.1、PDFBox 3.0.8、Flexmark 0.64.8 |
| 源码与 SQL | JavaParser 3.28.2、JSqlParser 5.4 |
| 前端 | Vue 3.5.42、Vite 8.3.0、TypeScript 6.0.3、Arco 2.58.0、Monaco 0.56.0 |

完整依赖以 [backend/pom.xml](backend/pom.xml) 和 [frontend/pnpm-lock.yaml](frontend/pnpm-lock.yaml) 为准；每次发行的 CycloneDX SBOM 与依赖树清单作为附件挂在 [GitHub Releases](https://github.com/yao00jun/ai-test-platform-oss/releases)。

## 许可与来源

- 本项目以 GPL-3.0 发布，见 [LICENSE](LICENSE)。
- 移植自 MeterSphere 与 TestPilot-AI 的代码、其版权与许可说明见 [NOTICE.md](NOTICE.md)、[licenses/](licenses/) 与 [frontend/THIRD_PARTY_NOTICES.md](frontend/THIRD_PARTY_NOTICES.md)。
- 发行包、SBOM、依赖清单与验收附件通过 [GitHub Releases](https://github.com/yao00jun/ai-test-platform-oss/releases) 分发。

发行包包含应用 JAR、配置示例、脚本、迁移、文档、第三方许可、对应源码及 SHA-256 清单。实例备份同时保存数据库、受管文件与有效主密钥；便携资产导出会脱敏并解除项目专属绑定，不能替代完整备份。
