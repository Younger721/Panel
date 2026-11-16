package com.panel.knowledge.domain.model;

/** 知识库可见性：INTERNAL 仅运营/面试官可见，PUBLIC 候选人也可见。 */
public enum KbVisibility {
    PUBLIC,
    INTERNAL;

    public static KbVisibility parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("可见性不能为空");
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的可见性：" + raw);
        }
    }
}
