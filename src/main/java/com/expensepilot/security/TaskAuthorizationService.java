package com.expensepilot.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 任务级数据权限。
 *
 * <p>API 层先校验 task owner，再允许查询、恢复、澄清和补件。
 * Graph 内保存的 userId 因此来自受信任 JWT，而不是用户请求体。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskAuthorizationService {

    private final JdbcTemplate jdbcTemplate;

    public void assertOwner(long taskId, String currentUserId) {
        String owner = jdbcTemplate.query("""
                select user_id
                  from expense_task
                 where id=?
                """,
                (rs, i) -> rs.getString("user_id"),
                taskId
        ).stream().findFirst().orElseThrow(
                () -> new IllegalArgumentException(
                        "任务不存在: " + taskId)
        );

        if (!owner.equals(currentUserId)) {
            throw new AccessDeniedException(
                    "无权访问该报销任务");
        }
    }
}
