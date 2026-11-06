package com.nova.interview.domain.model;

/** 简历解析流水线状态。 */
public enum ResumeStatus {
    /** 已上传，等待解析。 */
    PENDING,
    /** 解析中（持有租约）。进程崩溃后由 {@code recoverExpired} 回收。 */
    PARSING,
    /** 抽模块与小点中：原文已解析，正在做深度分析与批注。 */
    ANALYZING,
    /** 解析完成，已有结构化画像与批注。 */
    PARSED,
    /** 重试耗尽仍失败。 */
    FAILED
}
