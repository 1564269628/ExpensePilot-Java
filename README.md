# ExpensePilot-Java

企业费用报销自主执行 Agent，基于 **Spring Boot + Spring AI Structured Output + Spring AI Alibaba Graph + MCP** 构建。

## 当前实现不是 Demo Runtime

主链已经移除 Mock MCP、手写工作流 Runtime 和自建 Checkpoint。当前生产链路是：

```text
HTTP API
  -> JWT Resource Server / task data authorization
  -> Spring AI Structured Output Planner
  -> PlanOutput / Task DAG 强类型校验
  -> Spring AI Alibaba StateGraph
       -> emailSearch ┐
       -> driveSearch ├─ 并行 fan-out / fan-in
       -> travelQuery ┘
       -> parseInvoices
       -> materialCheck
            -> 缺件 -> Graph interruptAfter -> 用户补件 -> resume
       -> policyCheck
            -> 冲突 -> Graph interruptAfter -> 人工审批 -> resume
       -> generateReport
       -> submitApproval
            -> Graph interruptAfter -> 最终提交审批 -> resume
       -> submitExpenseReport
            -> idempotencyKey / requestId / UNKNOWN reconciliation
       -> Transactional Outbox
       -> RocketMQ
       -> production notification MCP
```

Graph Checkpoint 使用 Spring AI Alibaba 自带 **MysqlSaver**；业务状态以
`expense_task` 为事实源。多实例通过 Redisson watchdog 锁协调，并使用 MySQL
`version` CAS 防止并发状态覆盖。

## Structured Output

`Planner` 使用：

```java
chatClient.prompt()
    .system(...)
    .user(...)
    .call()
    .entity(PlanOutput.class);
```

模型返回 `PlanOutput -> TripScope + List<PlannedTask>`，随后必须经过
`PlanValidator` 的确定性检查：节点唯一、依赖存在、无环、副作用标记正确，
并逐项校验 DAG 是否与生产 StateGraph 主路径一致。LLM 不拥有最终执行权限。

Planner 只规划到 `SUBMIT_REPORT`。通知不交给 LLM 规划；报销提交成功后由
`Transactional Outbox -> RocketMQ -> Notification MCP` 确定性触发。

## Graph API

完整拓扑位于：

- `config/ExpenseGraphConfig.java`
- `graph/ExpenseGraphNodes.java`
- `graph/ExpenseGraphKeys.java`

使用真实的：

- `StateGraph.addNode`
- `addEdge`
- `addConditionalEdges`
- 并行 fan-out / fan-in
- `CompileConfig.interruptAfter`
- `CompiledGraph.updateState`
- `MysqlSaver`

不存在第二套手写 Executor 工作流。

## 身份与数据权限

所有 `/api/**` 请求使用 Spring Security OAuth2 Resource Server 校验 JWT。
任务创建时的 `userId` 只取 token `sub`，请求体不能指定其他用户；查询、故障恢复、
澄清和补件都校验任务 owner。审批接口不接受客户端传入 approver，而是记录当前
JWT subject，并要求 `SCOPE_expense.approve`。

因此 Graph State 中的用户身份来自受信任 IdP，而不是可伪造的请求参数。

## 生产 MCP

四个真实 MCP Server 使用 Streamable HTTP：

- email
- drive
- travel
- expense

每个 Server 使用独立 WebClient transport，可分别配置 URL、endpoint 和 Bearer Token。
主链没有 Mock fallback；缺少真实 URL/凭证/Tool 时应启动失败，而不是返回假数据。

完整 Tool Schema：`docs/production-mcp-contract.md`。

## 可靠性

| 问题 | 实现 |
|---|---|
| Agent 如何规划 | Spring AI Structured Output + PlanValidator |
| 流程如何驱动 | Spring AI Alibaba StateGraph / CompiledGraph |
| 三路材料怎么提速 | Graph fan-out/fan-in + 独立有界线程池 |
| 外部系统怎么接 | Spring AI MCP Streamable HTTP |
| 提交重复怎么办 | 幂等键 + DB 唯一索引 + requestId |
| 提交超时但外部已成功 | UNKNOWN + query_expense_submission |
| JVM 崩溃怎么续跑 | Graph MysqlSaver + RecoveryWorker |
| 多实例重复抢任务 | Redisson watchdog lease |
| 并发状态覆盖 | MySQL version CAS |
| DB 成功但 MQ 未发送 | Transactional Outbox |
| MQ 重复投递 | eventId + SideEffectGuard + consumed_event |
| 怎么观察 | Spring AI Alibaba Graph Observation + OpenTelemetry |
| 怎么评测 | 240 条故障 JSONL 数据集 |

## 生产配置

复制 `.env.example` 的变量到部署平台 Secret/环境变量中。至少需要：

- `OPENAI_API_KEY / OPENAI_MODEL`
- `JWT_ISSUER_URI`
- MySQL / Redis / RocketMQ
- 四个 `*_MCP_URL`
- 对应 MCP Token 或基础设施级 mTLS/Service Mesh 身份

项目不会把真实凭证写进 Git。

## 评测数据

`src/test/resources/eval/expensepilot-240.jsonl` 包含 240 条异常用例：

- 缺票
- 重复提交
- 政策冲突
- Tool 超时
- 进程崩溃

指标定义见 `docs/evaluation.md`。仓库不会伪造 39%、81% -> 93%、91% 等运行结果；
这些数字必须由真实压测/故障注入产生。

> 按要求不使用 GitHub Actions 做真实集成测试。生产端到端验证需要部署方提供真实
> LLM、MCP、MySQL、Redis、RocketMQ 与 OTLP 地址和凭证。
