package com.nova.tenancy.api;

import com.nova.shared.api.ApiResponse;
import com.nova.shared.security.CurrentActorProvider;
import com.nova.tenancy.application.TenantQuotaService;
import com.nova.tenancy.application.TenantQuotaView;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/quota")
@SecurityRequirement(name = "bearerAuth")
public class TenantQuotaController {

    private final TenantQuotaService service;
    private final CurrentActorProvider actors;

    public TenantQuotaController(TenantQuotaService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public ApiResponse<TenantQuotaView> current() {
        return ApiResponse.success(service.getForTenant(actors.requireCurrent()));
    }
}
