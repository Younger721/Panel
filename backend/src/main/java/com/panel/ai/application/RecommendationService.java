package com.panel.ai.application;

import com.panel.shared.security.CurrentActor;
import java.util.List;
import java.util.Set;

public interface RecommendationService {
    List<RecommendationCandidate> recommend(CurrentActor actor, String goal, Set<String> healthConstraints);
}
