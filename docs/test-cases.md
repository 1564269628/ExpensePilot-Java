# 典型故障注入案例

## Case 1：提交成功但响应超时

- 预置：`submit_expense_report` 在外部系统创建业务单号后模拟网络超时。
- 期望：本地执行记录进入 `UNKNOWN`。
- 禁止：直接再次 submit。
- 恢复：使用稳定 `requestId` 调用 `query_expense_submission`；如果能查到业务单号，回填 `SUCCEEDED`。
- 关键验证：外部实际提交次数仍然为 1。

## Case 2：多实例竞争与旧执行者恢复

- Instance A 获取 Redisson 锁；watchdog 在 A 正常存活时自动续租。
- 模拟 A 长时间 stop-the-world / 网络分区，导致锁最终无法续租并释放。
- Instance B 获得锁并推进 MySQL `version`。
- A 恢复后尝试用旧版本写业务状态。
- 期望：数据库 `version` CAS `affectedRows=0`，A 不能覆盖 B 已提交的新状态。
- 说明：Redis 锁负责协调，MySQL CAS 才是最终一致性保护。

## Case 3：Outbox 发布中断

- 本地事务成功写入 `task=SUCCEEDED` 和 `outbox=PENDING`。
- RocketMQ 发布前 kill 进程。
- 服务恢复后 `OutboxPublisher` 再扫描 `PENDING`。
- 期望：消息最终送达。
- 若多实例导致重复投递：Consumer 先通过 SideEffectGuard 的稳定 `eventId/requestId` 保护真实通知副作用，成功后再写 `consumed_event`。

## Case 4：Graph Checkpoint 恢复

- 三路材料获取已经完成，进入 `policyCheck` 前 kill JVM。
- RecoveryWorker 扫描到异常任务。
- 使用原 `threadId` 调用 `CompiledGraph.stream(null, config)`。
- 期望：`MysqlSaver` 从最近 checkpoint 恢复，已完成材料节点不从头重复执行。

## Case 5：邮箱 MCP 持续不可用

- `search_email` 连续返回 timeout / upstream unavailable。
- ToolGateway 先做有限指数退避。
- 达到重试上限后，邮箱查询降级成 `degraded=true + empty materials`。
- Graph 继续进入材料完整性检查。
- 期望：若网盘/差旅仍无法补齐材料，则进入 `requestSupplement`，而不是让 LLM 猜出一张发票。

## Case 6：政策冲突人工审批

- 政策服务返回 `compliant=false`。
- Graph 路由到 `humanApproval` 并 `interruptAfter`。
- 只有具备 `SCOPE_expense.approve` 的 JWT 用户可以审批。
- approve/reject 都必须写入 `approval_record`。
- 恢复后按人工决定继续到报销草稿或 `REJECTED`。
