package com.panel.knowledge.application;

import java.math.BigDecimal;

/** 部分更新：null 表示保持原值。 */
public record UpdateKnowledgeBaseCommand(
        String name,
        String description,
        String visibility,
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer topK,
        Boolean rerankEnabled,
        BigDecimal scoreThreshold) {
}
