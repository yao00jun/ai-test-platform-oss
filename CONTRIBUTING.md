# 参与开发

欢迎提交改进。这份说明告诉你怎么把环境跑起来、代码放在哪、改完怎么验证、怎么把改动交回来。

## 1. 准备环境

只需要 Windows 10/11、Java 21（JDK）和 PowerShell 7。其余（MySQL 8.4、Node.js 24、pnpm、Chromium）由脚本自动下载到 `.tools/`，也可以用本机已装好的：脚本会依次查 `instance/config.json` 里填的路径、环境变量（`JAVA_HOME`、`MYSQL_HOME`、`PLAYWRIGHT_BROWSERS_PATH`）、`.tools/`、本机常见安装位置。

```powershell
git clone https://github.com/<你的账号>/ai-test-platform-oss.git
cd ai-test-platform-oss
.\scripts\aitest.ps1 up -Dev      # 起 MySQL、后端和 Vite 热更新，打开 http://127.0.0.1:5173
.\scripts\aitest.ps1 status       # 看每样工具是从哪里找到的
```

Linux 用 `scripts/aitest.sh`，命令相同，选项写成 `--小写-连字符`。

## 2. 代码在哪

| 位置 | 内容 |
| --- | --- |
| `backend/src/main/java/com/aitest/` | 后端。按模块分包：`api` 接口测试、`asset` 用例资产、`bug` 缺陷、`engine` 执行引擎、`ai` 模型调用、`exchange` 导入导出、`security` 登录、`workbench` 工作台等 |
| `backend/src/main/resources/db/migration/` | Flyway 数据库迁移。**只能新增** `V<下一个号>__说明.sql`，不能修改已有文件 |
| `backend/src/main/resources/prompts/` | 提示词模板 |
| `backend/src/test/java/` | 后端单元测试（`*Test`）和集成测试（`*IT`，用真实 MySQL） |
| `frontend/src/pages/` | 八个页面，文件名与菜单一一对应 |
| `frontend/src/components/`、`frontend/src/api/` | 共享组件与 HTTP/SSE 封装 |
| `frontend/tests/` | 前端单元测试与 Playwright 端到端测试 |
| `scripts/aitest.ps1`、`scripts/aitest.sh` | 唯一的运维/构建脚本，Windows 与 Linux 各一份 |
| `docs/` | 架构、API 契约、数据库、运行手册等 |

设计约束见 `docs/architecture-design.md`，接口契约见 `docs/api-contract.md`。改接口时两者同步更新。

## 3. 开发循环

1. 从 `main` 开一个分支：`git switch -c feat/简短描述`。
2. 改代码。后端改完用 `.\scripts\aitest.ps1 restart` 重启；前端在 `-Dev` 模式下自动刷新。
3. 提交前跑检查，必须全绿：

   ```powershell
   .\scripts\aitest.ps1 verify                 # 前端 lint/单测/构建 + 后端单测 + 快速集成测试，约 5～10 分钟
   .\scripts\aitest.ps1 verify -Full           # 改了执行引擎、恢复、容量相关代码时再跑全量（含 @Tag("slow")）
   .\scripts\aitest.ps1 verify -IncludeBrowser # 改了前端交互时再跑浏览器端到端
   ```

4. 新功能要带测试；修 bug 先写一个能复现的测试再修。
5. 用户可见的文字用中文；错误信息要告诉用户下一步怎么做。

代码风格由 `.editorconfig` 和 `frontend/eslint.config.js` 约束：Java 4 空格，其余 2 空格，UTF-8，LF。

## 4. 提交与 Pull Request

- 提交信息用「范围: 做了什么」的格式，例如 `api: 支持 HAR 导入时保留请求顺序`；一个提交只做一件事。
- 推到你自己的 fork，向 `yao00jun/ai-test-platform-oss` 的 `main` 发起 Pull Request，按模板填写。
- 每个 PR 会自动跑 GitHub Actions（等同 `verify`）。绿了才会被合并；红了请看日志修好再推。
- `main` 分支受保护，不接受直接推送。

## 5. 不要提交的东西

`.tools/`、`.runtime/`、`instance/`、`data/`、`artifacts/`、`node_modules/`、`target/` 都被 `.gitignore` 排除，里面有下载的工具、数据库数据、密钥和日志。任何账号密码、API key、公司内网地址都不要写进代码或文档。

## 6. 发布

只有维护者做。`.\scripts\aitest.ps1 build` 生成联网版和离线完整版两个 zip，经 `node scripts/tests/release-smoke.mjs <发行目录>` 演练通过后上传到 GitHub Releases。流程见 `docs/operations.md`。
