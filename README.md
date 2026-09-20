# AI-Test-Platform 使用手册

AI-Test-Platform 是一个装在自己电脑上的测试工作台：把需求文档、接口定义、源码和数据库表结构交给它，它用 AI 帮你写测试用例、接口测试、场景脚本和网页自动化脚本；写完由你审核、修改，再交给它真正去执行 HTTP 请求、SQL 和浏览器操作，并把结果、截图和缺陷记录下来。AI 只出草稿，采纳与否由人决定。

这份手册面向没有编程经验的使用者，按「准备 → 下载 → 启动 → 日常使用 → 看日志 → 排查问题」的顺序写。开发者请直接看最后一节。

---

## 1. 准备一台电脑

| 项目 | 要求 |
| --- | --- |
| 操作系统 | Windows 10 / 11（64 位）。Linux 见第 9 节 |
| 内存 | 8 GB 以上 |
| 磁盘 | 至少 5 GB 空闲，最好是固态盘 |
| 网络 | 第一次启动需要联网下载约 900 MB 的组件，之后可以离线使用（AI 功能除外） |

**不需要提前安装任何软件。** Java、数据库、浏览器内核都会在第一次启动时自动下载到软件目录下的 `.tools` 文件夹里，卸载时整个目录删掉即可，不会在系统里留下东西。

唯一可能需要的是 PowerShell 7。Windows 11 通常自带；如果没有，启动脚本会自动用系统的 winget 安装，只需要在弹出的窗口里点「是」。

---

## 2. 下载

有两种下载方式，二选一。**没有特别原因就选方式 A。**

### 方式 A：下载发行包（推荐）

1. 打开 <https://github.com/yao00jun/ai-test-platform-oss/releases>。
2. 找到最上面的版本，点开 **Assets**，下载名字像 `ai-test-platform-1.0.0-xxxxxxxx.zip` 的文件（约 400 MB）。
3. 右键这个 zip → **全部解压**，解压到一个你记得住的文件夹，例如 `D:\ai-test-platform`。

解压后文件夹里应该有：`启动.cmd`、`停止.cmd`、`查看状态.cmd`、`查看日志.cmd`、`备份.cmd`、`app.jar`、`config.example.json`、`scripts`、`docs` 等。

### 方式 B：下载源码

适合想自己修改或打包的人。打开 <https://github.com/yao00jun/ai-test-platform-oss>，点绿色的 **Code** 按钮 → **Download ZIP**，解压。源码第一次启动会多下载 Node.js 并自己打包程序，比方式 A 多花 10 分钟左右。

### 放在哪里

- 中文和空格路径都可以，例如 `D:\测试平台`。
- 不要放在桌面、OneDrive、坚果云等会自动同步的目录里，同步会锁住数据库文件。
- 不要放在 U 盘或网络盘上。

---

## 3. 第一次启动

1. 打开解压后的文件夹，**双击 `启动.cmd`**。
2. 如果 Windows 弹出蓝色的「Windows 已保护你的电脑」，点「更多信息」→「仍要运行」。这是因为脚本没有购买微软的数字签名，不是病毒。
3. 会出现一个黑色窗口，按顺序显示 6 步：

   ```
   ==> 1/6 Java 21
   ==> 2/6 MySQL 8.4
   ==> 3/6 实例配置
   ==> 4/6 程序包
   ==> 5/6 Playwright 浏览器内核
   ==> 6/6 后端服务
   ✔ 平台已就绪：http://127.0.0.1:8080
   ```

   第一次会在第 1、2、5 步下载文件，各需要几分钟，黄色文字会告诉你正在下载什么。请耐心等待，不要关窗口。
4. 看到 **✔ 平台已就绪** 后，浏览器会自动打开 <http://127.0.0.1:8080>，黑窗口随后自动关闭。这是正常的：程序在后台继续运行。
5. 第二次以后启动只需要十几秒。

第一次启动共下载这些东西，全部来自官方网站并校验过指纹：

