package com.nova.tenancy.api;

import com.nova.shared.api.ApiResponse;
import com.nova.shared.security.CurrentActorProvider;
import com.nova.tenancy.application.PlatformStatsService;
import com.nova.tenancy.application.PlatformStatsView;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
@SecurityRequirement(name = "bearerAuth")
public class PlatformStatsController {

    private final PlatformStatsService service;
    private final CurrentActorProvider actors;

    public PlatformStatsController(PlatformStatsService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping("/stats")
    public ApiResponse<PlatformStatsView> stats() {
        return ApiResponse.success(service.stats(actors.requireCurrent()));
    }
}
