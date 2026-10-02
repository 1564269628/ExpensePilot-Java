package com.expensepilot.coordination;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 多实例任务执行租约。
 *
 * <p>这里故意不传固定 leaseTime，而使用 tryLock(waitTime, unit)：
 * Redisson 会启用 lock watchdog，只要持锁实例仍存活就自动续期；实例崩溃后，
 * watchdog TTL 到期锁会释放，其他实例才能接管。</p>
 *
 * <p>Redis 锁不是最终一致性边界。业务状态写入仍由 MySQL version CAS 保护。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskLeaseService {

    private final RedissonClient redissonClient;

    public boolean tryAcquire(long taskId) {
        RLock lock = redissonClient.getLock("expensepilot:task:" + taskId);
        try {
            // leaseTime=-1，由 Redisson watchdog 自动续租。
            return lock.tryLock(0, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release(long taskId) {
        RLock lock = redissonClient.getLock("expensepilot:task:" + taskId);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
