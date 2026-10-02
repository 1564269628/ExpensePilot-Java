package com.expensepilot.coordination;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 多实例任务协调。
 *
 * <p>Redisson Lease 用来减少多个实例同时抢同一任务；数据库 version CAS 再做最终保护。
 * 即使 A 因长 GC 导致 Redis 锁过期、B 已接管，A 恢复后也无法用旧 version 覆盖 B 的状态。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskLeaseService {

    private final RedissonClient redissonClient;
    private final JdbcTemplate jdbcTemplate;

    public boolean tryAcquire(long taskId) {
        RLock lock = redissonClient.getLock("expensepilot:task:" + taskId);
        try {
            return lock.tryLock(0, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release(long taskId) {
        RLock lock = redissonClient.getLock("expensepilot:task:" + taskId);
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }

    /**
     * 使用 version 做 compare-and-set。affectedRows=0 表示当前执行者拿的是过期状态。
     */
    public boolean casStatus(long taskId, int expectedVersion, String status, String currentNode) {
        int affected = jdbcTemplate.update("""
                update expense_task
                set status=?, current_node=?, version=version+1
                where id=? and version=?
                """, status, currentNode, taskId, expectedVersion);
        return affected == 1;
    }
}
