package com.nova.audit.application;

import com.nova.ai.application.AiUsageRecord;
import com.nova.shared.security.CurrentActor;
import java.util.List;

public interface AiUsageQueryService {
    List<AiUsageRecord> list(CurrentActor actor);
    AiUsagePage search(CurrentActor actor, AiUsageFilter filter);
    AiUsageSummary summary(CurrentActor actor, AiUsageFilter filter);
    List<AiUsageTrendPoint> trend(CurrentActor actor, AiUsageFilter filter);
}
