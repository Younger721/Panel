package com.nova.tenancy.application;

import com.nova.shared.api.PageResponse;
import com.nova.shared.security.CurrentActor;
import com.nova.tenancy.application.command.CreateTenantCommand;
import com.nova.tenancy.application.command.UpdateTenantCommand;
import com.nova.tenancy.api.response.TenantView;
import org.springframework.data.domain.Pageable;

public interface PlatformTenantService {

    TenantView create(CreateTenantCommand command, CurrentActor actor);

    TenantView update(Long tenantId, UpdateTenantCommand command, CurrentActor actor);

    PageResponse<TenantView> list(CurrentActor actor, Pageable pageable);

    TenantView activate(Long tenantId, CurrentActor actor);

    TenantView disable(Long tenantId, CurrentActor actor);
}
