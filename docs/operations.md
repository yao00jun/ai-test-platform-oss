# 运行、验证与恢复手册（开发者）

本手册对应仓库中的实际脚本：Windows 用 `scripts/aitest.ps1`，Linux 用 `scripts/aitest.sh`，两者命令相同（Linux 选项写成 `--小写-连字符`）。根目录的 `启动.cmd`、`停止.cmd`、`查看状态.cmd`、`查看日志.cmd`、`备份.cmd`、`打包发行.cmd` 只是双击壳，分别等于 `up`、`down`、`status`、`logs`、`backup`、`build`。面向非开发者的分步教程见 [README](../README.md)。各轮演练的结果、失败与修复记录在源码仓库的验收记录；未执行的命令不视作验收通过。

## 环境与目录

运行需要 Windows（PowerShell 7.4+）或 Linux（bash、curl、tar、xz、python3）、Java 21、MySQL 8.4。浏览器执行和 PDF 需要与 JAR 所含 Playwright Java 1.62.0 匹配的浏览器。构建额外需要 Node.js 24+，pnpm 由 corepack 按 `frontend/package.json` 钉住的版本自动提供，Maven Wrapper 锁定 3.9.16。缺少的 Java、MySQL、Node.js 和浏览器内核由脚本从官方源下载到 `.tools/` 并校验 SHA-256；已安装的 JDK 可在配置中指定 `javaHome`，或设置 `AI_TEST_JAVA_HOME`。

发行包目录包含 `app.jar`、`config.example.json`、双击用的 `*.cmd`、`scripts/aitest.ps1`、`scripts/aitest.sh`、`docs/`、`database/migration/`、`licenses/`、`source.zip`、`release.json` 与 `SHA256SUMS`。`source.zip` 包含对应源码及构建所需文件，不包含 `.runtime`、用户数据库、密钥或 `node_modules`。

实例目录与应用包分离。默认是包内 `instance/`，也可以通过每个命令的 `-InstanceDirectory` 指定，例如 `E:\测试平台\实例 一`。其中 `config.json` 是用户配置，`run/` 保存进程身份和临时运维状态，`logs/` 保存启动与 MySQL 客户端日志，默认 `data/` 保存受管文件与主密钥。相对存储、浏览器和本地读取目录均相对于实例目录解析。

## 首次启动

最简单的方式是双击 `启动.cmd`（或 `scripts/aitest.sh up`）：发行包里没有 `instance/config.json` 时，它会自动下载并初始化一个只监听本机 3307 的 MySQL 8.4，生成配置并启动。要接公司数据库时，先由数据库管理员创建一个空的专用 MySQL 8.4 库及仅访问该库的账号，再手工复制配置：

```powershell
New-Item -ItemType Directory -Path 'E:\测试平台\实例 一'
Copy-Item -LiteralPath '.\config.example.json' -Destination 'E:\测试平台\实例 一\config.json'
```

配置字段如下：

| 字段 | 含义 |
| --- | --- |
| `javaHome`、`mysqlHome` | JDK 21 和 MySQL 8.4 客户端安装目录；为空时从本机路径查找 |
| `bind`、`port` | 默认 127.0.0.1:8080。也可用 `::1`；显式开放网卡时使用 `0.0.0.0` 或 `::`，以保留本机停止通道 |
| `security.enabled` | 默认 false，仅允许回环监听。远程监听必须设为 true |
| `security.username/password` | 启用时必须配置单账号凭据，无默认密码；账号为 1–64 个字母、数字或 `_.@-`，密码至少 12 字符且不超过 72 个 UTF-8 字节 |
| `security.sessionMinutes/secureCookie` | 空闲失效分钟数 1–1440，默认 30；经 HTTPS 访问时设置 Secure Cookie，默认 false |
| `database.url/username/password` | 一个明确库名的 JDBC 地址及分离的凭据；地址中不嵌入口令 |
| `model.baseUrl/apiKey/modelName` | 可为空；手工流程正常使用，AI 调用显示缺配置状态 |
| `paths.storage/browsers/localFileRoots` | 存储、浏览器及允许本地读取的根目录；根目录列表为空时保留本地个人模式的读取行为 |
| `runtime.heapMiB` | 主应用堆上限，默认 1024 MiB；不等于包含 Chromium 的总进程内存 |
| `runtime.concurrency/browserWorkers/businessConnections` | 默认 8 个执行槽、2 个浏览器工作进程、32 个业务数据库连接总上限 |
| `mysqlSslMode/mysqlSslCa` | 可选的 MySQL 客户端 TLS 设置，用于检查、备份和恢复；JDBC 的 TLS 参数单独写在地址中 |

