package com.panel.ai.application;

import java.util.List;

@FunctionalInterface
public interface RecommendationCandidateSource {
    List<RecommendationCandidate> findByTenant(Long tenantId);
}
