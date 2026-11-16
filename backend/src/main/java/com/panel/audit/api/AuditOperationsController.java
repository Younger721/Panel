package com.panel.audit.api;

import com.panel.ai.application.AiUsageRecord;
import com.panel.audit.application.AiUsageQueryService;
import com.panel.audit.application.AuditQueryService;
import com.panel.audit.domain.OperationAudit;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@SecurityRequirement(name = "bearerAuth")
public class AuditOperationsController {

    private final AuditQueryService auditQueryService;
    private final AiUsageQueryService aiUsageQueryService;
    private final CurrentActorProvider actors;

    public AuditOperationsController(
            AuditQueryService auditQueryService,
            AiUsageQueryService aiUsageQueryService,
            CurrentActorProvider actors) {
        this.auditQueryService = auditQueryService;
        this.aiUsageQueryService = aiUsageQueryService;
        this.actors = actors;
    }

    @GetMapping("/operations")
    public ApiResponse<List<OperationAudit>> operations() {
        return ApiResponse.success(auditQueryService.list(actors.requireCurrent()));
    }

    @GetMapping("/ai-usage")
    public ApiResponse<List<AiUsageRecord>> aiUsage() {
        return ApiResponse.success(aiUsageQueryService.list(actors.requireCurrent()));
    }
}