平台用于个人或受控团队环境，支持一个配置的工作空间账号，不设置多租户权限树。已有缺少 `security` 的本机配置按免登录模式处理。启用登录后，密码只通过子进程环境传递，应用在内存中以 BCrypt 校验；配置文件和备份应使用与数据库口令相同的文件访问权限。经 HTTPS 入口访问时将 `secureCookie` 设为 true。修改配置后停止并重启实例，启动脚本不会静默替换正在运行的进程。

直接运行 JAR 时，对应环境变量为 `AI_TEST_AUTH_ENABLED`、`AI_TEST_AUTH_USERNAME`、`AI_TEST_AUTH_PASSWORD`、`AI_TEST_SESSION_TIMEOUT`（如 `30m`）、`AI_TEST_SECURE_COOKIE`。前端登录不会把密码或会话令牌写入 localStorage。会话失效后，本标签页保留未提交的编辑；重新登录后可继续。主动退出会提示清理未保存编辑。更换密码或重启服务后，已有会话不能继续使用。

```powershell
.\scripts\aitest.ps1 check -InstanceDirectory 'E:\测试平台\实例 一'
.\scripts\aitest.ps1 install-browsers -InstanceDirectory 'E:\测试平台\实例 一'
.\scripts\aitest.ps1 start -InstanceDirectory 'E:\测试平台\实例 一'
.\scripts\aitest.ps1 stop -InstanceDirectory 'E:\测试平台\实例 一'
```

首次启动由 Flyway 初始化数据库，健康检查最多等待 240 秒。已应用迁移不可修改，不能通过 Flyway clean 或重新执行旧 SQL 修复运行库。前端已内嵌 JAR，访问同一端口即可，八个业务路由支持刷新。

浏览器可选择 `-Browsers chromium,firefox,webkit`；只预览安装位置时用 `-DryRun`。脚本直接调用 JAR 内匹配版本的官方 Playwright CLI，不需要在运行机上安装 Maven 或 Node。离线运行可预先安装并保留同版本的浏览器目录，再在配置中指向它。

## 公司模型配置

界面「模型设置」中保存的配置优先于文件配置，并加密保存在数据库中。填写服务 base URL、API key 与模型名称后测试连接；未带路径的地址补 `/v1`，已有路径原样保留，不能填写带 query/fragment 的 URL。切换配置不会在前端持久存储 API key。

```powershell
.\scripts\aitest.ps1 check -InstanceDirectory 'E:\测试平台\实例 一' -TestModel
```

该命令测试正在运行实例的实际有效模型配置，会向模型发起一次简短请求；启用平台认证时，它只连接受管实例的回环地址和端口，使用配置账号临时登录并在调用后退出，使用同一套会话和 CSRF 校验。默认 `check` 不调用模型，不发送群通知。公司模型未提供时，验收只记录本地协议 fixture 和人工流程，不假报公司联调成功。

## 开发、构建与验证

在源码根目录中：

```powershell
.\scripts\aitest.ps1 mysql
.\scripts\aitest.ps1 maven -version
.\scripts\aitest.ps1 up
```

