package com.expensepilot.recovery;

import org.springframework.stereotype.Component;

/**
 * “有限重试 -> 参数修复 -> 降级 -> Replan -> 人工接管”的确定性策略。
 */
@Component
public class RecoveryPolicy {

    public RecoveryAction decide(String errorCode, int retryCount) {
        if ("TIMEOUT".equals(errorCode) && retryCount < 3) return RecoveryAction.RETRY;
        if ("INVALID_ARGUMENT".equals(errorCode) && retryCount < 2) return RecoveryAction.REPAIR_ARGUMENTS;
        if ("UPSTREAM_UNAVAILABLE".equals(errorCode) && retryCount < 3) return RecoveryAction.FALLBACK;
        if (retryCount < 5) return RecoveryAction.REPLAN;
        return RecoveryAction.MANUAL_TAKEOVER;
    }

    public enum RecoveryAction {
        RETRY, REPAIR_ARGUMENTS, FALLBACK, REPLAN, MANUAL_TAKEOVER
    }
}
