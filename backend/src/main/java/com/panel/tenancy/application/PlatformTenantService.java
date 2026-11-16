package com.panel.tenancy.application;

import com.panel.shared.api.PageResponse;
import com.panel.shared.security.CurrentActor;
import com.panel.tenancy.application.command.CreateTenantCommand;
import com.panel.tenancy.application.command.UpdateTenantCommand;
import com.panel.tenancy.api.response.TenantView;
import org.springframework.data.domain.Pageable;

public interface PlatformTenantService {

    TenantView create(CreateTenantCommand command, CurrentActor actor);

    TenantView update(Long tenantId, UpdateTenantCommand command, CurrentActor actor);

    PageResponse<TenantView> list(CurrentActor actor, Pageable pageable);

    TenantView activate(Long tenantId, CurrentActor actor);

    TenantView disable(Long tenantId, CurrentActor actor);
}
