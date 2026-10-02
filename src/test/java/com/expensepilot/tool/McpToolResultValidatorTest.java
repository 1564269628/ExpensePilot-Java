package com.expensepilot.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolResultValidatorTest {

    private final McpToolResultValidator validator =
            new McpToolResultValidator();

    @Test
    void acceptsMaterialValidationWithExplicitBoolean() {
        ToolResult result = new ToolResult(
                true,
                "OK",
                "success",
                Map.of(
                        "complete", false,
                        "missingItems", List.of("返程行程单")
                ),
                null
        );

        assertDoesNotThrow(() ->
                validator.validate(
                        "validate_materials",
                        result
                )
        );
    }

    @Test
    void rejectsMaterialResultWithoutComplete() {
        ToolResult result = new ToolResult(
                true,
                "OK",
                "success",
                Map.of(
                        "missingItems", List.of()
                ),
                null
        );

        assertThrows(
                McpContractException.class,
                () -> validator.validate(
                        "validate_materials",
                        result
                )
        );
    }

    @Test
    void rejectsPolicyResultWithoutCompliant() {
        ToolResult result = new ToolResult(
                true,
                "OK",
                "success",
                Map.of(
                        "violations", List.of()
                ),
                null
        );

        assertThrows(
                McpContractException.class,
                () -> validator.validate(
                        "check_expense_policy",
                        result
                )
        );
    }

    @Test
    void requiresExternalBusinessNumberAfterSuccessfulSubmit() {
        ToolResult result = new ToolResult(
                true,
                "OK",
                "submitted",
                Map.of("status", "SUBMITTED"),
                null
        );

        assertThrows(
                McpContractException.class,
                () -> validator.validate(
                        "submit_expense_report",
                        result
                )
        );
    }
}
