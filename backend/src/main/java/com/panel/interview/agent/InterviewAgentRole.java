package com.panel.interview.agent;

/**
 * 面试编排里会产生模型调用的角色。
 *
 * <p>把"角色 → 记账 feature → 能不能用便宜模型"绑在一处：以后加一个 agent，
 * 必须在这里登记，否则用量账上会出现没有归属的调用。</p>
 *
 * <p>只有"整理型"任务（复核、报告）允许换便宜模型；出题和评分直接决定面试质量，
 * 固定用主模型——省这点钱不值得冒降质的风险。</p>
 */
public enum InterviewAgentRole {

    QUESTION("interview.question", false),
    TECHNICAL_JUDGE("interview.judge", false),
    EXPRESSION_JUDGE("interview.expression", false),
    REVIEW("interview.review", true),
    REPORT("interview.report", true),
    JOB_FIT("interview.fit", false);

    private final String feature;
    private final boolean cheapModelAllowed;

    InterviewAgentRole(String feature, boolean cheapModelAllowed) {
        this.feature = feature;
        this.cheapModelAllowed = cheapModelAllowed;
    }

    /** 用量表里的 feature 字段，用来回答"一场面试的钱花在哪个 agent 上"。 */
    public String feature() {
        return feature;
    }

    public boolean cheapModelAllowed() {
        return cheapModelAllowed;
    }
}
