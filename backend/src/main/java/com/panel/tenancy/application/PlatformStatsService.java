package com.panel.tenancy.application;

import com.panel.shared.security.CurrentActor;

public interface PlatformStatsService {
    PlatformStatsView stats(CurrentActor actor);
}
