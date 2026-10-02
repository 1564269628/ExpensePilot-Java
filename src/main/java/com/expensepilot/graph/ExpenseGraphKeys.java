package com.expensepilot.graph;

/** Graph State key 统一定义，避免各节点散落硬编码字符串。 */
public final class ExpenseGraphKeys {

    private ExpenseGraphKeys() {}

    public static final String TASK_ID = "taskId";
    public static final String USER_ID = "userId";
    public static final String REQUEST_TEXT = "requestText";

    public static final String PLAN = "plan";
    public static final String TRIP_START = "tripStart";
    public static final String TRIP_END = "tripEnd";
    public static final String TRIP_CITY = "tripCity";
    public static final String CLARIFICATION_QUESTIONS = "clarificationQuestions";
    public static final String PLANNER_ROUTE = "plannerRoute";

    public static final String EMAIL_RESULT = "emailResult";
    public static final String DRIVE_RESULT = "driveResult";
    public static final String TRAVEL_RESULT = "travelResult";
    public static final String INVOICE_RESULT = "invoiceResult";
    public static final String MATERIAL_RESULT = "materialResult";
    public static final String SUPPLEMENTAL_MATERIALS = "supplementalMaterials";
    public static final String MATERIAL_ROUTE = "materialRoute";

    public static final String POLICY_RESULT = "policyResult";
    public static final String POLICY_ROUTE = "policyRoute";
    public static final String POLICY_APPROVED = "policyApproved";
    public static final String POLICY_DECISION_ROUTE = "policyDecisionRoute";

    public static final String REPORT_DRAFT = "reportDraft";
    public static final String SUBMIT_APPROVED = "submitApproved";
    public static final String SUBMIT_DECISION_ROUTE = "submitDecisionRoute";
    public static final String SUBMIT_RESULT = "submitResult";

    public static final String TASK_STATUS = "taskStatus";
    public static final String WAITING_REASON = "waitingReason";
}
