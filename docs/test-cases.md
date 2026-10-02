# 典型故障注入案例

## Case 1：提交成功但响应超时

- 预置：`submit_expense_report` 在外部系统创建业务单号后模拟网络超时。
- 期望：本地执行记录进入 UNKNOWN。
- 禁止：直接再次 submit。
- 恢复：用稳定 requestId 查询外部系统；如果能查到业务单号，回填 SUCCEEDED。

## Case 2：Redisson Lease 过期

- Instance A 获取任务租约。
- A 发生长 GC，超过 lease。
- Instance B 获得租约并推进 version。
- A 恢复并尝试写入旧状态。
- 期望：数据库 version CAS affectedRows=0，A 放弃更新。

## Case 3：Outbox 发布中断

- 本地事务成功写入 task=SUCCEEDED 和 outbox=PENDING。
- 发布 RocketMQ 前 kill 进程。
- 服务恢复后 Publisher 再扫描 PENDING。
- 期望：消息最终送达；若重复投递，消费者通过 eventId 去重。
