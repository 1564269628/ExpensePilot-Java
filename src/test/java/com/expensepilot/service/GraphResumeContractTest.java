package com.expensepilot.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固化 Spring AI Alibaba Graph 的恢复契约：
 * 真正 checkpoint resume 必须带 HUMAN_FEEDBACK/resume metadata。
 */
class GraphResumeContractTest {

    @Test
    void withResumeAddsResumeMetadata() {
        RunnableConfig config = RunnableConfig.builder()
                .threadId("expense-1001")
                .build();

        RunnableConfig resume = config.withResume();

        assertTrue(
                resume.metadata(
                        RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY
                ).isPresent()
        );
    }
}
