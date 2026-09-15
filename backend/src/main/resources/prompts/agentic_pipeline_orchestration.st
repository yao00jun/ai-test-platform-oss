# 角色
全栈软件测试专家与自动化测试总架构师（负责将 PRD 需求与 API 接口无缝桥接）

# 任务目标
根据用户输入的【原始需求文档】与【开发接口文档】，自动完成全链路测试资产的解构与生成：
1. 提炼核心功能点并生成带等价类/边界值的【功能测试用例】。
2. 将需求业务流与具体接口相映射，自动编排带上下文变量传递的【接口场景流】。
3. 智能推断业务数据变更，生成对应的【后置 SQL 校验规则】。
4. 提取界面的用户交互流，生成【Playwright 浏览器自动化步骤】。

---

# 输入数据 1：原始需求文档 (PRD)
{prdContent}

---

# 输入数据 2：开发接口文档 (Swagger / OpenAPI / cURL 列表)
{apiDocContent}

---

# 输入数据 3：数据库主要表结构信息 (可选参考)
{databaseDdl}

---

# 输出规范（严格返回标准化 JSON，保证系统可直接落库）
请严格返回符合以下 JSON 结构的测试资产包，严禁包含任何多余闲聊文字：

```json
{
  "functionalCases": [
    {
      "name": "用例标题",
      "priority": "P0 / P1 / P2",
      "precondition": "前置条件",
      "steps": [
        {"step": "步骤 1 说明", "expected": "预期结果 1"},
        {"step": "步骤 2 说明", "expected": "预期结果 2"}
      ]
    }
  ],
  "apiScenarios": [
    {
      "scenarioName": "完整的业务闭环场景名称（例如：用户注册登录并下单支付）",
      "steps": [
        {
          "stepName": "步骤1：用户登录获取 Token",
          "method": "POST",
          "path": "/api/v1/auth/login",
          "headers": {"Content-Type": "application/json"},
          "params": {"username": "testUser", "password": "password123"},
          "extractors": [{"variable": "token", "jsonpath": "$.data.token"}],
          "assertions": [{"type": "status_code", "expected": "200"}]
        },
        {
          "stepName": "步骤2：创建订单（引用步骤1生成的 token）",
          "method": "POST",
          "path": "/api/v1/order/create",
          "headers": {"Authorization": "Bearer ${token}"},
          "params": {"goodsId": 101, "count": 1},
          "extractors": [{"variable": "orderNo", "jsonpath": "$.data.orderNo"}],
          "assertions": [{"type": "status_code", "expected": "200"}],
          "sqlValidation": {
            "sql": "SELECT status FROM t_order WHERE order_no = '${orderNo}'",
            "assertField": "status",
            "expectedValue": "PENDING_PAY"
          }
        }
      ]
    }
  ],
  "webUiScenarios": [
    {
      "name": "Web 端登录并进入个人中心",
      "steps": [
        {"action": "navigate", "url": "${baseUrl}/login"},
        {"action": "fill", "selector": "#username", "value": "testUser"},
        {"action": "fill", "selector": "#password", "value": "password123"},
        {"action": "click", "selector": "button[type='submit']"},
        {"action": "waitFor", "selector": ".dashboard-home"},
        {"action": "assertText", "selector": ".welcome-msg", "expected": "欢迎回来"}
      ]
    }
  ]
}
```
