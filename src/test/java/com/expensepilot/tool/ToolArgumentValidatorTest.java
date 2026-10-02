package com.expensepilot.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolArgumentValidatorTest {

    private final ToolArgumentValidator validator =
            new ToolArgumentValidator();

    @Test
    void acceptsValidTripQueryBoundToAuthenticatedUser() {
        ToolCall call = new ToolCall(
                1001L,
                "employee-1",
                "query_travel",
                Map.of(
                        "userId", "employee-1",
                        "startDate", "2026-09-21",
                        "endDate", "2026-09-27",
                        "city", "上海"
                ),
                false,
                "QUERY_TRAVEL"
        );

        assertDoesNotThrow(() -> validator.validate(call));
    }

    @Test
    void rejectsToolArgumentUserDifferentFromGraphIdentity() {
        ToolCall call = new ToolCall(
                1001L,
                "employee-1",
                "search_email",
                Map.of(
                        "userId", "employee-2",
                        "startDate", "2026-09-21",
                        "endDate", "2026-09-27",
                        "city", "上海"
                ),
                false,
                "SEARCH_EMAIL"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(call)
        );
    }

    @Test
    void rejectsInvalidTripDateRange() {
        ToolCall call = new ToolCall(
                1001L,
                "employee-1",
                "search_drive",
                Map.of(
                        "userId", "employee-1",
                        "startDate", "2026-09-27",
                        "endDate", "2026-09-21",
                        "city", "上海"
                ),
                false,
                "SEARCH_DRIVE"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(call)
        );
    }

    @Test
    void rejectsNotificationForAnotherTask() {
        ToolCall call = new ToolCall(
                1001L,
                "employee-1",
                "send_notification",
                Map.of(
                        "eventId", "evt-1",
                        "eventType", "EXPENSE_SUBMITTED",
                        "taskId", 9999L,
                        "userId", "employee-1",
                        "businessNo", "EXP-1",
                        "templateCode", "EXPENSE_SUBMITTED"
                ),
                true,
                "SEND_NOTIFICATION:evt-1"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(call)
        );
    }
}
