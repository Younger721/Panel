package com.nova.shared.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 异步任务调度器：从队列领取一个任务，交给声明了该类型的 {@link AsyncJobHandler}。
 *
 * <p>调度有两路触发，指向同一个 {@link #pollOnce()}：</p>
 * <ul>
 *   <li>Kafka 唤醒消息（毫秒级，正常路径）</li>
 *   <li>{@code @Scheduled} 轮询（兜底，Kafka 不可用时退化为 ≤5 秒延迟）</li>
 * </ul>
 *
 * <p>这样多条业务线共用一张 {@code async_job} 表和一套租约/重试语义，
 * 不会出现「A 业务的 worker 把 B 业务的任务当未知类型标记完成」。</p>
 */
@Component
public class AsyncJobDispatcher {

    private final PersistentJobQueue jobs;
    private final List<AsyncJobHandler> handlers;
    private final Clock clock;
    private final Duration lease;

    public AsyncJobDispatcher(
            PersistentJobQueue jobs,
            List<AsyncJobHandler> handlers,
            Clock clock,
            @Value("${nova.job-queue.lease-minutes:5}") long leaseMinutes) {
        this.jobs = jobs;
        this.handlers = handlers;
        this.clock = clock;
        this.lease = Duration.ofMinutes(Math.max(1, leaseMinutes));
    }

    @Scheduled(fixedDelayString = "${nova.knowledge.ingest-delay-ms:5000}")
    @Transactional
    public void pollOnce() {
        Instant now = clock.instant();
        jobs.recoverExpired(now);
        jobs.claimNext(now, lease).ifPresent(job -> {
            AsyncJobHandler handler = handlers.stream()
                    .filter(candidate -> job.type() != null && candidate.supports(job.type()))
                    .findFirst()
                    .orElse(null);
            if (handler == null) {
                // 没有任何 handler 认领：视为无意义任务，标记完成避免无限重试
                jobs.complete(job.id());
                return;
            }
            handler.handle(job, now);
        });
    }
}
