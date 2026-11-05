package com.nova.ai.application; import com.nova.shared.security.CurrentActor; public interface AiChatService { AiChatView chat(CurrentActor actor,String sessionId,String question); }