本地 MySQL 凭据保存在 Git 忽略的 `.runtime/mysql/`。`up` 是源码目录的一键入口：依次确认 Java 21、拉起项目 MySQL、生成实例配置、缺 JAR 时打包、缺 Chromium 时安装、启动受管后端并打开浏览器；`up -Dev` 额外以后台进程启动 Vite（5173，`/api` 代理到后端）；`down` 停止 Vite、后端与项目 MySQL；`restart`、`status`、`logs` 分别重启、查看状态、跟踪日志。已有手动启动服务占用端口时，脚本会拒绝冲突，不结束其他进程。`up -Build` 强制重新打包。

Maven Wrapper 的官方脚本和分发 SHA-256 已提交。`aitest.ps1 maven` 选择 Java 21，再调用 Wrapper，其余参数原样传给 Maven；普通测试由 Surefire 执行，集成测试由 Failsafe 在 `verify` 阶段执行。完整测试需要独立 MySQL 测试库及已安装 Chromium，不使用 H2，也不静默跳过。集成测试共用一个持久的 `ai_test_platform_test` 库，`verify` 与 `build` 在跑后端测试前会先执行 `reset-test-db` 重建它（以及 `ai_test_business_test`）；直接用 `aitest.ps1 maven verify` 跑集成测试时请先手动执行 `aitest.ps1 reset-test-db`，否则残留数据会让 `LargeAssetScopeIT` 等测试慢几十倍，残留的晨报排期还会让 AI 流水线测试报模型服务 503。`aitest.ps1 mysql` 每次启动都会重新生成 `my.ini`，并为本地实例设置 1 GB InnoDB 缓冲池与每秒刷新一次重做日志；已在运行的旧实例执行一次 `down` 再 `up` 即可套用。数据目录默认在 `.runtime/mysql/data`；源码目录在机械硬盘上时，用 `aitest.ps1 mysql -DataDirectory <固态盘目录>` 首次初始化（或停止后迁移数据目录再指定一次），集成测试每建一个新库要执行 65 张 `CREATE TABLE`，在机械盘上约 50～100 秒，在固态盘上只需几秒。集成测试默认由 Failsafe 分 3 个 JVM 并行执行（`backend/pom.xml` 的 `aitest.it.forks`），第 N 个 JVM 使用 `ai_test_platform_test_N` / `ai_test_business_test_N`，`reset-test-db` 会一并重建；连接外部测试库时传 `-Daitest.it.forks=1`。

```powershell
.\scripts\aitest.ps1 verify
.\scripts\aitest.ps1 verify -Full
.\scripts\aitest.ps1 verify -IncludeBrowser
.\scripts\aitest.ps1 build
```

`verify -IncludeBrowser` 从本次编译类建立不可变快照，在 8081/8082/5174 启动测试后端、协议 fixture 与测试前端。先运行基础 CRUD、文件交换和 AI/执行流程，再以独立认证配置运行登录、过期草稿恢复和手机流程；两组串行，均不修改开发实例的公司模型设置。相关端口必须空闲。各 Maven 操作和 `backend/target` 写入串行执行。仅重跑认证浏览器时使用 `pnpm --dir frontend test:e2e:auth`，需先存在本次构建的后端快照。

`build` 默认产出两个包：联网版 `<发行名>.zip` 和离线完整版 `<发行名>-offline-windows.zip`（多出 `.tools/` 目录：MySQL 压缩包、`playwright-<版本>-chromium-win64.zip`、`vc_redist.x64.exe` 和记录指纹的 `manifest.json`；`-SkipOffline` 跳过）。给已有发行目录补做离线包用 `aitest.ps1 offline-bundle -ReleaseDirectory <目录>`。离线包里随附的第三方程序及其许可见 `NOTICE.md`；MySQL 的对应源码放在公开库 Releases 的 `third-party-sources` 发布项下。`build` 执行前端 lint、单元测试和构建，再用 `distribution,nightly` profile 执行后端 `clean verify`（含全部 `slow` 集成测试；日常 `verify` 默认跳过它们），将前端放入 JAR 并生成发行目录、ZIP 和逐文件校验清单。`-SkipTests` 用于调试待验收包，`release.json` 明确记录该状态；`-SkipInstall` 仅用于本地已按锁文件安装依赖时。需要公司 Maven 镜像配置时传入 `-MavenSettings 'E:\配置\settings.xml'`，凭据不提交到仓库。

