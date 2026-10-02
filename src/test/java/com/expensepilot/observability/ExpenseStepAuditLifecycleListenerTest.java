package com.expensepilot.observability;

import com.expensepilot.domain.StepType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpenseStepAuditLifecycleListenerTest {

    @Test
    void mapsProductionGraphNodesToPlannerStepTypes() {
        assertEquals(
                StepType.SEARCH_EMAIL,
                ExpenseStepAuditLifecycleListener
                        .stepTypeForNode("emailSearch")
                        .orElseThrow()
        );

        assertEquals(
                StepType.SUBMIT_REPORT,
                ExpenseStepAuditLifecycleListener
                        .stepTypeForNode("submit")
                        .orElseThrow()
        );
    }

    @Test
    void ignoresRuntimeOnlyNodes() {
        assertTrue(
                ExpenseStepAuditLifecycleListener
                        .stepTypeForNode("humanApproval")
                        .isEmpty()
        );

        assertTrue(
                ExpenseStepAuditLifecycleListener
                        .stepTypeForNode("outbox")
                        .isEmpty()
        );
    }
}
