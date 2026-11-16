package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;

/**
 * 一道题的作答记录：题面展示 → 展开答案 → 自评结果。
 *
 * <p>{@code categoryId} 是抽题那一刻定格的知识点，不跟随目录调整而变化——
 * 否则题目被重新归类后，历史错题本的归属会漂。</p>
 */
@Entity
@Table(name = "kb_quiz_attempt",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_quiz_attempt_slot",
                columnNames = {"session_id", "ordinal"}),
        indexes = {
                @Index(name = "idx_kb_quiz_attempt_user_q", columnList = "user_id,question_id,created_at"),
                @Index(name = "idx_kb_quiz_attempt_session", columnList = "session_id"),
                @Index(name = "idx_kb_quiz_attempt_cat", columnList = "user_id,category_id,result")
        })
public class KbQuizAttempt extends BaseEntity {

    /** 自评结果：对 / 错 / 不确定（不确定进错题本，但不计入正确率）。 */
    public enum Result {
        CORRECT,
        WRONG,
        UNSURE,
        SKIPPED;

        public static Result parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("自评结果不能为空");
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("不支持的自评结果：" + raw);
            }
        }

        public boolean countsAsCorrect() {
            return this == CORRECT;
        }
    }

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(length = 16)
    private String result;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "shown_at", nullable = false)
    private Instant shownAt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbQuizAttempt() {
    }

    private KbQuizAttempt(Long sessionId, Long userId, Long kbId, Long questionId, int ordinal,
                          Long categoryId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.kbId = kbId;
        this.questionId = questionId;
        this.ordinal = ordinal;
        this.categoryId = categoryId;
        this.shownAt = Instant.now();
        this.createdAt = this.shownAt;
    }

    public static KbQuizAttempt create(Long sessionId, Long userId, Long kbId, Long questionId,
                                       int ordinal, Long categoryId) {
        return new KbQuizAttempt(sessionId, userId, kbId, questionId, ordinal, categoryId);
    }

    public void reveal() {
        if (this.revealedAt == null) {
            this.revealedAt = Instant.now();
        }
    }

    public void grade(Result result) {
        this.result = result.name();
        this.answeredAt = Instant.now();
        if (this.revealedAt == null) {
            this.revealedAt = this.answeredAt;
        }
    }

    public void reset() {
        this.result = null;
        this.answeredAt = null;
    }

    public boolean answered() {
        return result != null;
    }

    /** 从看到题面到展开答案花了几秒——思考 3 秒就点开，说明没在回忆。 */
    public long thinkingSeconds() {
        if (revealedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(shownAt, revealedAt).toSeconds());
    }

    public Long sessionId() { return sessionId; }
    public Long userId() { return userId; }
    public Long kbId() { return kbId; }
    public Long questionId() { return questionId; }
    public int ordinal() { return ordinal; }
    public String result() { return result; }
    public Long categoryId() { return categoryId; }
    public Instant shownAt() { return shownAt; }
    public Instant revealedAt() { return revealedAt; }
    public Instant answeredAt() { return answeredAt; }
    public Instant createdAt() { return createdAt; }
}