当前验收发行另附依赖树、CycloneDX 1.6 SBOM 及最终验收 JSON，入口见源码仓库的交付记录。这些是完整构建完成后的旁置附件，绑定原始 JAR/ZIP 与锁文件哈希；不修改已经验证的包，也不表示每次单独执行 `build` 都会自动生成这些验收附件。

完整发行演练脚本是：

```powershell
node .\scripts\tests\release-smoke.mjs 'E:\发行包目录'
```

它使用 bootstrap 的独立随机库、中文/空格路径、真实 JAR、模型协议服务和 Chromium，在启用平台登录的配置下验证会话、CSRF、模板、生成、两轮局部反馈、源码快照、HTTP/SQL/UI 计划、报告、运维模型检查、退出、备份与恢复。恢复实例需重新登录。结果在 `.runtime/release-acceptance/`；这里只创建和删除本次测试自己的库，没有真实公司调用或群消息。

## 工具查找与离线模式

`up` 按固定顺序找每样工具：`config.json` 里填的 `javaHome` / `mysqlHome` / `paths.browsers` → 环境变量 `AI_TEST_JAVA_HOME`、`JAVA_HOME`、`MYSQL_HOME`、`PLAYWRIGHT_BROWSERS_PATH`、`AI_TEST_NODE_HOME` → `.tools/` 自带（目录或压缩包）→ 本机已安装（PATH、`C:\Program Files\Java` 等厂商目录、`C:\Program Files\MySQL\MySQL Server 8.4*`、Playwright 默认目录 `%LOCALAPPDATA%\ms-playwright`）→ 联网下载。找到的路径写回 `instance/config.json`，`status` 底部显示每样工具的来源。

运行接受 Java 21 及更高版本（21 优先，其他版本给出提示）；`maven`、`verify`、`build` 只用 21。MySQL 只接受 8.4；找到本机安装的 8.4 时只借用其程序文件，在项目自己的数据目录里起独立实例（端口 3307）。浏览器内核以 Playwright 自己报告的目录清单为准（`install --dry-run`），版本号不再写死在脚本里，而是从 `backend/pom.xml` 或 `app.jar` 读取。

`config.json` 里的数据库指向本机且端口等于项目 MySQL 端口时启动自带实例；否则视为外部数据库，不启动自带实例（源码目录同样适用）。

Windows 版 MySQL 的服务端和客户端按系统 ANSI 代码页解析文件路径。项目路径含代码页无法表示的字符时（例如英文系统上的中文目录），`my.ini`、`admin.cnf`、日志和默认数据目录整体放到 `%ProgramData%i-test-platform\mysql`（记录在 `connection.json` 的 `runtime`），备份/检查/恢复用的临时客户端配置放到 `%ProgramData%i-test-platform\client`；实例目录、存储目录和 SQL 内容不受影响。

任何命令加 `-Offline`（或设置环境变量 `AI_TEST_OFFLINE=1`）进入离线模式：缺什么直接报错并说明把文件放到哪里，不尝试下载；联网下载的连接超时缩短为 20 秒。自带 MySQL 需要微软 VC++ 2015-2022 x64 运行库，缺失时从 `.tools/vc_redist.x64.exe` 或微软官网安装（校验微软数字签名，需要管理员权限）。

## 私有库与公开库

