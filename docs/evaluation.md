# 稳定性评测设计

## 数据集

仓库包含 240 条 JSONL 测试样本，五类故障各 48 条：

| 故障类型 | 核心验证 |
|---|---|
| MISSING_INVOICE | 能否识别缺票并进入补件，而不是继续提交 |
| DUPLICATE_SUBMIT | 相同幂等键重复调用时，外部提交次数是否仍为 1 |
| POLICY_CONFLICT | 超标准费用能否进入人工审批 |
| TOOL_TIMEOUT | 有限重试、Checkpoint、恢复链路是否生效 |
| PROCESS_CRASH | JVM/Worker 中断后能否从最近 Checkpoint 续跑 |

JSONL 中的 `userId` 是离线评测样本身份标签，不是生产 API 可提交字段。
生产 API 的用户身份来自已验证 JWT 的 `sub`。

## 指标定义

### 端到端完成率

`最终完成任务数 / 全部评测任务数`。

简历中的提升值必须按“基线版本 vs 开启自动恢复版本”使用同一批样本对比，
不能混用不同样本或只挑成功案例。

### 自动恢复率

`无需人工介入即从可恢复异常恢复成功的任务数 / 可恢复异常任务数`。

缺票和政策冲突本身需要用户/审批人提供信息，不应强行计入“应该自动恢复”的分母。

### 并行材料获取耗时

同一个样本分别执行两种拓扑：

1. 串行基线：邮箱 -> 网盘 -> 差旅；
2. Graph 并行：`resolveTripRange` 同时 fan-out 到 `emailSearch / driveSearch / travelQuery`，
   三个分支完成后 fan-in 到 `materialJoin`。

每个外部系统节点使用独立有界线程池做资源隔离。

比较端到端 P50/P95，而不是只比较单次最优值。
任何“耗时降低 xx%”必须由固定数据集重复运行后计算。

### Checkpoint 恢复正确率

对 `PROCESS_CRASH` 样本记录：

- crash 前最后完成节点；
- `MysqlSaver` 最新 checkpoint；
- 恢复后的第一个执行节点；
- 已完成只读 Tool 是否被不必要重复调用；
- 副作用 Tool 是否保持幂等。

### 副作用安全

对 `DUPLICATE_SUBMIT` / timeout-after-commit 场景验证：

- 外部 `submit_expense_report` 实际创建单据次数；
- `tool_execution_record` 状态迁移；
- `requestId` 回查结果；
- `UNKNOWN` 状态下是否禁止盲目二次提交。

## 结果文件建议

真实压测后保存原始结果：

```text
benchmark/
  baseline-serial.csv
  graph-parallel.csv
  recovery-off.csv
  recovery-on.csv
  side-effect-chaos.csv
  summary.json
```

本仓库当前提供实现、单元测试、240 条故障数据与实验定义，不伪造真实运行数字。
需要写入简历的吞吐、耗时下降、完成率和恢复率，都应由真实环境压测输出覆盖 `summary.json`。