| 组件 | 大小 | 用途 |
| --- | --- | --- |
| Java 21（Eclipse Temurin） | 约 200 MB | 运行程序 |
| MySQL 8.4 | 约 270 MB | 保存你的项目、用例和执行记录 |
| Chromium 浏览器内核 | 约 400 MB | 执行网页自动化和生成 PDF |
| Node.js 24（只有源码方式需要） | 约 30 MB | 打包前端页面 |

如果电脑上已经装了 Java 21 或 MySQL 8.4，脚本会直接使用，不再下载。

---

## 4. 日常使用

文件夹里的几个 `.cmd` 文件就是全部操作，双击即可：

| 双击 | 作用 |
| --- | --- |
| `启动.cmd` | 启动平台并打开网页。已经在运行时再双击不会重复启动 |
| `停止.cmd` | 停止平台和数据库。关机前建议先双击它 |
| `查看状态.cmd` | 显示数据库、后端、模型配置是否正常，以及日志文件在哪 |
| `查看日志.cmd` | 实时滚动显示后端日志，关闭窗口即可退出 |
| `备份.cmd` | 把数据库和附件完整备份到 `instance\backups`，备份期间平台会短暂停止再自动恢复 |

平台的网址固定是 <http://127.0.0.1:8080>，只能在这台电脑上打开。想让同事在局域网访问，见第 8 节。

### 配置 AI 模型

不配置模型也能用：手工写用例、导入导出、执行接口和网页测试都正常，只是「AI 生成」「AI 诊断」按钮会提示缺少模型。

配置方法：打开网页 → 右上角 **模型设置** → 填写公司给你的三项信息：

| 填写项 | 说明 |
| --- | --- |
| 服务地址（baseUrl） | 例如 `https://api.example.com/v1`，只支持 OpenAI 兼容的在线服务 |
| API Key | 公司发的密钥，保存后加密存放在你的数据库里 |
| 模型名称 | 例如 `gpt-4o`、`qwen-plus`，以公司提供的名字为准 |

填完点「测试连接」，通过后保存即可。

### 界面主题

登录卡片和页面右上角的太阳/月亮按钮切换浅色、暗色。首次跟随系统，手动选择后记在当前浏览器里。

---

## 5. 看日志

出问题时，日志是最重要的线索。

**最简单的方法：双击 `查看日志.cmd`。** 窗口会实时显示后端最新的日志，新内容不断追加。看完关掉窗口就行。

**日志文件在哪：**

| 文件 | 内容 |
| --- | --- |
| `instance\logs\application-日期-时间.log` | 后端主日志。每次启动新建一个，文件名里的时间就是启动时间 |
| `instance\logs\application-日期-时间.err.log` | 后端的错误输出，通常为空；启动失败时先看它 |
| `.runtime\mysql\mysql.log` | 数据库日志。数据库启动失败时看它 |
| `.runtime\dev\vite.log` | 只有开发者开热更新时才有 |

**怎么看：** 用记事本打开，拉到最底部。正常的日志每行以时间开头，中间是 `INFO`。出错的行是 `ERROR` 或 `WARN`，下面往往跟着一段以 `Caused by:` 开头的说明，那一行就是原因。把出错那几行截图给开发者，比描述现象有用得多。

---

## 6. 出错了怎么办

