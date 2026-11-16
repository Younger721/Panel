package com.panel.shared.async;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
 *
 * <p><b>事务边界：</b>只有"领取任务"这一步在事务里。handler 干活（解析文档、调模型）
 * 必须跑在事务外——否则 handler 内部抛异常被自己 catch 住之后，外层事务早已被标成
 * rollback-only，提交时抛 {@code UnexpectedRollbackException}，一个坏任务就能让整轮调度失败，
 * 甚至把异常甩到 {@code ScheduledThreadPoolExecutor} 的顶层。</p>
 */
@Component
public class AsyncJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobDispatcher.class);

    private final PersistentJobQueue jobs;
    private final List<AsyncJobHandler> handlers;
    private final Clock clock;
    private final Duration lease;
    private final TransactionTemplate tx;
    /**
     * 干活线程池。
     *
     * <p>以前 handler 直接在 {@code @Scheduled} 线程里跑：一次简历解析要 40-60 秒，
     * 这段时间里队列上所有任务（知识入库、其他用户的简历）全部排队等着，
     * 而 {@code @Scheduled} 默认只有一个线程，连定时清理都插不进去。
     * 现在只把"领取任务"留在调度线程，模型调用丢给虚拟线程。</p>
     */
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** 单轮最多领几个：并发太高会同时打满模型配额。 */
    private final int concurrency;

    public AsyncJobDispatcher(
            PersistentJobQueue jobs,
            List<AsyncJobHandler> handlers,
            Clock clock,
            @Value("${panel.job-queue.lease-minutes:5}") long leaseMinutes,
            @Value("${panel.job-queue.concurrency:2}") int concurrency,
            PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.handlers = handlers;
        this.clock = clock;
        this.lease = Duration.ofMinutes(Math.max(1, leaseMinutes));
        this.concurrency = Math.max(1, Math.min(8, concurrency));
        this.tx = new TransactionTemplate(txManager);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${panel.knowledge.ingest-delay-ms:5000}")
    public void pollOnce() {
        Instant now = clock.instant();
        for (int round = 0; round < concurrency; round += 1) {
            PersistentJob claimed;
            try {
                jobs.recoverExpired(now);
                claimed = tx.execute(status -> jobs.claimNext(now, lease).orElse(null));
            } catch (RuntimeException ex) {
                // 一轮失败不影响下一轮；真正的原因由 handler 自己记录
                log.warn("异步调度本轮失败，{} 秒后再试：{}", 5, rootMessage(ex));
                return;
            }
            if (claimed == null) {
                return;
            }
            workers.execute(() -> dispatch(claimed, now));
        }
    }

    private void dispatch(PersistentJob job, Instant now) {
        AsyncJobHandler handler = handlers.stream()
                .filter(candidate -> job.type() != null && candidate.supports(job.type()))
                .findFirst()
                .orElse(null);
        if (handler == null) {
            // 没有任何 handler 认领：视为无意义任务，标记完成避免无限重试
            safeComplete(job);
            return;
        }
        try {
            handler.handle(job, now);
        } catch (RuntimeException ex) {
            log.warn("异步任务执行失败 type={} id={}：{}", job.type(), job.id(), rootMessage(ex));
            safeRetry(job, ex, now);
        }
    }

    private void safeComplete(PersistentJob job) {
        try {
            jobs.complete(job.id());
        } catch (RuntimeException ex) {
            log.warn("任务收尾失败 id={}：{}", job.id(), rootMessage(ex));
        }
    }

    private void safeRetry(PersistentJob job, RuntimeException failure, Instant now) {
        try {
            jobs.retry(job.id(), failure.getMessage(), now);
        } catch (RuntimeException ex) {
            log.warn("任务重试入队失败 id={}：{}", job.id(), rootMessage(ex));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
