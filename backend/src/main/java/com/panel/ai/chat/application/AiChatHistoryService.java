package com.panel.ai.chat.application;
import com.panel.ai.chat.domain.model.*; import com.panel.shared.security.CurrentActor; import java.util.*;
public interface AiChatHistoryService {
 AiChatSession ensureSession(CurrentActor actor,String key,String title); List<AiChatSession> list(CurrentActor actor); List<AiChatMessage> messages(CurrentActor actor,String key); void append(CurrentActor actor,String key,AiChatMessageRole role,String content); void archive(CurrentActor actor,String key);
}
