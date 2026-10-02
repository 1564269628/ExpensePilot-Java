# ExpensePilot 架构说明

## 1. 为什么 Agent 和可靠执行要拆开

LLM 擅长理解“帮我报销上周上海出差”这样的模糊请求，但不适合承担数据库一致性、幂等、并发控制等职责。因此系统分两层：

- Agent 层：Planner、Replan、条件路由、参数修复。
- 执行层：Task 状态机、线程池、MCP Gateway、幂等、Checkpoint、租约、Outbox。

## 2. Checkpoint 恢复

每个关键节点完成后保存 AgentState 快照。JVM 崩溃后 RecoveryWorker 从 MySQL 找到异常任务，读取最新 Checkpoint，再重新获得任务租约并继续执行。

Checkpoint 不等于“把 Java 调用栈保存下来”，而是保存**足够重建下一步执行的业务状态**。

## 3. 副作用不确定状态

提交接口最危险的场景：

1. ExpensePilot 发起 submit；
2. 外部报销系统已经创建单据；
3. HTTP 响应在网络中丢失；
4. 本系统只看到 timeout。

此时不能直接 retry，否则可能重复报销。正确做法是将执行记录置为 UNKNOWN，再使用稳定 requestId / idempotencyKey 到外部系统查询；查到业务单号就回填成功，明确不存在才允许再次提交。

## 4. 双层并发保护

Redisson 锁负责“减少两个实例一起干活”，DB version CAS 负责“即使锁失效也不允许旧状态覆盖新状态”。锁是优化与协调，数据库才是最终事实源。

## 5. Outbox

任务状态和 outbox_event 同一个 MySQL 事务写入。Publisher 可以晚一点、重复多次投递，但事件不会因为进程在 commit 后崩溃而永久消失。消费端再通过 eventId 幂等抵抗重复消息。
