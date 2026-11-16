package com.panel.ai.application;

import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.ActorAccess;
import com.panel.tenancy.application.TenantQuotaService;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class DefaultRecommendationService implements RecommendationService {
    private final RecommendationCandidateSource source;
    private final TenantQuotaService quotas;

    public DefaultRecommendationService(RecommendationCandidateSource source, TenantQuotaService quotas) { this.source = source; this.quotas = quotas; }

    @Override
    public List<RecommendationCandidate> recommend(CurrentActor actor, String goal, Set<String> healthConstraints) {
        Long tenantId = ActorAccess.tenantId(actor);
        if (tenantId == null || !actor.hasPermission("ai:recommend")
                || goal == null || goal.isBlank()) throw new BusinessException(ErrorCode.FORBIDDEN);
        quotas.ensureAiModuleEnabled(tenantId);
        Set<String> blocked = healthConstraints == null ? Set.of() : healthConstraints;
        return Optional.ofNullable(source.findByTenant(tenantId)).orElseGet(List::of).stream()
                .filter(c -> c != null && Objects.equals(c.tenantId(), tenantId))
                .filter(c -> c.tags().contains(goal))
                .filter(c -> Collections.disjoint(c.contraindications(), blocked))
                .toList();
    }
}
