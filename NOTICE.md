# 第三方版权与源码

本工程包含从 MeterSphere 移植并修改的 Markdown 解析、文本处理、提示词及前端交互代码。原作者版权、来源和修改说明保留在相应源码文件头与 `frontend/THIRD_PARTY_NOTICES.md`。MeterSphere 的 GPLv3 及上游附加条款原文见 `licenses/MeterSphere-LICENSE`。

GNU GPL 第 3 版完整许可文本另附于 `licenses/GPL-3.0.txt`，按本地 Git 发行包所携原始字节复制，不修改许可文字。

TestPilot-AI 的设计参考与范围单独登记，MIT 版权及许可见 `licenses/TestPilot-AI-LICENSE`。其许可不替代 MeterSphere 代码的许可。

发行目录中的 `source.zip` 提供本次构建使用的工程源码、构建脚本、锁定依赖、测试、提示词与数据库迁移。JavaScript 和 Java 的第三方库还各自适用其上游许可。前端的 MeterSphere 来源标识及 `frontend/THIRD_PARTY_NOTICES.md` 随相应源码保留。

## 离线完整包里随附的第三方程序

`*-offline-windows.zip` 的 `.tools/` 目录额外带有下列未经修改的第三方程序，方便没有网络的电脑直接使用。它们不是本工程的一部分，各自适用其原始许可：

| 组件 | 版本 | 许可 | 说明 |
| --- | --- | --- | --- |
| MySQL Community Server（Windows x64 压缩包） | 8.4.10 | GPLv2（含 Oracle Universal FOSS Exception），全文见 `licenses/MySQL-LICENSE` | 对应源码 `mysql-8.4.10.tar.gz` 挂在本仓库 GitHub Releases 的 `third-party-sources` 发布项下，亦可从 dev.mysql.com 获取 |
| Chromium / Chrome for Testing 与 Chrome Headless Shell（Playwright 构建） | 随 Playwright 1.62.0 | BSD-3-Clause，见 `licenses/Chromium-LICENSE` | 由 Playwright 项目构建并分发的浏览器内核 |
| FFmpeg（Playwright 构建） | Playwright ffmpeg 1011 | LGPL-2.1，见 `licenses/FFmpeg-COPYING.LGPLv2.1` | 源码及构建脚本见 github.com/microsoft/playwright 的 `browser_patches/ffmpeg` |
| Playwright 附带工具 winldd | Playwright winldd 1007 | Apache-2.0，见 `licenses/Playwright-LICENSE` | Playwright 用于检查 Windows 依赖的小工具 |
| Microsoft Visual C++ 2015-2022 Redistributable (x64) | 随微软官网当前版本 | 按微软的可再分发条款原样分发 | MySQL 的 Windows 压缩包版运行所需 |

Playwright Java 客户端本身以 Apache-2.0 许可随 `app.jar` 分发，见 `licenses/Playwright-LICENSE`。联网版发行包不含上述程序，首次启动时由脚本从各官方站点下载。

本仓库为公开源码仓库；发行包与验收附件通过 GitHub Releases 分发。
