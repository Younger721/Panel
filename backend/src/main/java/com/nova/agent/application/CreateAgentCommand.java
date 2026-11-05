package com.nova.agent.application;

import java.util.List;

public record CreateAgentCommand(
        String name,
        String description,
        String systemPrompt,
        boolean knowledgeEnabled,
        List<String> enabledTools) {
}
