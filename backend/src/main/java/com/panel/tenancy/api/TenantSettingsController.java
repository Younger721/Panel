package com.panel.tenancy.api;

import com.panel.shared.api.ApiResponse;
import com.panel.shared.security.ActorAccess;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import com.panel.tenancy.api.request.UpdateTenantSettingsRequest;
import com.panel.tenancy.api.response.TenantSettingsView;
import com.panel.tenancy.application.TenantSettingsService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/tenant/settings")
@SecurityRequirement(name = "bearerAuth")
public class TenantSettingsController {

    private final TenantSettingsService service;
    private final CurrentActorProvider actors;

    public TenantSettingsController(TenantSettingsService service, CurrentActorProvider actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    @GetMapping
    public ApiResponse<TenantSettingsView> getCurrent() {
        return ApiResponse.success(service.getCurrent(requireGymAdmin("tenant:settings:read")));
    }

    @PutMapping
    public ApiResponse<TenantSettingsView> update(@Valid @RequestBody UpdateTenantSettingsRequest request) {
        return ApiResponse.success(service.updateCurrent(requireGymAdmin("tenant:settings:write"), request.toCommand()));
    }

    private CurrentActor requireGymAdmin(String permission) {
        CurrentActor actor = actors.requireCurrent();
        ActorAccess.requireGymAdmin(actor, permission);
        return actor;
    }
}
