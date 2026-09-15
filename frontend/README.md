# AI 测试平台前端

Vue 3、TypeScript、Arco Design，开发入口为 `http://127.0.0.1:5173`。Vite 将 `/api` 代理至 `127.0.0.1:8080`，不提供假数据或离线业务替代。

```powershell
pnpm install
pnpm dev
pnpm lint
pnpm typecheck
pnpm test:unit
pnpm build
pnpm test:e2e
```

浏览器测试需要真实后端运行；测试自行创建带唯一名称的项目并清理该项目。测试不会写入现有用户项目。模型生成需先在「模型设置」保存可用配置；没有密钥时展示后端错误，不伪造成功结果。

## 结构与设计约束

- `App.vue` 仅组合布局。`layouts/AppShell.vue` 提供导航、项目选择和模型设置入口。
- `pages/` 保留八个独立领域路由。`components/assets/` 共享目录驱动表格、表单、子项编辑及历史。
- `api/` 处理真实 HTTP、字段级补丁及 SSE；`core/request-scope.ts` 隔离过期请求。
- 白色内容面、冷灰背景、靛蓝主色、224px 石板色侧栏；中文系统字体，正文 14px、页面标题 26px，8px 间距基线。
- 页面采用表格、分栏与抽屉。窄屏侧栏折叠为覆盖导航，表格在自身容器滚动；表单与抽屉限制在视口内。
- 导航按顺序固定为：工作台、项目管理、测试用例、接口测试、场景自动化、Playwright UI、测试计划、缺陷管理。
- 不含装饰性 hero、模拟统计、虚假任务结果。JSON/SQL/代码字段可按需加载 Monaco。

Image Gen 在本次会话不可用，按已批准的文字设计规范实现。实际浏览器证据、交互核验与版本记录见主任务的前端报告。

## 并发与数据契约

身份以 `projectId + id` 为准，版本始终为字符串。PATCH 仅发送改变字段；子项单独读写。排序仅对无筛选、已完整加载的同类型同父级范围开放，每项携带原版本。项目切换、抽屉关闭只停止前端请求/订阅；只有用户点击「取消任务」才取消服务器任务。

会话索引仅在当前浏览器保留会话 ID 与目标 ID；消息内容来自服务端。API Key 不写入浏览器持久存储。

## 上游来源

`AppSidebar.vue` 的路由选中/折叠菜单与 `ConversationMessages.vue` 的消息位置、复用反馈、滚动交互改编自本地 MeterSphere 前端。具体源路径在源文件注释与 `THIRD_PARTY_NOTICES.md` 中列明。项目根目录负责完整许可证记录。
