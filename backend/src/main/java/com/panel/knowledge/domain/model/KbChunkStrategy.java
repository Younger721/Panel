package com.panel.knowledge.domain.model;

/**
 * 知识库级别的切片策略。
 *
 * <p>与旧的 {@code ChunkStrategy}（PROCEDURE/RECORD/… 健身域）分开：这里是面试知识库的
 * 五种：题库一题一片、技术文档按标题、JD 按段落、FAQ 按问答、其余按长度。</p>
 */
public enum KbChunkStrategy {
    QA,
    TECH_DOC,
    JD,
    FAQ,
    GENERAL;

    public static KbChunkStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("切片策略不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的切片策略：" + raw);
        }
    }

    public static KbChunkStrategy defaultFor(KbKind kind) {
        return switch (kind) {
            case QUESTION_BANK -> QA;
            case TECH_DOC -> TECH_DOC;
            case JOB_KNOWLEDGE -> JD;
            case CUSTOM -> GENERAL;
        };
    }
}
