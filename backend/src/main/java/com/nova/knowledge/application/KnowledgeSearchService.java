package com.nova.knowledge.application;

import com.nova.ai.domain.ContextSegment;
import com.nova.ai.harness.AiProgressEvent;
import com.nova.shared.security.CurrentActor;

import java.util.List;
import java.util.function.Consumer;

public interface KnowledgeSearchService {
    List<ContextSegment> search(CurrentActor actor, String query);

    default List<ContextSegment> search(CurrentActor actor, String query,
                                        Consumer<AiProgressEvent> progress) {
        return search(actor, query);
    }
}
