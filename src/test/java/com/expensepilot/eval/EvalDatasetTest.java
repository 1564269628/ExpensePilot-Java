package com.expensepilot.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 240 条稳定性数据集的结构校验。
 * 这里不依赖真实外部系统，保证数据分布和断言定义本身可复现。
 */
class EvalDatasetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void datasetShouldContain240CasesAndFiveFaultFamilies() throws Exception {
        InputStream in = getClass().getResourceAsStream("/eval/expensepilot-240.jsonl");
        assertNotNull(in);

        List<JsonNode> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) cases.add(mapper.readTree(line));
            }
        }

        assertEquals(240, cases.size());

        Map<String, Long> counts = new TreeMap<>();
        for (JsonNode c : cases) {
            counts.merge(c.get("faultType").asText(), 1L, Long::sum);
            assertTrue(c.hasNonNull("caseId"));
            assertTrue(c.hasNonNull("expectedFinalState"));
            assertTrue(c.has("inject"));
        }

        assertEquals(Set.of(
                "MISSING_INVOICE",
                "DUPLICATE_SUBMIT",
                "POLICY_CONFLICT",
                "TOOL_TIMEOUT",
                "PROCESS_CRASH"
        ), counts.keySet());

        // 240 / 5 = 48，每类样本数一致，便于比较恢复策略。
        counts.values().forEach(v -> assertEquals(48L, v));
    }
}
