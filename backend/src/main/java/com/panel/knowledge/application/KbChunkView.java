package com.panel.knowledge.application;

import com.panel.knowledge.domain.model.KbChunk;

public record KbChunkView(
        Long id,
        Long documentId,
        int ordinal,
        String heading,
        Integer pageNo,
        int tokenCount,
        String text) {

    public static KbChunkView from(KbChunk chunk) {
        return new KbChunkView(chunk.getId(), chunk.documentId(), chunk.ordinal(), chunk.heading(),
                chunk.pageNo(), chunk.tokenCount(), chunk.text());
    }
}
