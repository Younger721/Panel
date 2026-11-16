package com.panel.audit.application;

import com.panel.ai.application.AiUsageRecord;
import com.panel.shared.security.CurrentActor;
import java.util.List;

public interface AiUsageQueryService {
    List<AiUsageRecord> list(CurrentActor actor);
    AiUsagePage search(CurrentActor actor, AiUsageFilter filter);
    AiUsageSummary summary(CurrentActor actor, AiUsageFilter filter);
    List<AiUsageTrendPoint> trend(CurrentActor actor, AiUsageFilter filter);

    /** 按 feature 拆开的用量与成本，按成本降序——回答"钱花在哪个功能上"。 */
    List<AiFeatureUsage> byFeature(CurrentActor actor, AiUsageFilter filter);
}
