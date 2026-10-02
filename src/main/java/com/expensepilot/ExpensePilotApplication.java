package com.expensepilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ExpensePilot 主启动类。
 *
 * <p>项目刻意把“LLM 规划”和“可靠执行”拆开：
 * LLM 负责生成和修正计划，Java 服务负责状态、并发、幂等、一致性和恢复。</p>
 */
@SpringBootApplication
public class ExpensePilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExpensePilotApplication.class, args);
    }
}
