package com.nova.ai.chat.application;
import com.nova.ai.chat.domain.model.*; import com.nova.shared.security.CurrentActor; import java.util.*;
public interface AiChatHistoryService {
 AiChatSession ensureSession(CurrentActor actor,String key,String title); List<AiChatSession> list(CurrentActor actor); List<AiChatMessage> messages(CurrentActor actor,String key); void append(CurrentActor actor,String key,AiChatMessageRole role,String content); void archive(CurrentActor actor,String key);
}
