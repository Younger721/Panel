package com.panel.ai.application;

import com.panel.shared.security.CurrentActor;

import java.util.List;

public interface AiMemoryService {
    void write(CurrentActor actor, AiMemoryType type, String key, String value);

    default void write(CurrentActor actor, String key, String value) {
        write(actor, AiMemoryType.IMPORTANT_FACT, key, value);
    }

    List<AiMemoryEntry> list(CurrentActor actor);

    default List<AiMemoryEntry> context(CurrentActor actor) {
        return list(actor);
    }

    void delete(CurrentActor actor, AiMemoryType type, String key);

    default void delete(CurrentActor actor, String key) {
        delete(actor, AiMemoryType.IMPORTANT_FACT, key);
    }
}
