package com.panel.agent.application;

import com.panel.ai.application.AiChatView;
import com.panel.shared.security.CurrentActor;

public interface AgentChatService {
    AiChatView chat(CurrentActor actor, Long agentId, String sessionId, String question);
}
