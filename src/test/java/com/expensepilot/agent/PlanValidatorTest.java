package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void acceptsPlanMatchingProductionGraph() {
        assertDoesNotThrow(() -> validator.validate(validPlan()));
    }

    @Test
    void rejectsPlannerChangingProductionTopology() {
        PlanOutput valid = validPlan();
        List<PlannedTask> tasks = new ArrayList<>(valid.tasks());

        int policyIndex = indexOf(tasks, StepType.CHECK_POLICY);
        PlannedTask old = tasks.get(policyIndex);

        tasks.set(policyIndex, new PlannedTask(
                old.stepKey(),
                old.stepType(),
                List.of("parse"),
                old.input(),
                old.sideEffect()
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(new PlanOutput(
                        valid.goal(),
                        valid.tripScope(),
                        tasks,
                        false,
                        List.of(),
                        valid.planSummary()
                ))
        );
    }

    @Test
    void rejectsRuntimeBranchInsideMainDag() {
        PlanOutput valid = validPlan();
        List<PlannedTask> tasks = new ArrayList<>(valid.tasks());
        tasks.add(new PlannedTask(
                "supplement",
                StepType.REQUEST_SUPPLEMENT,
                List.of("material"),
                Map.of(),
                false
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(new PlanOutput(
                        valid.goal(),
                        valid.tripScope(),
                        tasks,
                        false,
                        List.of(),
                        valid.planSummary()
                ))
        );
    }

    @Test
    void requiresQuestionWhenClarificationIsNeeded() {
        PlanOutput valid = validPlan();

        PlanOutput invalid = new PlanOutput(
                valid.goal(),
                new TripScope(
                        null,
                        null,
                        "",
                        List.of("用户只说了帮我报销"),
                        List.of("startDate", "endDate", "city")
                ),
                valid.tasks(),
                true,
                List.of(),
                valid.planSummary()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(invalid)
        );
    }

    private PlanOutput validPlan() {
        List<PlannedTask> tasks = List.of(
                task("range", StepType.RESOLVE_TRIP_RANGE, List.of(), false),
                task("email", StepType.SEARCH_EMAIL, List.of("range"), false),
                task("drive", StepType.SEARCH_DRIVE, List.of("range"), false),
                task("travel", StepType.QUERY_TRAVEL, List.of("range"), false),
                task(
                        "parse",
                        StepType.PARSE_INVOICE,
                        List.of("email", "drive", "travel"),
                        false
                ),
                task("material", StepType.CHECK_MATERIAL, List.of("parse"), false),
                task("policy", StepType.CHECK_POLICY, List.of("material"), false),
                task("report", StepType.GENERATE_REPORT, List.of("policy"), false),
                task("submit", StepType.SUBMIT_REPORT, List.of("report"), true),
                task("notify", StepType.SEND_NOTIFICATION, List.of("submit"), true)
        );

        return new PlanOutput(
                "报销上海出差",
                new TripScope(
                        LocalDate.of(2026, 9, 21),
                        LocalDate.of(2026, 9, 27),
                        "上海",
                        List.of("上周去上海出差"),
                        List.of()
                ),
                tasks,
                false,
                List.of(),
                "检索材料、核验政策并提交报销"
        );
    }

    private PlannedTask task(
            String key,
            StepType type,
            List<String> deps,
            boolean sideEffect) {
        return new PlannedTask(
                key,
                type,
                deps,
                Map.of(),
                sideEffect
        );
    }

    private int indexOf(
            List<PlannedTask> tasks,
            StepType type) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).stepType() == type) {
                return i;
            }
        }
        throw new IllegalArgumentException("missing type: " + type);
    }
}
