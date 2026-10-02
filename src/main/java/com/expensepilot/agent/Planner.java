package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 使用 Spring AI Structured Output 的生产 Planner。
 *
 * <p>这里不再返回硬编码计划。ChatClient.call().entity(PlanOutput.class)
 * 会根据 Java record 生成 JSON Schema，并把模型输出直接反序列化为强类型对象。</p>
 *
 * <p>Structured Output 仍然不能替代业务校验，因此每次模型返回后都必须经过
 * PlanValidator。校验失败时把确定性的错误反馈给模型，最多有限次修复。</p>
 */
@Component
public class Planner {

    private static final Set<StepType> SIDE_EFFECT_TYPES =
            Set.of(StepType.SUBMIT_REPORT, StepType.SEND_NOTIFICATION);

    private final ChatClient chatClient;
    private final PlanValidator planValidator;
    private final ZoneId businessZone;
    private final int maxAttempts;

    public Planner(
            ChatClient.Builder chatClientBuilder,
            PlanValidator planValidator,
            @Value("${expensepilot.planner.zone-id:Asia/Shanghai}") String zoneId,
            @Value("${expensepilot.planner.max-attempts:3}") int maxAttempts) {
        this.chatClient = chatClientBuilder.build();
        this.planValidator = planValidator;
        this.businessZone = ZoneId.of(zoneId);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public PlanOutput planOutput(String requestText) {
        String validationFeedback = "无";
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                PlanOutput output = chatClient.prompt()
                        .system(systemPrompt())
                        .user(userPrompt(requestText, validationFeedback))
                        .call()
                        .entity(PlanOutput.class);

                if (output == null) {
                    throw new IllegalStateException("Structured Output 返回 null");
                }

                planValidator.validate(output);
                return output;
            }
            catch (RuntimeException ex) {
                last = ex;
                validationFeedback = ex.getMessage() == null
                        ? ex.getClass().getSimpleName()
                        : ex.getMessage();
            }
        }

        throw new IllegalStateException(
                "Planner 在有限修复次数内仍未生成合法计划",
                last
        );
    }

    /**
     * 兼容旧调用点；Graph-first 主链会直接使用 planOutput。
     */
    public List<PlannedTask> plan(String requestText) {
        return planOutput(requestText).tasks();
    }

    private String systemPrompt() {
        return """
                你是企业费用报销 Agent 的 Planner。
                你的职责只有：理解用户请求并返回符合 Java Schema 的执行计划，不直接调用任何工具。

                规则：
                1. 只能使用后端提供的 StepType 枚举，不得创造新的 stepType。
                2. 查询邮箱、网盘、差旅互不依赖，应在 DAG 中并行。
                3. PARSE_INVOICE 依赖材料查询；CHECK_MATERIAL 依赖解析和差旅结果；
                   CHECK_POLICY 依赖材料检查；GENERATE_REPORT 依赖政策检查；
                   SUBMIT_REPORT 依赖生成报销单；SEND_NOTIFICATION 依赖提交结果。
                4. SUBMIT_REPORT、SEND_NOTIFICATION 必须 sideEffect=true；其他步骤必须 false。
                5. 不得根据常识编造发票、金额、订单号、审批结果或企业政策。
                6. 日期表达如“上周”需要结合我提供的当前业务日期计算。
                7. 若城市/日期等关键字段无法从原文安全确定，在 TripScope.unknownFields 中标记，
                   needsClarification=true，并生成 clarificationQuestions。
                8. planSummary 只输出简短可审计说明，不输出隐含推理过程。
                """;
    }

    private String userPrompt(String requestText, String validationFeedback) {
        LocalDate today = LocalDate.now(businessZone);
        return """
                当前业务日期：%s
                业务时区：%s

                用户请求：
                %s

                上一次结构或业务校验错误：
                %s

                请返回完整 PlanOutput。必须包含完整 DAG；如果上次有错误，请修复后重新生成。
                """.formatted(today, businessZone, requestText, validationFeedback);
    }
}
