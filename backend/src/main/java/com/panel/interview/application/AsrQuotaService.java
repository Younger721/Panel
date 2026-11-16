package com.panel.interview.application;

import com.panel.ai.infrastructure.bailian.AsrProperties;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音输入的配额与并发闸门。
 *
 * <p>放在内存里，和 {@code TenantRateLimitFilter} 的做法保持一致：单实例部署够用，
 * 也不给语音链路增加一个 Redis 依赖（Redis 挂了不该让语音一起挂）。
 * 要多实例部署时，把 {@code daily} 换成 Redis 计数器、把 {@code active} 换成带 TTL 的 SETNX 即可。</p>
 */
@Service
public class AsrQuotaService {

    private final AsrProperties properties;
    private final ConcurrentHashMap<Long, AtomicInteger> daily = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> active = new ConcurrentHashMap<>();
    private final AtomicReference<LocalDate> currentDay = new AtomicReference<>(LocalDate.now());

    public AsrQuotaService(AsrProperties properties) {
        this.properties = properties;
    }

    /**
     * 预占一次识别次数。在签发 ticket 时就扣，避免「只开连接不发声」把并发位占满。
     */
    public void reserveDaily(Long userId) {
        rolloverIfNeeded();
        AtomicInteger counter = daily.computeIfAbsent(userId, key -> new AtomicInteger());
        int used = counter.incrementAndGet();
        if (used > properties.getDailyQuotaPerUser()) {
            counter.decrementAndGet();
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    /**
     * 退还一次预占。
     *
     * <p>只用于<b>系统侧失败</b>：握手被拒、并发位抢不到、上游百炼连不上。
     * 这些情况下用户一句话都没说出去，白扣一次配额是纯损失，而且会让用户
     * 在"配额用完"和"页面报错"之间反复循环却查不出原因。</p>
     *
     * <p>正常的「签了票没用」不退还——否则反复签发再放弃就等于绕过每日限额。</p>
     */
    public void refundDaily(Long userId) {
        if (userId == null) {
            return;
        }
        rolloverIfNeeded();
        daily.computeIfPresent(userId, (key, value) ->
                value.decrementAndGet() <= 0 ? null : value);
    }

    /** 连接建立时占一个并发位；一个人同时只允许一条。 */
    public boolean acquireConnection(Long userId) {
        AtomicInteger counter = active.computeIfAbsent(userId, key -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= properties.getMaxConnectionsPerUser()) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseConnection(Long userId) {
        if (userId == null) {
            return;
        }
        active.computeIfPresent(userId, (key, value) ->
                value.decrementAndGet() <= 0 ? null : value);
    }

    private void rolloverIfNeeded() {
        LocalDate today = LocalDate.now();
        LocalDate previous = currentDay.get();
        if (!today.equals(previous) && currentDay.compareAndSet(previous, today)) {
            daily.clear();
        }
    }
}