代码在私有库 `yao00jun/ai-test-platform` 和公开库 `yao00jun/ai-test-platform-oss` 之间保持一致；公开库不带 `docs/acceptance/`、`docs/analysis/`、`docs/design-archive/`、`docs/superpowers/`、`docs/roadmap.md`、`docs/codex-implementation-prompt.md`、`docs/session-handoff-*.md`、`.superpowers/` 和两个模板文件。同步命令：

```powershell
.\scriptsitest.ps1 sync-public -PublicDirectory 'E:\WorkSpacei-test-platform-public'            # 私有 → 公开，随后到公开库提交推送
.\scriptsitest.ps1 sync-public -PublicDirectory 'E:\WorkSpacei-test-platform-public' -Reverse   # 公开库合并了外部 PR 后，公开 → 私有
```

公开库 `main` 受保护：外部改动只能经 Pull Request 合并，且 GitHub Actions 的 `verify` 工作流（`.github/workflows/verify.yml`，Windows runner，等同本地 `verify -Forks 2` 加三项脚本契约测试）必须通过；仓库管理员可直接推送同步提交。私有库里该工作流只在手动触发时运行。

## 停止与日志

`start` 使用隐藏子进程，状态绑定 PID 与开始时间。口令、模型 key 和主密钥不在 Java 命令行中；父终端的 Spring/JVM/服务配置变量不会覆盖实例设置。实例停止令牌随机生成，仅在本机连接时可用；令牌缺失、错误、过短或来自非本机连接时不停止服务。

`stop` 请求优雅停止并等待最长 90 秒。它让持久队列、连接池和浏览器执行器关闭；`-Force` 仅结束身份校验通过的本实例进程树，正在运行的测试会在恢复后标为中断，不自动重放外部写入。

`run/state.json` 中保留本实例的日志路径；优先查看 `logs/application-*.log` 与 `.err.log`。健康入口为 `/actuator/health`。不要把包含配置、密钥、备份或完整日志的实例目录上传到公开问题记录。

## 一致备份

```powershell
.\scripts\aitest.ps1 backup -InstanceDirectory 'E:\测试平台\实例 一' -DestinationDirectory 'E:\测试备份'
```

运行中的自有实例先优雅停止，随后使用 MySQL 8.4 `mysqldump` 保存平台数据库，再复制受管文件、源码证据与实际有效的 32 字节主密钥。默认备份结束后恢复启动，增加 `-LeaveStopped` 保持停止。每个实例应独占平台数据库和受管目录，备份期间不能由另一个应用进程向它们写入。

备份目录中有 `database.sql`、`storage/`、`configuration.json` 和最后生成的 `manifest.json`。清单包含文件长度、SHA-256、MySQL/迁移版本和主密钥摘要。临时 MySQL 凭据文件在调用后删除，密码不作为命令行参数。临时浏览器工作目录不进入包。

环境变量 `AI_TEST_MASTER_KEY` 若作为该实例的主密钥，备份必须在同一有效变量下运行；脚本与上次启动时的密钥摘要比较，拒绝生成不匹配备份。备份包含解密密钥与原配置口令，应按数据库备份同等保护。校验和证明完整性，不证明陌生备份的来源。

## 恢复与升级回退

先创建新的空目标库和空存储目录，为新实例写入目标连接、端口与路径配置。不要覆盖原实例。

```powershell
.\scripts\aitest.ps1 restore -BackupDirectory 'E:\测试备份\backup-具体目录' -InstanceDirectory 'E:\测试平台\恢复实例'
.\scripts\aitest.ps1 start -InstanceDirectory 'E:\测试平台\恢复实例'
```

恢复校验文件长度/哈希、路径归属、链接、重复路径、密钥与数据库版本。它保留目标配置，把原配置另存 `restored-configuration-*.json` 供参考。恢复后原 ID、修订、运行、附件、评价、模型与业务数据源密文保留；外部业务服务地址也保留原值，按需要由人工调整。

