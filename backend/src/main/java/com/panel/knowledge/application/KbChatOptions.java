package com.panel.knowledge.application;

/** 前端检索配置：不传时回落到知识库默认值。 */
public record KbChatOptions(Integer topK, String mode, Boolean rerank, Boolean publishedOnly) {

    public static KbChatOptions defaults() {
        return new KbChatOptions(null, null, null, null);
    }

    public int topKOr(int fallback) {
        if (topK == null) {
            return Math.min(20, Math.max(1, fallback));
        }
        return Math.min(20, Math.max(1, topK));
    }

    public String modeOr(String fallback) {
        return mode == null || mode.isBlank() ? fallback : mode.trim();
    }

    public boolean rerankOr(boolean fallback) {
        return rerank == null ? fallback : rerank;
    }

    public boolean publishedOnlyOr(boolean fallback) {
        return publishedOnly == null ? fallback : publishedOnly;
    }
}
