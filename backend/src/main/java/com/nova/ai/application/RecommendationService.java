package com.nova.ai.application;

import com.nova.shared.security.CurrentActor;
import java.util.List;
import java.util.Set;

public interface RecommendationService {
    List<RecommendationCandidate> recommend(CurrentActor actor, String goal, Set<String> healthConstraints);
}