导入过程中遇到磁盘或 SQL 失败，`run/restore-incomplete.json` 保留失败阶段并阻止 `start`。MySQL DDL 不能整体回滚，脚本不会自动删除部分表或原实例。检查日志、修复原因后使用新的空目标重新恢复。只有完整备份能用于此流程；资产 JSON/XLSX 导出不能作为恢复输入。

升级采用新包加原实例配置，先备份再启动，由 Flyway 应用新增迁移。回退需要将升级前完整备份恢复到新库/新目录，并使用对应旧包；不对已升级数据库直接切回旧代码。

V23 为 AI 会话消息增加任务、角色和状态联合索引，避免采纳或拒绝草稿时扫描、锁住其他会话。该迁移不修改资产或会话内容；升级核验应保持 V1–V22 的迁移历史与业务表数据，并确认只追加 V23 成功记录和新索引。

V24 增加资产近期排序与同类同父位置查询索引，不修改已有资产内容。升级时保留 V1–V23 校验和，确认新增 V24 成功记录。大库建索引需要额外磁盘空间与 I/O，应在备份后安排升级窗口。

## 界面主题

登录卡片或页面右上角的太阳/月亮按钮切换浅色、暗色主题。无浏览器偏好时跟随系统；手动选择保存在 `localStorage` 的 `ai-test-platform:theme`，优先于系统并同步到同源的其他标签页。配色不写入项目、资产或实例配置。存储不可用时仍可切换，但刷新后不保证保留选择。

首屏脚本在应用启动前应用配色；Arco 弹层与 Monaco 同步，切换不重建编辑器或重置未保存草稿。该版本的按钮提供两种明确配色；恢复自动跟随系统可在浏览器开发工具中只删除上述主题键并刷新，不需要清空其他站点数据。

## 性能复测

查询与批量写入测量使用 `PerformanceMeasurementIT`：3 轮各 1,000 条导入、20 次工作台统计请求、12 条 SSE 空闲订阅及终态投递，以及临时文件顺序写和 4 KiB 强制落盘探针。该测试要求本项目 bootstrap MySQL 与 Performance Schema，自动创建并清理随机验收库，输出不包含数据库口令。

```powershell
.\scripts\aitest.ps1 maven -B -ntp test-compile failsafe:integration-test failsafe:verify `
  '-Dit.test=PerformanceMeasurementIT' `
  '-Daitest.measure.output=<工作区>/.runtime/performance.json'
```

运行时避免并行构建、备份或其他负载。比较相同 JVM、连接池、数据规模和磁盘上的查询次数与延迟；使用报告中的 `stableConnections` 确认计数窗口内连接集合稳定。应用会话计数包含其后台调度器，`Handler_commit`/`SQL_commit` 不能直接解释为导入的 fsync 次数。磁盘探针包含操作系统和控制器缓存影响，不能作为断电持久性证明或生产 SLA。

平台继续使用 MySQL 和本地受管存储。遇到机械盘小块同步写瓶颈，先减少重复查询和写入往返；需要迁移 SSD 时，将数据库、受管存储与有效密钥纳入完整备份恢复流程，单独验证新磁盘。本轮优化不自动迁移目录，也不降低数据库落盘策略。

## 常见故障

| 现象 | 处理 |
| --- | --- |
| 端口占用 | 查看已有实例状态；停止明确属于自己的服务或改端口 |
| 启动迁移校验失败 | 核对应用与迁移版本，保留现场，从备份向新目标恢复 |
| AI 操作失败 | 检查实际有效模型配置、兼容端点与任务历史；原资产不变 |
| SQL/UI 阶段阻塞 | 补齐数据源、DDL、页面或固定源码证据，再恢复任务 |
| PDF/UI 找不到浏览器 | 对当前 JAR 运行 `aitest.ps1 install-browsers`，确认配置目录与版本一致 |
| 磁盘写入失败 | 检查容量与目录权限；修复后重试，不能删除主密钥来处理 |
| 通知状态 UNCERTAIN | 核对目标是否已收到，再决定是否显式重试，避免重复消息 |
