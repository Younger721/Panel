package com.nova.tenancy.application;

import com.nova.shared.security.CurrentActor;
import com.nova.tenancy.api.response.TenantSettingsView;
import com.nova.tenancy.application.command.UpdateTenantSettingsCommand;

public interface TenantSettingsService {

    TenantSettingsView getCurrent(CurrentActor actor);

    TenantSettingsView updateCurrent(CurrentActor actor, UpdateTenantSettingsCommand command);

    TenantSettingsView getForPlatform(Long tenantId, CurrentActor actor);

    TenantSettingsView updateForPlatform(Long tenantId, CurrentActor actor, UpdateTenantSettingsCommand command);
}
