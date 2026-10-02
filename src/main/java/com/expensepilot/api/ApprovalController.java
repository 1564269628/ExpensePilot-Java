package com.expensepilot.api;

import com.expensepilot.approval.ApprovalService;
import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Graph Human-in-the-loop 审批入口。 */
@RestController
@RequestMapping("/api/v1/expense-tasks/{taskId}/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ExpenseAgentService expenseAgentService;

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long taskId,
            @RequestParam String operationType,
            @RequestParam String approver,
            @RequestParam(defaultValue = "") String comment) {

        // 先验证任务确实停在对应中断点，再落审批记录，防止“提前审批”污染审计表。
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
            @RequestParam String approver,
            @RequestParam(defaultValue = "") String comment) {

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
