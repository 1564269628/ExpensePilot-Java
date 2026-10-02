# 生产 MCP Tool Contract

ExpensePilot 主流程**没有 Mock 回退**。应用启动时会初始化真实 MCP Client，并通过
`RequiredMcpToolsVerifier` 校验以下工具是否已经由生产 MCP Server 发布；缺少任一工具，
应用直接启动失败。

> MCP Server 负责把企业真实系统封装为标准 Tool。ExpensePilot 只看到工具契约，
> 不把 Outlook/Gmail、SharePoint/Drive、差旅供应商 SDK、内部报销 RPC 的细节泄漏进 Agent 层。

## 1. Server 边界

| Server | 典型真实后端 | Tool |
|---|---|---|
| email | Microsoft Graph / Gmail Enterprise / 企业邮件网关 | `search_email` |
| drive | SharePoint / OneDrive / Google Drive / 企业网盘 | `search_drive` |
| travel | 企业差旅平台 / TMC / 内部差旅聚合服务 | `query_travel` |
| expense | 发票解析、材料校验、政策服务、报销系统、通知服务 | 其余工具 |

实际企业可以把 expense 再拆成 policy/document/notification 多个 MCP Server。
ExpensePilot 对 Tool 名称做契约，不依赖物理部署数量。

## 2. 统一返回 Envelope

所有 Tool 必须返回：

```json
{
  "success": true,
  "code": "OK",
  "message": "success",
  "data": {},
  "externalBusinessNo": null
}
```

副作用 Tool 必须支持由 ExpensePilot 传入的稳定 `requestId`，并允许通过该
`requestId` 回查最终状态。

## 3. Tool Gateway 前置校验

在请求离开 ExpensePilot 之前，`ToolArgumentValidator` 会做确定性校验：

- `taskId / userId / toolName / operationType` 必须有效；
- 凡是带 `userId` 的生产 Tool，其参数值必须和 Graph 中由 JWT subject 固化的身份一致；
- `startDate/endDate` 必须是 ISO-8601 日期，且开始日期不能晚于结束日期；
- 发票、差旅、政策、报销草稿等关键对象必须存在且类型正确；
- 通知事件里的 `taskId` 必须和 `ToolCall.taskId` 一致；
- `submit_expense_report / send_notification` 必须显式标记为副作用。

成功返回还会经过 `McpToolResultValidator` 的业务语义校验：

- `validate_materials` 必须显式返回 boolean `complete` 和数组 `missingItems`；
- `check_expense_policy` 必须显式返回 boolean `compliant` 和数组 `violations`；
- `submit_expense_report` / 成功的提交回查必须返回非空 `externalBusinessNo`。

因此关键路由字段缺失不会被 Java 的默认 false 误解释成“材料缺失”或“政策冲突”。

MCP 异常分为两类：

- `McpTransportException`：网络、远端调用等传输失败；查询 Tool 可以有限重试，
  邮箱/网盘在达到上限后才允许安全降级。
- `McpContractException`：返回 JSON、Envelope、字段类型或 Tool 注册契约错误；
  **禁止降级成“空材料”**，必须立即失败并进入恢复/告警，避免把系统故障伪装成缺票。

`query_expense_submission` 和 `query_notification` 是 SideEffectGuard 在 UNKNOWN 对账时
直接通过 `ExpenseMcpClient` 调用的内部回查 Tool，不暴露在 ToolGateway 的普通业务白名单里。

## 4. Tool Schema

### search_email

输入：

```json
{
  "userId": "u10001",
  "startDate": "2026-09-21",
  "endDate": "2026-09-27",
  "city": "上海",
  "includeAttachments": true,
  "keywords": ["发票", "invoice", "行程单"]
}
```

输出 data 至少包含邮件和附件的稳定引用，不应把所有邮箱正文无上限返回：

```json
{
  "messages": [
    {
      "messageId": "msg-001",
      "subject": "上海酒店电子发票",
      "sentAt": "2026-09-26T10:30:00+08:00",
      "attachmentRefs": ["mail://msg-001/att-01"]
    }
  ]
}
```

### search_drive

输入时间范围、城市和文件类型；输出稳定 `fileRef`、文件名、修改时间、摘要元数据。
二进制文件由后续解析工具按引用读取，避免把 PDF Base64 放入 Graph State。

### query_travel

输入 `userId/startDate/endDate/city`，输出真实机票、酒店、火车、用车订单及供应商业务号。

### parse_invoices

读取 email/drive 中的材料引用，调用企业 OCR/电子发票服务完成结构化解析。
输出发票代码、号码、金额、税额、时间、购买方/销售方、文件引用及验真状态。

### validate_materials

把发票与差旅订单对账，输出：

```json
{
  "complete": false,
  "missingItems": ["返程机票电子行程单"],
  "matchedItems": []
}
```

### check_expense_policy

输入人员、城市、结构化发票和差旅订单，调用真实政策服务，不允许由 LLM 猜政策。
输出：

```json
{
  "compliant": false,
  "violations": [
    {
      "ruleId": "HOTEL-SH-001",
      "actual": 850,
      "limit": 600,
      "action": "REQUIRE_APPROVAL"
    }
  ]
}
```

### build_expense_report_draft

只生成“待提交草稿”，不能产生最终副作用。返回真实报销系统需要的字段结构。

### submit_expense_report

副作用 Tool。输入中会额外包含 `requestId`。
生产报销系统必须把 requestId 作为幂等请求号或建立 requestId -> businessNo 映射。

返回：

```json
{
  "success": true,
  "code": "OK",
  "message": "submitted",
  "data": {
    "status": "SUBMITTED"
  },
  "externalBusinessNo": "EXP202610020001"
}
```

### query_expense_submission

输入 `requestId`，解决“外部已提交成功，但响应在网络中丢失”的 UNKNOWN 状态。

### send_notification

由 RocketMQ Consumer 调用。输入包含 `eventId/taskId/userId/businessNo/templateCode/requestId`。
通知服务必须基于 requestId/eventId 幂等。

### query_notification

按 requestId 回查通知结果，用于通知调用超时后的 UNKNOWN 对账。

## 5. 认证与网络

`ProductionMcpTransportConfig` 为每个 MCP Server 创建独立 WebClient transport：

- `*_MCP_TOKEN` 非空：添加对应 Bearer Token。
- token 为空：假定身份认证由 mTLS、Service Mesh、内网 API Gateway 等基础设施完成。
- 不使用全局 WebClient Bearer Header，避免不同服务器之间泄漏凭证。
- 生产 URL 建议只暴露 HTTPS 或内网服务地址。

## 6. 启动时真实联通校验

Spring AI MCP Client 在启动时 initialize，随后 `RequiredMcpToolsVerifier` 检查完整 Tool 集。
因此以下情况会阻止应用以“假健康”状态启动：

1. MCP Server 不可访问；
2. 握手失败；
3. 凭证失败；
4. Server 可访问但缺少必须 Tool。

真正的业务可用性还应通过 Actuator/监控系统对 MCP latency、error rate 和熔断状态持续观察。
