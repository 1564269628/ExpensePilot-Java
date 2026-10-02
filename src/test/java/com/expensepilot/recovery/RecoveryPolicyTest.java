package com.expensepilot.recovery;

import org.junit.jupiter.api.Test;

import static com.expensepilot.recovery.RecoveryPolicy.RecoveryAction.MANUAL_TAKEOVER;
import static com.expensepilot.recovery.RecoveryPolicy.RecoveryAction.REPLAN;
import static com.expensepilot.recovery.RecoveryPolicy.RecoveryAction.RESUME_CHECKPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RecoveryPolicyTest {

    private final RecoveryPolicy policy = new RecoveryPolicy();

    @Test
    void resumesCheckpointForEarlyFailures() {
        assertEquals(RESUME_CHECKPOINT, policy.decide(1));
        assertEquals(RESUME_CHECKPOINT, policy.decide(2));
    }

    @Test
    void replansAfterRepeatedCheckpointFailures() {
        assertEquals(REPLAN, policy.decide(3));
        assertEquals(REPLAN, policy.decide(4));
    }

    @Test
    void escalatesToManualTakeoverAfterRecoveryBudgetExhausted() {
        assertEquals(MANUAL_TAKEOVER, policy.decide(5));
        assertEquals(MANUAL_TAKEOVER, policy.decide(9));
    }
}
