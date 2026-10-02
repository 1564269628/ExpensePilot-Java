package com.expensepilot.recovery;

import org.springframework.stereotype.Component;

/**
 * 任务级恢复阶梯。
 *
 * <p>参数修复在 Planner Structured Output 校验循环完成；
 * 只读 Tool 的瞬时重试/安全降级在 ToolGateway 完成；
 * 这里负责跨 Graph 执行的“续跑 -> Replan -> 人工接管”。</p>
 */
@Component
public class RecoveryPolicy {

    public RecoveryAction decide(int retryCount) {
        if (retryCount <= 2) {
            return RecoveryAction.RESUME_CHECKPOINT;
        }
        if (retryCount <= 4) {
            return RecoveryAction.REPLAN;
        }
        return RecoveryAction.MANUAL_TAKEOVER;
    }

    public enum RecoveryAction {
        RESUME_CHECKPOINT,
        REPLAN,
        MANUAL_TAKEOVER
    }
}