先双击 `查看状态.cmd`，它会告诉你数据库和后端是不是在运行。然后对照下表。

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| 双击 `启动.cmd` 黑窗口一闪就没了，网页也没打开 | 脚本出错后窗口应该会停住等你按键。如果真的一闪而过，多半是 Windows 拦截了 | 右键 `启动.cmd` → 属性 → 勾选下方的「解除锁定」→ 确定，再双击 |
| 提示「本机还没有 PowerShell 7」后安装失败 | 电脑没有 winget，或公司网络限制 | 打开 <https://aka.ms/powershell> 下载 PowerShell 7 的 `.msi` 安装，然后再双击 `启动.cmd` |
| 「无法下载 Java 21 / MySQL 8.4」 | 网络不通或被公司防火墙拦住 | 换一个网络（手机热点也行）重试；或找人把提示里的文件下载好，放到 `.tools` 文件夹里再双击启动 |
| 「端口 8080 已被其他程序占用」 | 另一个程序在用 8080 | 用记事本打开 `instance\config.json`，把 `"port": 8080` 改成 `8090`，保存后重新启动，网址相应变成 `http://127.0.0.1:8090` |
| 「MySQL 启动后立即退出」 | 3307 端口被占，或上次没有正常关闭 | 先双击 `停止.cmd`，再双击 `启动.cmd`。还不行就打开 `.runtime\mysql\mysql.log` 看最后几行 |
| 「程序启动后退出了」或「启动超时」 | 后端没起来 | 打开提示里给出的 `.err.log` 和 `.log`，找 `ERROR` 或 `Caused by` 那几行 |
| 网页打不开、显示「无法访问此网站」 | 后端没在运行 | 双击 `查看状态.cmd` 确认；显示未运行就双击 `启动.cmd` |
| 浏览器内核安装失败 | 网络问题 | 不影响其他功能。网络好的时候再双击 `启动.cmd`，它会重试 |
| 网页自动化或 PDF 报「浏览器未安装」 | 上面那一步没成功 | 同上 |
| AI 生成失败，提示模型错误 | 模型地址、密钥或名称不对，或公司服务不可用 | 网页 → 模型设置 → 测试连接，按提示修改 |
| 黑窗口里中文显示成方块或问号 | 窗口字体不支持 | 右键窗口标题栏 → 属性 → 字体，改成「新宋体」或「Microsoft YaHei Mono」。不影响程序运行 |
| 磁盘空间越来越少 | 日志和临时文件堆积 | 开发者用 `aitest.ps1 clean` 清理；普通使用者可以删除 `instance\logs` 里旧的日志文件 |
| 想彻底重来 | | 先双击 `停止.cmd`，然后删除 `.runtime`、`instance`、`data` 三个文件夹。**这会删掉所有项目数据**，删之前先双击 `备份.cmd` |

如果表里没有你的情况，把这三样东西发给开发者：`查看状态.cmd` 的截图、黑窗口里红色文字的截图、`instance\logs` 里最新的两个日志文件。

---

## 7. 备份、恢复和升级

### 备份

双击 `备份.cmd`。它会先停止平台，把数据库导出、附件复制到 `instance\backups\backup-日期-时间-xxxx` 文件夹，再自动把平台启动回来。整个文件夹就是一份完整备份，复制到别的地方保存即可。

备份里包含解密密钥和数据库密码，请像对待公司数据一样保管，不要发到公开的地方。

### 恢复

恢复是把一份备份还原到一个**新的、空的**位置，不会覆盖当前正在用的数据。需要打开 PowerShell 敲命令，步骤如下（把路径换成你自己的）：

1. 双击 `停止.cmd`，再双击 `启动.cmd`，让数据库处于运行状态（只需要数据库，平台本身开着也没关系）。
2. 在软件文件夹里，按住 Shift 点右键 → 「在此处打开 PowerShell 窗口」，粘贴下面的命令，创建一个空数据库：

   ```powershell
   cd .runtime\mysql
   "CREATE DATABASE ai_test_restored CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON ai_test_restored.* TO 'aitest'@'localhost';" | ..\..\.tools\mysql-8.4.10-winx64\bin\mysql.exe --defaults-file=admin.cnf
   cd ..\..
   ```

3. 新建一个文件夹作为新实例，例如 `D:\恢复实例`，把 `instance\config.json` 复制进去，用记事本打开，把 `database.url` 里的 `ai_test_platform` 改成 `ai_test_restored`，把 `port` 改成 `8090`，把 `"storage"` 改成 `"data"`。
4. 执行恢复，然后启动这个新实例：

   ```powershell
   .\scripts\aitest.ps1 restore -BackupDirectory 'D:\ai-test-platform\instance\backups\backup-20260919-202535-41b9205e' -InstanceDirectory 'D:\恢复实例'
   .\scripts\aitest.ps1 start -InstanceDirectory 'D:\恢复实例'
   ```

