package com.panel.ai.application; import com.panel.shared.security.CurrentActor; public interface AiChatService { AiChatView chat(CurrentActor actor,String sessionId,String question); }
