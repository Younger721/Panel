package com.panel.shared.async.kafka;

import com.panel.shared.async.JdbcJobStateStore;
import com.panel.shared.async.JobStatus;
import com.panel.shared.async.PersistentJob;
import com.panel.shared.async.PersistentJobQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Kafka 版队列：<b>DB 权威 + Kafka 唤醒</b>。
 *
 * <p>不要把 Kafka 当任务存储。状态机（PENDING/RUNNING/COMPLETED/FAILED、attempts、
 * 租约、退避）始终在 {@code async_job} 表里，Kafka 只负责「有新任务了，快去看一眼」。</p>
 *
 * <p>这样做的三个后果，都是刻意接受的：</p>
 * <ol>
 *   <li>消息重复：消费端按 jobId 调 {@code claimNext}，只有 PENDING 才能抢到租约，
 *       重复消息自然空转，无需去重表。</li>
 *   <li>消息丢失：保留 {@code @Scheduled} 轮询兜底，Kafka 挂了只是延迟变大，不会丢任务。</li>
 *   <li>进度查询：读 DB，不读 consumer offset，所以多实例、重放、重启都不会让进度错乱。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "panel.job-queue", havingValue = "kafka")
public class KafkaPersistentJobQueue implements PersistentJobQueue {

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final long DEFAULT_RETRY_BASE_SECONDS = 30L;

    private final JdbcJobStateStore store;
    private final KafkaJobPublisher publisher;

    public KafkaPersistentJobQueue(JdbcJobStateStore store, KafkaJobPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public Long enqueue(String type, Long tenantId) {
        Long jobId = store.enqueue(type, tenantId);
        // 只有真正进入 PENDING 才有必要唤醒；命中已存在的活动任务时也发一次，
        // 属于无害冗余（消费端抢不到租约就退出）。
        publisher.wake(jobId, tenantId);
        return jobId;
    }

    @Override
    public Optional<PersistentJob> claimNext(Instant now, Duration lease) {
        return store.claimNext(now, lease);
    }

    @Override
    public void advance(Long id) {
        store.advance(id);
    }

    /**
     * 重试不立刻发唤醒：{@code next_attempt_at} 被推到未来，马上唤醒也会被
     * {@code claimNext} 的 {@code next_attempt_at<=now} 条件拒掉，交给轮询即可。
     */
    @Override
    public void retry(Long id, String error, Instant now) {
        store.retry(id, error, now, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BASE_SECONDS);
        PersistentJob job = store.find(id);
        if (job.status() == JobStatus.FAILED) {
            publisher.deadLetter(id, job.type(), error);
        }
    }

    @Override
    public void complete(Long id) {
        store.complete(id);
    }

    @Override
    public void recoverExpired(Instant now) {
        store.recoverExpired(now);
    }

    @Override
    public PersistentJob find(Long id) {
        return store.find(id);
    }
}
