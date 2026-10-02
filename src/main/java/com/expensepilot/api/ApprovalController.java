package com.expensepilot.api;

import com.expensepilot.approval.ApprovalService;
import com.expensepilot.security.CurrentUser;
import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Graph Human-in-the-loop 审批入口。
 *
 * <p>approver 不接受客户端参数，只能取当前 JWT subject；
 * 同时要求 IdP 下发 expense.approve OAuth scope。</p>
 */
@RestController
@RequestMapping("/api/v1/expense-tasks/{taskId}/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ExpenseAgentService expenseAgentService;
    private final CurrentUser currentUser;

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long taskId,
            @RequestParam String operationType,
            @RequestParam(defaultValue = "") String comment) {

        String approver = requireApprover();

        expenseAgentService.assertApprovalReady(
                taskId,
                operationType
        );

        approvalService.approve(
                taskId,
                operationType,
                approver,
                comment
        );

        expenseAgentService.resumeApproval(
                taskId,
                operationType,
                approvalPatch(operationType, true, approver)
        );

        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "operationType", operationType,
                "status", "APPROVED"
        ));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable long taskId,
            @RequestParam String operationType,
            @RequestParam(defaultValue = "") String comment) {

        String approver = requireApprover();

        expenseAgentService.assertApprovalReady(
                taskId,
                operationType
        );

        approvalService.reject(
                taskId,
                operationType,
                approver,
                comment
        );

        expenseAgentService.resumeApproval(
                taskId,
                operationType,
                approvalPatch(operationType, false, approver)
        );

        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "operationType", operationType,
                "status", "REJECTED"
        ));
    }

    private String requireApprover() {
        currentUser.requireAnyAuthority(
                "SCOPE_expense.approve"
        );
        return currentUser.userId();
    }

    private Map<String, Object> approvalPatch(
            String operationType,
            boolean approved,
            String approver) {

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("lastApprover", approver);

        switch (operationType) {
            case "POLICY_EXCEPTION" ->
                    patch.put("policyApproved", approved);
            case "SUBMIT_REPORT" ->
                    patch.put("submitApproved", approved);
            default -> throw new IllegalArgumentException(
                    "未知审批类型: " + operationType);
        }

        return patch;
    }
}
