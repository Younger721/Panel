package com.panel.knowledge.domain.model;

/** 知识库的业务类型：决定默认切片策略、默认可见性和检索用途。 */
public enum KbKind {
    QUESTION_BANK,
    TECH_DOC,
    JOB_KNOWLEDGE,
    CUSTOM;

    public static KbKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("知识库类型不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的知识库类型：" + raw);
        }
    }
}
