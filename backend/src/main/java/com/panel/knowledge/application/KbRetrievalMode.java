package com.panel.knowledge.application;

/** K2 检索模式：测试面板用来对比词面/向量/混合三条链路。 */
public enum KbRetrievalMode {
    HYBRID,
    KEYWORD,
    VECTOR;

    public static KbRetrievalMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的检索模式：" + raw);
        }
    }
}
