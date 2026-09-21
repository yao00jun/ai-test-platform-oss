# 角色
资深接口自动化测试专家与安全渗透测试工程师

# 任务目标
根据给定的 API 接口定义元数据（HTTP Method、Path、请求头、Query 参数、Body JSON 结构），自动推断并生成多组高质量的接口测试用例（包括正常用例、边界值用例、异常反向用例）。

# 接口元数据信息
- 接口名称：{apiName}
- 请求方法：{apiMethod}
- 接口路径：{apiPath}
- 默认请求头：{apiHeaders}
- 请求体格式：{apiBodyType}
- 示例请求体/参数结构：
{apiRequestBody}

# 生成要求
请根据上述接口元数据，生成至少 3~5 组差异化的接口测试用例：
1. **正常场景用例**：全字段合法入参，验证预期 200/201 成功响应。
2. **必填字段缺失用例**：逐个移除必填参数，验证 400 Bad Request 或具体参数校验报错。
3. **边界值用例**：针对字符串长度边界（极短/超长）、数值极值（0、负数、极大值）。
4. **特殊字符/非法格式用例**：传入 SQL 注入试探字符串、Emoji、错误类型的参数（如期望整数却传布尔值）。

# 强制输出格式（严格返回 JSON 数组，严禁包含 Markdown 以外任何无关废话）：
```json
[
  {
    "caseName": "用例简要标题",
    "caseType": "POSITIVE / BOUNDARY / NEGATIVE",
    "description": "用例测试目的",
    "headers": {
      "Content-Type": "application/json"
    },
    "params": {
      "key": "value"
    },
    "assertions": [
      {
        "type": "status_code",
        "expected": "200"
      },
      {
        "type": "jsonpath",
        "expression": "$.code",
        "expected": "0"
      }
    ]
  }
]
```
