package com.panel.knowledge.application;

import java.time.Instant;

public record KbChatSessionView(Long id, Long kbId, String title, Instant createdAt, Instant updatedAt) {
}
