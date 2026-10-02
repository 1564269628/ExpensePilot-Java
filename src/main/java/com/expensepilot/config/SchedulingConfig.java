package com.expensepilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用 Outbox 发布与 Recovery Worker 定时扫描。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
