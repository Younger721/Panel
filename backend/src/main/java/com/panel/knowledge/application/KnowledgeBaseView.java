package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KnowledgeBase;

import java.math.BigDecimal;
import java.time.Instant;

public record KnowledgeBaseView(
        Long id,
        String code,
        String name,
        String description,
        String kind,
        String visibility,
        String chunkStrategy,
        int chunkSize,
        int chunkOverlap,
        int topK,
        boolean rerankEnabled,
        BigDecimal scoreThreshold,
        boolean builtin,
        long documentCount,
        long chunkCount,
        Instant createdAt,
        Instant updatedAt) {

    public static KnowledgeBaseView from(KnowledgeBase base) {
        return from(base, 0L, 0L);
    }

    public static KnowledgeBaseView from(KnowledgeBase base, long documentCount, long chunkCount) {
        return new KnowledgeBaseView(
                base.getId(),
                base.code(),
                base.name(),
                base.description(),
                base.kind().name(),
                base.visibility().name(),
                base.chunkStrategy().name(),
                base.chunkSize(),
                base.chunkOverlap(),
                base.topK(),
                base.rerankEnabled(),
                base.scoreThreshold(),
                base.isBuiltin(),
                documentCount,
                chunkCount,
                base.createdAt(),
                base.updatedAt());
    }
}
