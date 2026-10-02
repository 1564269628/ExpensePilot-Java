# ExpensePilot 架构说明

## 1. 总体原则：LLM 负责候选计划，Java/Graph 决定能否执行

ExpensePilot 的主链已经统一为：

```text
HTTP API
  -> Spring AI Structured Output Planner
  -> PlanValidator
  -> Spring AI Alibaba StateGraph / CompiledGraph
  -> MCP Tool Gateway
  -> 企业真实系统
```

Planner 使用 `ChatClient.call().entity(PlanOutput.class)` 生成强类型 `PlanOutput`。
模型不能直接生成任意工具名，也不能改企业工作流的安全拓扑。

`PlanValidator` 会把 Planner 生成的 Task DAG 与生产 `StateGraph` 主路径逐项比较。
因此 DAG 不是“只给人看的假计划”；它必须与真正执行的 Graph 主路径一致。
补件、人工审批、Replan 属于 Runtime 条件分支，不放进主路径 DAG。

## 2. Graph-first 工作流

固定拓扑和条件分支都在 `ExpenseGraphConfig` 中声明：

- `addNode`：一个确定的业务动作；
- `addEdge`：固定流转；
- `addConditionalEdges`：材料缺失、政策冲突、审批结果等条件路由；
- 多条从同一节点发出的边：邮箱 / 网盘 / 差旅并行 fan-out；
- 多条边汇入 `materialJoin`：fan-in；
- `interruptAfter`：澄清、补件、政策审批、最终提交审批；
- `CompiledGraph.updateState`：人工输入写回 checkpoint 后恢复。

不存在第二套 `while/switch` 手写工作流 Runtime。

## 3. Checkpoint 与故障恢复

Checkpoint 使用 Spring AI Alibaba 自带 `MysqlSaver`，而不是业务代码自建快照表。

`threadId` 是 Graph 恢复键：

1. 每次 Graph 执行后，`MysqlSaver` 保存状态与下一执行位置；
2. JVM / Pod 崩溃后，`RecoveryWorker` 扫描 MySQL 业务任务；
3. 用同一个 `threadId` 调用 `CompiledGraph.stream(null, config)`；
4. Graph 从最近 checkpoint 继续，而不是从头重跑。

业务状态 `expense_task` 与 Graph checkpoint 职责不同：

- `expense_task`：业务事实、运营查询、版本 CAS；
- `MysqlSaver`：工作流控制状态和恢复位置。

## 4. 恢复阶梯

恢复不是无限重试：

1. Planner Structured Output 校验失败：反馈确定性错误，让模型有限次数重新生成；
2. 查询类 MCP 瞬时失败：ToolGateway 有限指数退避；
3. 邮箱 / 网盘持续不可用：安全降级为空材料，后续进入人工补件；
4. Graph / 进程级失败前两次：从 checkpoint 续跑；
5. 持续失败：换新的 Graph `threadId`，重新经过 Planner 做 Replan；
6. 恢复预算耗尽：`MANUAL_TAKEOVER`。

差旅、政策这类关键事实不能通过猜测降级。

## 5. 副作用与 UNKNOWN

提交报销、发送通知属于副作用操作。

`SideEffectGuard` 使用：

- 稳定 `idempotencyKey`；
- 稳定 `requestId`；
- MySQL 唯一索引；
- `RUNNING / SUCCEEDED / FAILED / UNKNOWN` 执行记录。

最危险的场景是：

1. ExpensePilot 发出 submit；
2. 外部报销系统已经创建单据；
3. 响应在网络中丢失；
4. 本系统只看到异常。

此时状态进入 `UNKNOWN`，不能盲目再次 submit。
系统调用 `query_expense_submission(requestId)` 对账，确认外部结果后再继续。

## 6. Human-in-the-loop

人工决定由两部分组成：

- Graph：负责中断和恢复；
- `approval_record`：负责保存人工事实。

`approve/reject` 都使用 upsert，因此即使政策异常审批没有预先创建 PENDING 记录，
最终的人类决定也一定可审计。

最终 `SUBMIT_REPORT` 除了 Graph 审批分支外，ToolGateway 还会再次读取
`approval_record`，形成“工作流层 + 副作用网关层”双重保护。

## 7. 多实例并发

Redisson 锁使用 watchdog 自动续租，负责减少多个实例同时执行同一任务。

MySQL `version` CAS 是最终保护：

```sql
update expense_task
set status=?, current_node=?, version=version+1
where id=? and version=?
```

即使 Redis 锁发生租约边界问题，旧执行者也不能覆盖新执行者的业务状态。

## 8. Transactional Outbox 与 RocketMQ

报销提交成功后：

1. 在一个 MySQL 本地事务中更新任务状态并插入 `outbox_event`；
2. `OutboxPublisher` 至少一次投递 RocketMQ；
3. 消息 Envelope 保留原始 `eventId`；
4. Consumer 调用真实 `send_notification` MCP Tool；
5. 外部通知成功后才写 `consumed_event`。

所以不会用 `message.hashCode()` 伪造 eventId，也不会在通知真正成功前提前确认消费。

## 9. 生产 MCP

邮箱、网盘、差旅、报销分别使用独立 Streamable HTTP transport，可配置独立 Bearer Token。

主链没有 Mock fallback。启动时会：

1. 初始化真实 MCP Client；
2. 拉取工具列表；
3. `RequiredMcpToolsVerifier` 检查完整 Tool 契约。

完整输入输出协议见 `docs/production-mcp-contract.md`。

## 10. MySQL、Redis 与可观测

MySQL 是事实源，Flyway 管理业务表版本。

Redis 只缓存热点任务视图：

```text
GET task
  -> Redis hit
  -> miss -> MySQL
  -> 回填短 TTL
```

状态迁移会主动失效缓存，Redis 故障时直接回退 MySQL。

Graph 使用 Spring AI Alibaba Graph Observation，MCP ToolGateway 通过 Micrometer
Observation 建立 Tool span，最终由 OpenTelemetry/OTLP 输出。
