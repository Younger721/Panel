package com.nova.interview.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 一场模拟面试。
 *
 * <p>只存"编排状态"和两块 JSON：{@code plan_json}（Planner 产出的考察点矩阵）、
 * {@code report_json}（ReportAgent 产出的报告）。中间过程在 {@link InterviewTurn} 里。</p>
 */
@Entity
@Table(name = "interview_session")
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long resumeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InterviewStatus status = InterviewStatus.PLANNED;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String planJson;

    @Column(nullable = false)
    private int turnBudget;

    /** 已出的题数（含当前未作答的那道）。 */
    @Column(nullable = false)
    private int currentTurn;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String reportJson;

    private Integer overallScore;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected InterviewSession() {
    }

    public static InterviewSession create(Long resumeId, int turnBudget, String planJson) {
        InterviewSession session = new InterviewSession();
        session.resumeId = resumeId;
        session.turnBudget = turnBudget;
        session.planJson = planJson;
        session.status = InterviewStatus.PLANNED;
        return session;
    }

    public void markInProgress(int currentTurn) {
        this.status = InterviewStatus.IN_PROGRESS;
        this.currentTurn = currentTurn;
        touch();
    }

    public void markCompleted(String reportJson, Integer overallScore) {
        this.status = InterviewStatus.COMPLETED;
        this.reportJson = reportJson;
        this.overallScore = overallScore;
        touch();
    }

    public void markFailed() {
        this.status = InterviewStatus.FAILED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public String getPlanJson() {
        return planJson;
    }

    public int getTurnBudget() {
        return turnBudget;
    }

    public int getCurrentTurn() {
        return currentTurn;
    }

    public String getReportJson() {
        return reportJson;
    }

    public Integer getOverallScore() {
        return overallScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
