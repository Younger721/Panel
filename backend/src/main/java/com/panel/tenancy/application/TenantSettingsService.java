package com.panel.tenancy.application;

import com.panel.shared.security.CurrentActor;
import com.panel.tenancy.api.response.TenantSettingsView;
import com.panel.tenancy.application.command.UpdateTenantSettingsCommand;

public interface TenantSettingsService {

    TenantSettingsView getCurrent(CurrentActor actor);

    TenantSettingsView updateCurrent(CurrentActor actor, UpdateTenantSettingsCommand command);

    TenantSettingsView getForPlatform(Long tenantId, CurrentActor actor);

    TenantSettingsView updateForPlatform(Long tenantId, CurrentActor actor, UpdateTenantSettingsCommand command);
}
