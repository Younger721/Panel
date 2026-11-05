package com.nova.agent.application;

import com.nova.ai.application.AiChatView;
import com.nova.shared.security.CurrentActor;

public interface AgentChatService {
    AiChatView chat(CurrentActor actor, Long agentId, String sessionId, String question);
}
