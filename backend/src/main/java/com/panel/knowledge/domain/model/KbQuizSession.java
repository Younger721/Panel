package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一次刷题会话：固定抽 N 道题，逐题展开答案并自评。
 *
 * <p>会话存在的意义是“五题一组”这个节奏：刷新不丢进度、中途退出能续上、
 * 一组做完能给出「这组对了几题 / 错在哪几个知识点」的汇总。</p>
 */
@Entity
@Table(name = "kb_quiz_session",
        indexes = @Index(name = "idx_kb_quiz_session_user", columnList = "user_id,kb_id,started_at"))
public class KbQuizSession extends BaseEntity {

    public static final String RUNNING = "RUNNING";
    public static final String FINISHED = "FINISHED";

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected KbQuizSession() {
    }

    private KbQuizSession(Long userId, Long kbId, int questionCount) {
        if (userId == null || kbId == null) {
            throw new IllegalArgumentException("会话缺少用户或知识库");
        }
        if (questionCount < 1) {
            throw new IllegalArgumentException("抽题数量至少 1 道");
        }
        this.userId = userId;
        this.kbId = kbId;
        this.questionCount = questionCount;
        this.correctCount = 0;
        this.wrongCount = 0;
        this.status = RUNNING;
        this.startedAt = Instant.now();
    }

    public static KbQuizSession start(Long userId, Long kbId, int questionCount) {
        return new KbQuizSession(userId, kbId, questionCount);
    }

    /** 用作答记录回算，避免“先加后错”导致计数漂移。 */
    public void applyCounts(int correct, int wrong) {
        this.correctCount = Math.max(0, correct);
        this.wrongCount = Math.max(0, wrong);
    }

    public void finish() {
        this.status = FINISHED;
        this.finishedAt = Instant.now();
    }

    public boolean running() {
        return RUNNING.equals(status);
    }

    public Long userId() { return userId; }
    public Long kbId() { return kbId; }
    public int questionCount() { return questionCount; }
    public int correctCount() { return correctCount; }
    public int wrongCount() { return wrongCount; }
    public String status() { return status; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
}
