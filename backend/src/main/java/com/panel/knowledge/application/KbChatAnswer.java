package com.panel.knowledge.application;

import java.util.List;

public record KbChatAnswer(
        Long sessionId,
        Long messageId,
        String content,
        List<KbChatCitation> citations,
        int latencyMs) {
}
