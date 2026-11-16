package com.panel.knowledge.application;

import java.math.BigDecimal;

public record CreateKnowledgeBaseCommand(
        String code,
        String name,
        String description,
        String kind,
        String visibility,
        String chunkStrategy,
        Integer chunkSize,
        Integer chunkOverlap,
        Integer topK,
        Boolean rerankEnabled,
        BigDecimal scoreThreshold) {
}