5. 浏览器打开 <http://127.0.0.1:8090> 检查数据。确认无误后，可以继续用这个实例，也可以停掉它。

### 升级到新版本

1. 双击 `备份.cmd`，再双击 `停止.cmd`。
2. 下载新版本的 zip，解压到一个新文件夹。
3. 把旧文件夹里的 `instance`、`data`、`.runtime`、`.tools` 四个文件夹**整个复制**到新文件夹里（`.tools` 复制过去可以省掉重新下载）。
4. 在新文件夹里双击 `启动.cmd`。第一次启动会自动升级数据库结构。

如果升级后有问题，回到旧文件夹双击 `启动.cmd` 即可继续用旧版；旧版的数据在旧文件夹里没有被改动。

---

## 8. 让同事一起用（可选）

默认只能本机访问，因为没有设置密码。要开放给局域网：

1. 双击 `停止.cmd`。
2. 用记事本打开 `instance\config.json`，改三处：

   ```json
   "bind": "0.0.0.0",
   "security": { "enabled": true, "username": "admin", "password": "至少12位的密码", "sessionMinutes": 30, "secureCookie": false },
   ```

3. 双击 `启动.cmd`。同事在浏览器输入 `http://你的电脑IP:8080`，用上面的账号密码登录。

电脑 IP 可以在 `查看状态.cmd` 之外通过 Windows 设置 → 网络 → 属性里看到。只有一个账号，所有人共用；这个平台面向个人或小团队，没有多用户权限管理。

---

## 9. 在 Linux 服务器上运行

下载和解压同第 2 节。然后在解压后的目录里执行：

```bash
chmod +x scripts/aitest.sh
./scripts/aitest.sh up        # 首次会自动下载 Java 21、MySQL 8.4、Chromium 到 .tools/
./scripts/aitest.sh status    # 查看状态
./scripts/aitest.sh logs      # 看日志，Ctrl+C 退出
./scripts/aitest.sh backup    # 备份
./scripts/aitest.sh down      # 停止
```

需要系统自带的 `bash`、`curl`、`tar`、`xz`、`python3`（Ubuntu、Debian、CentOS 默认都有）。MySQL 还需要 `libaio` 库，Ubuntu 上脚本会自动补齐；其他发行版按提示 `sudo dnf install libaio` 即可。Chromium 缺少系统库时用 `./scripts/aitest.sh install-browsers --with-deps`（需要 root）。选项都用 `--小写-连字符` 写法，例如 `--instance-directory /srv/aitest`。

---

## 10. 平台功能一览

| 模块 | 主要能力 |
| --- | --- |
| 工作台 | 质量指标、可配置卡片、AI 质量报告、自动晨报、EvalOps |
| 项目管理 | 项目与环境、需求文档、源码/DDL 固定快照、模型与数据源配置 |
| 测试用例 | 列表、模块树、脑图、独立步骤、人工评审与多轮反馈 |
| 接口测试 | OpenAPI/Swagger、Postman、cURL、HAR 导入，断言、提取、鉴权与契约 Diff |
| 场景自动化 | HTTP→SQL 场景链、变量作用域、数据集及 DDT、SQL 校验 |
| Playwright UI | 步骤与 DSL、语义定位器、frame/popup、附件、录制导入与独立 Java 导出 |
| 测试计划 | 混合执行、排期、不可变报告、人工项录入、定向回归 |
| 缺陷管理 | 手工录单、失败去重与发生历史、代码 RCA、修复建议、人工评价 |

AI 草稿由人裁决：全局反馈产生可选择的变更集；单条用例、单句 SQL、单个 UI 步骤可以多轮原地调优，每轮基于当前版本，人工修改优先。各类型导入格式见发行包 `docs/import-templates-guide.md`。

