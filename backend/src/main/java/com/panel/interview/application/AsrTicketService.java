package com.panel.interview.application;

import com.panel.ai.infrastructure.bailian.AsrProperties;
import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.InterviewStatus;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.InterviewTurnRepository;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次性入场券（ticket）。
 *
 * <p>为什么需要它：浏览器的 WebSocket API <b>无法设置 Authorization 头</b>，
 * 而 JWT 塞进 query string 会留在代理和访问日志里。所以先用普通的 REST 请求
 * （带 JWT）换一张 30 秒、只能用一次、绑定用户与会话的券，握手时校验。
 * 券泄露也只能在 30 秒内开一条连接。</p>
 */
@Service
public class AsrTicketService {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong(System.currentTimeMillis());
    private final InterviewSessionRepository sessions;
    private final InterviewTurnRepository turns;
    private final AsrProperties properties;
    private final AsrQuotaService quota;

    public AsrTicketService(InterviewSessionRepository sessions,
                            InterviewTurnRepository turns,
                            AsrProperties properties,
                            AsrQuotaService quota) {
        this.sessions = sessions;
        this.turns = turns;
        this.properties = properties;
        this.quota = quota;
    }

    /** 握手时要用的身份快照：WebSocket 线程里没有请求上下文，只能提前带过去。 */
    public record Ticket(String token, Long sessionId, Long userId, String model,
                         Instant expiresAt, CurrentActor actor) {
    }

    public record Issued(String ticket, String wsUrl, String model, int sampleRate,
                         String format, int expiresInSeconds) {
    }

    public Issued issue(CurrentActor actor, Long sessionId) {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        InterviewSession session = sessions.findById(sessionId)
                // 别人的会话对它来说等于不存在：不能拿别人的 sessionId 换一张语音券，
                // 那等于把别人的作答音频灌进自己的通道
                .filter(found -> ResumeAccessGuard.visible(found.getOwnerUserId(), actor))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 已结束的会话（完成/失败/人工放弃）都不该再签发语音券
        if (session.isSettled()) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        // 没有待作答的题就没必要录音
        if (turns.findPending(sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        quota.reserveDaily(actor.userId());

        String token = newToken();
        Instant expiresAt = Instant.now().plus(TTL);
        tickets.put(token, new Ticket(token, sessionId, actor.userId(),
                properties.getModel(), expiresAt, actor));
        sweepIfNeeded();
        return new Issued(token, "/ws/interview/asr", properties.getModel(),
                properties.getSampleRate(), properties.getFormat(), (int) TTL.toSeconds());
    }

    /** 取走并作废。不是 peek：同一个 ticket 第二次握手必然失败。 */
    public Optional<Ticket> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 惰性清理，避免过期券一直占内存；一分钟最多扫一次。 */
    private void sweepIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastSweepAt.get();
        if (now - last < 60_000 || !lastSweepAt.compareAndSet(last, now)) {
            return;
        }
        Instant cutoff = Instant.now();
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(cutoff));
    }
}
