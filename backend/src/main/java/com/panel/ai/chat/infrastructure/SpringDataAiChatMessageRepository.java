package com.panel.ai.chat.infrastructure;
import com.panel.ai.chat.domain.model.*; import org.springframework.data.jpa.repository.JpaRepository; import java.util.*;
public interface SpringDataAiChatMessageRepository extends JpaRepository<AiChatMessage,Long>{ List<AiChatMessage> findTop40BySessionOrderBySequenceDesc(AiChatSession session); Optional<AiChatMessage> findTopBySessionOrderBySequenceDesc(AiChatSession session); }
