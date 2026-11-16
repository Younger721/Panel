package com.panel.knowledge.application;

import com.panel.ai.domain.ContextSegment;
import com.panel.ai.harness.AiProgressEvent;
import com.panel.shared.security.CurrentActor;

import java.util.List;
import java.util.function.Consumer;

public interface KnowledgeSearchService {
    List<ContextSegment> search(CurrentActor actor, String query);

    default List<ContextSegment> search(CurrentActor actor, String query,
                                        Consumer<AiProgressEvent> progress) {
        return search(actor, query);
    }
}
