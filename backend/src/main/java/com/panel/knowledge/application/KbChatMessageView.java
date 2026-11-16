package com.panel.knowledge.application;

import java.time.Instant;
import java.util.List;

public record KbChatMessageView(
        Long id,
        String role,
        String content,
        List<KbChatCitation> citations,
        List<KbSearchStage> stages,
        Integer latencyMs,
        Instant createdAt) {
}