需求支持本地路径、拖拽上传或粘贴；绝对路径指运行程序那台电脑上的文件。源码与 DDL 先导入成固定版本，再用于影响分析、生成和诊断；源文件后来改变不会改写历史证据。静态源码线索与模型建议仍需实际执行验证，代码 RCA 的补丁只是供人审阅的建议，不会自动修改业务源码。

---

## 11. 给开发者

### 一个脚本

`scripts/` 里只有两个脚本：Windows 用 `aitest.ps1`，Linux 用 `aitest.sh`，命令相同。根目录的 `*.cmd` 只是给非开发者双击用的壳。

```powershell
.\scripts\aitest.ps1 up            # = 启动.cmd；-Dev 额外起 Vite 热更新并打开 5173；-Build 先重新打包；-NoBrowser 不弹浏览器
.\scripts\aitest.ps1 down          # = 停止.cmd；-KeepMysql 保留数据库
.\scripts\aitest.ps1 restart | status | logs [-Errors] [-Vite] | check [-TestModel]
.\scripts\aitest.ps1 verify        # 前端 lint/单测/构建 + 后端单测和快速集成测试，约 4～7 分钟
.\scripts\aitest.ps1 verify -Full  # 加上 @Tag("slow") 的重集成测试，与发行构建相同
.\scripts\aitest.ps1 verify -IncludeBrowser   # 再以独立端口跑 Playwright 端到端
.\scripts\aitest.ps1 build         # 完整发行构建，输出到 artifacts\releases（= 打包发行.cmd）
.\scripts\aitest.ps1 clean [-WhatIf]          # 清理 .runtime 过程文件和旧发行包
.\scripts\aitest.ps1 mysql [-DataDirectory D:\ssd\mysql]   # 启动/初始化项目 MySQL；机械盘首次可把数据目录指到固态盘
.\scripts\aitest.ps1 maven -B -ntp verify     # 参数原样传给仓库内的 Maven Wrapper
```

前端命令统一经 `corepack` 使用 `frontend/package.json` 钉住的 pnpm 版本，本机装的是哪个 pnpm 无关紧要。`scripts/tests/` 下是运维脚本的契约测试和发行演练脚本，手动执行。运行、构建、测试库、备份恢复和性能复测的细节见发行包 `docs/operations.md`。

### 当前锁定版本

| 范围 | 实际采用 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.1、Spring AI 2.0.1 |
| 执行与存储 | MySQL 8.4.10、Playwright Java 1.62.0、POI 5.5.1、PDFBox 3.0.8、Flexmark 0.64.8 |
| 源码与 SQL | JavaParser 3.28.2、JSqlParser 5.4 |
| 前端 | Vue 3.5.42、Vite 8.3.0、TypeScript 6.0.3、Arco 2.58.0、Monaco 0.56.0 |

完整依赖以 [backend/pom.xml](backend/pom.xml) 和 [frontend/pnpm-lock.yaml](frontend/pnpm-lock.yaml) 为准。

### 文档与许可

- 架构、API 契约、数据库与迁移、运行手册、源码分析、计划排期、群通知、晨报、EvalOps、提示词等使用者文档随发行包的 `docs/` 目录分发，见 [GitHub Releases](https://github.com/yao00jun/ai-test-platform-oss/releases)。
- 本项目以 GPL-3.0 发布，见 [LICENSE](LICENSE)。移植自 MeterSphere 与 TestPilot-AI 的代码、其版权与许可说明见 [NOTICE.md](NOTICE.md)、[licenses/](licenses/) 与 [frontend/THIRD_PARTY_NOTICES.md](frontend/THIRD_PARTY_NOTICES.md)。

发行包包含应用 JAR、配置示例、脚本、迁移、面向使用者的文档、第三方许可、对应源码及 SHA-256 清单；验收记录与实施计划只留在源码仓库。公司模型与实际业务环境尚未配置，协议 fixture 的通过不代表公司模型的生成质量。
