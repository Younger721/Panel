package com.panel.knowledge.domain.model;

import com.panel.shared.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 面试信号回填台账：一条 = 一条 {@code interview_turn} 已经折算进能力值。
 *
 * <p>{@code turn_id} 唯一，所以回填接口可以反复调用而不会重复计分；
 * 反过来也能回答"我这个知识点为什么是 -1.2"，顺着台账就能翻到具体哪几轮面试。</p>
 */
@Entity
@Table(name = "kb_skill_interview_import",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_skill_interview_import_turn",
                columnNames = {"turn_id"}))
public class KbSkillInterviewImport extends BaseEntity {

    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(length = 64)
    private String skill;

    @Column(nullable = false, length = 16)
    private String observation;

    private Integer score;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KbSkillInterviewImport() {
    }

    private KbSkillInterviewImport(Long turnId, Long userId, Long kbId, Long categoryId,
                                   String skill, String observation, Integer score, Instant now) {
        this.turnId = turnId;
        this.userId = userId;
        this.kbId = kbId;
        this.categoryId = categoryId;
        this.skill = skill;
        this.observation = observation;
        this.score = score;
        this.appliedAt = now;
        this.createdAt = now;
    }

    public static KbSkillInterviewImport of(Long turnId, Long userId, Long kbId, Long categoryId,
                                            String skill, String observation, Integer score, Instant now) {
        return new KbSkillInterviewImport(turnId, userId, kbId, categoryId, skill, observation, score, now);
    }

    public Long turnId() { return turnId; }
    public Long userId() { return userId; }
    public Long kbId() { return kbId; }
    public Long categoryId() { return categoryId; }
    public String skill() { return skill; }
    public String observation() { return observation; }
    public Integer score() { return score; }
    public Instant appliedAt() { return appliedAt; }
}
