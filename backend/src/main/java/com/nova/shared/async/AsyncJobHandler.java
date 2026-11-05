package com.nova.shared.async;

import java.time.Instant;

/**
 * 一类异步任务的处理者。
 *
 * <p>队列（{@link PersistentJobQueue}）只负责「谁领到了这个任务」，
 * 具体「这个任务该谁做」由实现类按 {@code type} 前缀声明，再由
 * {@link AsyncJobDispatcher} 路由。这样新增一类任务（简历解析、报告生成）
 * 不需要改队列，也不会被别的 worker 误当成未知类型直接标完成。</p>
 */
public interface AsyncJobHandler {

    /** 是否认领该任务类型（按前缀判断，形态如 {@code RESUME_PARSE:42}）。 */
    boolean supports(String type);

    /**
     * 处理一个已领取（状态 RUNNING、持有租约）的任务。
     * 实现方负责最终置 COMPLETED / 调用 {@code retry}，异常可向外抛。
     */
    void handle(PersistentJob job, Instant now);
}
