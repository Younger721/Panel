package com.panel.interview.domain.model;

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

    /**
     * 乐观锁版本号。
     *
     * <p>同一场面试可能被两条路径同时推进：用户在页面上作答、定时任务在收尾超时会话。
     * 没有版本号时后者会把前者的 {@code status}/{@code currentTurn} 静默覆盖回去
     * （例如已经 COMPLETED 的会话被改回 IN_PROGRESS）。</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(nullable = false)
    private Long resumeId;

    /**
     * 归属用户（V11 迁移加的列）。NULL = 迁移前的历史数据，只有平台管理员可见。
     *
     * <p>会话里存着简历摘要、逐题回答与评分报告，泄露面比简历本身更大，
     * 所以所有按 id 的读写都必须先过归属校验。</p>
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private InterviewStatus status = InterviewStatus.PLANNED;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String planJson;

    @Column(nullable = false)
    private int turnBudget;

    /** 主问题数量（用户设置页的 N）。 */
    @Column(nullable = false)
    private int questionCount;

    /** 每个主问题最多追问几次（用户设置页的 M），0 表示不追问。 */
    @Column(nullable = false)
    private int followUpLimit;

    /** 面试官人设：决定音色、TTS 语气和追问阈值。 */
    @Column(nullable = false, length = 24)
    private String persona = InterviewerPersona.SERIOUS.name();

    /** 本场的追问阈值：低于它才追问。人设不同值不同（压力面 85，更爱追问）。 */
    @Column(nullable = false)
    private int followUpThreshold = 70;

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

    public static InterviewSession create(Long resumeId, Long ownerUserId, int questionCount, int followUpLimit,
                                          String planJson, InterviewerPersona persona) {
        InterviewerPersona effective = persona == null ? InterviewerPersona.SERIOUS : persona;
        InterviewSession session = new InterviewSession();
        session.resumeId = resumeId;
        session.ownerUserId = ownerUserId;
        session.questionCount = questionCount;
        session.followUpLimit = followUpLimit;
        session.persona = effective.name();
        session.followUpThreshold = effective.followUpThreshold();
        // 上限 = N 个主问题，每个最多追问 M 次；实际轮次通常更少
        session.turnBudget = questionCount * (1 + followUpLimit);
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

    /**
     * 用户主动放弃 / 超时未完成：把会话从"进行中"里摘出来。
     *
     * <p>没有这个方法之前，关掉浏览器就等于永久 {@code IN_PROGRESS}：
     * 历史页永远显示"进行中"，报告接口永远 409，ASR 还能继续给它签票据。</p>
     */
    public void markAbandoned() {
        this.status = InterviewStatus.ABANDONED;
        touch();
    }

    /** 已经收尾（完成/放弃/失败）的会话不允许再推进。 */
    public boolean isSettled() {
        return status == InterviewStatus.COMPLETED
                || status == InterviewStatus.ABANDONED
                || status == InterviewStatus.FAILED;
    }

    /** 长时间没有任何推进，交给定时任务收尾。 */
    public boolean isStale(Instant idleBefore) {
        return status == InterviewStatus.IN_PROGRESS && updatedAt.isBefore(idleBefore);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
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

    public int getQuestionCount() {
        return questionCount;
    }

    public int getFollowUpLimit() {
        return followUpLimit;
    }

    public String getPersona() {
        return persona;
    }

    /** 老数据的列值是 0，退回到全局默认，避免「答完从不追问」。 */
    public int getFollowUpThreshold() {
        return followUpThreshold > 0 ? followUpThreshold : 70;
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
