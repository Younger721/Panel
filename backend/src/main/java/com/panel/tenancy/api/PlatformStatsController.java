package com.panel.tenancy.api;

import com.panel.shared.api.ApiResponse;
import com.panel.shared.security.CurrentActorProvider;
import com.panel.tenancy.application.PlatformStatsService;
import com.panel.tenancy.application.PlatformStatsView;
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
