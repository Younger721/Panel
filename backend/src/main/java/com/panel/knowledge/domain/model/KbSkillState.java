package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 一个用户在一个知识点上的能力值（简化的 Elo/IRT 单参数版）。
 *
 * <p>为什么不是"错题数"：错题数只会一直涨，答对不会抵消；而 {@code theta} 会随答对回升，
 * 又随长时间不练衰减，更接近"现在到底会不会"。</p>
 */
@Entity
@Table(name = "kb_skill_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_skill_state",
                columnNames = {"user_id", "kb_id", "category_id"}),
        indexes = @Index(name = "idx_kb_skill_state_kb", columnList = "kb_id,user_id,theta"))
public class KbSkillState extends BaseEntity {

    /** 信号来源：刷题 / 面试（面试权重更高，见 {@code KbSkillModel.INTERVIEW_WEIGHT}）。 */
    public enum Source {
        QUIZ,
        INTERVIEW
    }

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private double theta;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "wrong_count", nullable = false)
    private int wrongCount;

    @Column(name = "unsure_count", nullable = false)
    private int unsureCount;

    @Column(name = "interview_count", nullable = false)
    private int interviewCount;

    @Column(name = "last_result", length = 16)
    private String lastResult;

    @Column(name = "last_source", nullable = false, length = 16)
    private String lastSource = Source.QUIZ.name();

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KbSkillState() {
    }

    private KbSkillState(Long userId, Long kbId, Long categoryId, Instant now) {
        this.userId = userId;
        this.kbId = kbId;
        this.categoryId = categoryId;
        this.lastSeenAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static KbSkillState create(Long userId, Long kbId, Long categoryId) {
        return new KbSkillState(userId, kbId, categoryId, Instant.now());
    }

    /** 记一次观测：能力值按 {@code delta} 移动，计数与"最近一次"同步更新。 */
    public void apply(double newTheta, String result, Source source, Instant now) {
        this.theta = newTheta;
        this.attempts += 1;
        switch (result) {
            case "CORRECT" -> this.correctCount += 1;
            case "WRONG" -> this.wrongCount += 1;
            case "UNSURE" -> this.unsureCount += 1;
            default -> { }
        }
        if (source == Source.INTERVIEW) {
            this.interviewCount += 1;
        }
        this.lastResult = result;
        this.lastSource = source.name();
        this.lastSeenAt = now;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public Long userId() { return userId; }
    public Long kbId() { return kbId; }
    public Long categoryId() { return categoryId; }
    public double theta() { return theta; }
    public int attempts() { return attempts; }
    public int correctCount() { return correctCount; }
    public int wrongCount() { return wrongCount; }
    public int unsureCount() { return unsureCount; }
    public int interviewCount() { return interviewCount; }
    public String lastResult() { return lastResult; }
    public String lastSource() { return lastSource; }
    public Instant lastSeenAt() { return lastSeenAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
