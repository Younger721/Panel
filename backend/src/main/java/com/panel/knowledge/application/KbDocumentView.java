package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbDocument;

import java.time.Instant;

public record KbDocumentView(
        Long id,
        Long kbId,
        String fileName,
        String contentType,
        long sizeBytes,
        String status,
        String errorMessage,
        int chunkCount,
        int tokenCount,
        boolean published,
        String indexGeneration,
        Instant createdAt,
        Instant updatedAt,
        Instant indexedAt) {

    public static KbDocumentView from(KbDocument document) {
        return new KbDocumentView(
                document.getId(),
                document.kbId(),
                document.fileName(),
                document.contentType(),
                document.sizeBytes(),
                document.status().name(),
                document.errorMessage(),
                document.chunkCount(),
                document.tokenCount(),
                document.published(),
                document.indexGeneration(),
                document.createdAt(),
                document.updatedAt(),
                document.indexedAt());
    }
}
