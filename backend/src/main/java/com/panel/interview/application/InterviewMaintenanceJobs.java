package com.panel.interview.application;

import com.panel.interview.domain.model.InterviewSession;
import com.panel.interview.domain.model.Resume;
import com.panel.interview.domain.model.ResumeStatus;
import com.panel.interview.domain.repository.InterviewSessionRepository;
import com.panel.interview.domain.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 面试与简历的"僵尸状态"收尾。
 *
 * <p>两处漏洞的共同点：进程重启、任务被丢弃、用户直接关掉浏览器，都会让一行记录停在
 * 中间态，而中间态没有任何出口——</p>
 * <ul>
 *   <li>会话停在 {@code IN_PROGRESS}：历史页永远显示"进行中"，报告永远 409，语音票据还能签；</li>
 *   <li>简历停在 {@code PARSING/ANALYZING}：保存与重新分析都会被 {@code inFlight} 挡成 409，
 *       用户只能删掉重传。</li>
 * </ul>
 *
 * <p>所以按"空闲时长"兜底：超时的会话判为已放弃，超时的简历判为解析失败并允许重试。</p>
 */
@Component
public class InterviewMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(InterviewMaintenanceJobs.class);

    private final InterviewSessionRepository sessions;
    private final ResumeRepository resumes;
    private final Duration sessionIdleTimeout;
    private final Duration resumeParseTimeout;

    public InterviewMaintenanceJobs(InterviewSessionRepository sessions,
                                    ResumeRepository resumes,
                                    @Value("${panel.interview.idle-timeout-minutes:120}") long sessionIdleMinutes,
                                    @Value("${panel.interview.resume-parse-timeout-minutes:15}") long resumeParseMinutes) {
        this.sessions = sessions;
        this.resumes = resumes;
        this.sessionIdleTimeout = Duration.ofMinutes(Math.max(1, sessionIdleMinutes));
        this.resumeParseTimeout = Duration.ofMinutes(Math.max(1, resumeParseMinutes));
    }

    /**
     * 超时未推进的会话 → ABANDONED。
     *
     * <p>用 {@code updated_at} 而不是 {@code created_at}：一场面试可能答了两小时中间还在推进，
     * 那不算放弃。只有"两小时没人动过"才是关掉页面走人了。</p>
     */
    @Scheduled(fixedDelayString = "${panel.interview.sweep-delay-ms:600000}",
            initialDelayString = "${panel.interview.sweep-initial-delay-ms:120000}")
    public void closeIdleSessions() {
        Instant idleBefore = Instant.now().minus(sessionIdleTimeout);
        List<InterviewSession> stale;
        try {
            stale = sessions.findStaleInProgress(idleBefore);
        } catch (RuntimeException ex) {
            log.warn("Interview sweep query failed: {}", ex.toString());
            return;
        }
        int closed = 0;
        for (InterviewSession session : stale) {
            try {
                // 条件窄更新：这期间用户可能刚好又答了一题，那就不能判成放弃。
                // 只写 status/updated_at，因此也不会和"作答"那条路径的整行更新抢版本号。
                if (sessions.abandonIfIdle(session.getId(), idleBefore) > 0) {
                    closed++;
                }
            } catch (RuntimeException ex) {
                log.warn("Interview sweep failed to close session {}: {}", session.getId(), ex.toString());
            }
        }
        if (closed > 0) {
            log.info("Interview sweep closed {} idle session(s) older than {}", closed, idleBefore);
        }
    }

    /**
     * 卡在解析中/分析中的简历 → FAILED（可重试）。
     *
     * <p>不自动重新投递：卡住的常见原因是这份文件本身让解析器抛错，自动重投只会
     * 每 10 分钟烧一次模型调用。置 FAILED 后 {@code last_error} 会说明原因，
     * 用户点"重新分析"（{@code POST /{id}/reset}）就是一次显式的重试。</p>
     */
    @Scheduled(fixedDelayString = "${panel.interview.resume-sweep-delay-ms:600000}",
            initialDelayString = "${panel.interview.resume-sweep-initial-delay-ms:180000}")
    public void failStuckResumes() {
        Instant stuckBefore = Instant.now().minus(resumeParseTimeout);
        List<Resume> stuck;
        try {
            stuck = resumes.findStuck(stuckBefore);
        } catch (RuntimeException ex) {
            log.warn("Resume sweep query failed: {}", ex.toString());
            return;
        }
        for (Resume resume : stuck) {
            try {
                resumes.updateStatus(resume.getId(), ResumeStatus.FAILED,
                        "解析超时（超过 " + resumeParseTimeout.toMinutes() + " 分钟无进展），可点击重新分析重试");
                log.info("Resume stuck in {} reset to FAILED: id={}", resume.getStatus(), resume.getId());
            } catch (RuntimeException ex) {
                log.warn("Resume sweep failed for {}: {}", resume.getId(), ex.toString());
            }
        }
    }
}
