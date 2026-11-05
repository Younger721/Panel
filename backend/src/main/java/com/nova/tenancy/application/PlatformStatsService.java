package com.nova.tenancy.application;

import com.nova.shared.security.CurrentActor;

public interface PlatformStatsService {
    PlatformStatsView stats(CurrentActor actor);
}
