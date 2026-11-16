package com.panel.interview.domain.model;

/** 一场模拟面试的生命周期。 */
public enum InterviewStatus {
    /** 考察点矩阵已生成，题目已出第一道。 */
    PLANNED,
    /** 进行中：有未作答的题。 */
    IN_PROGRESS,
    /** 轮次走完，报告已生成。 */
    COMPLETED,
    /** 用户主动放弃，或长时间无推进被定时任务收尾：不再推进、不再签语音票据。 */
    ABANDONED,
    /** 编排失败（模型不可用/JSON 不可解析）。 */
    FAILED
}
