# ExpensePilot-Java

企业费用报销自主执行 Agent（Java / Spring Boot / Spring AI Alibaba Graph）。

## 项目主线

ExpensePilot 不是“让大模型填报销单”，而是把 LLM 的理解、规划与决策能力接到可靠的 Java 分布式后端执行体系中。一次报销可能持续几十个步骤，并调用邮箱、网盘、差旅、政策库和报销系统，因此工程重点是：可靠执行、幂等、一致性、故障恢复和可观测。

```text
用户请求
  -> Planner
  -> Task DAG
  -> 并发材料获取（邮件 / 网盘 / 差旅）
  -> 发票解析 / 材料完整性检查
  -> 政策核验
  -> 缺材料 ? 补件 : 审批
  -> 生成报销单
  -> 幂等提交
  -> Outbox
  -> RocketMQ
  -> 通知 / 审计
```

## 简历能力到代码的映射

| 简历能力 | 主要实现 |
|---|---|
| Planner + Executor / Task DAG | `agent`、`planner`、`executor` |
| 条件路由 | `ExpenseAgentGraph` |
| 有界线程池并发 | `ExecutorConfig`、`ParallelMaterialService` |
| MCP Tool Gateway | `tool`、`mcp` |
| 副作用幂等 | `SideEffectGuard`、`tool_execution_record` |
| Checkpoint / 续跑 | `checkpoint`、`recovery` |
| Redisson Lease + DB Version CAS | `coordination` |
| Outbox + RocketMQ | `outbox` |
| OpenTelemetry | `observability` |
| 240 条异常测试数据 | `src/test/resources/eval` |

## 可靠性原则

1. MySQL 是状态事实源；Redis 仅缓存热点上下文和租约。
2. 查询类 Tool 可以有限重试；提交、通知等副作用 Tool 必须先经过审批和幂等检查。
3. 外部调用“超时”不等于失败。副作用调用超时后进入 `UNKNOWN`，优先通过 requestId / idempotencyKey / businessNo 回查外部结果。
4. Redisson 锁只减少重复抢占，数据库 version 乐观锁负责最终状态保护。
5. 状态变更和 Outbox 事件同事务提交，MQ 消费侧再用 eventId 做幂等。

> 本仓库不配置 GitHub Actions。代码以真实工程结构、关键链路完整和面试可解释性为目标，并提供可替换的 Mock MCP 适配器与故障注入测试数据。
