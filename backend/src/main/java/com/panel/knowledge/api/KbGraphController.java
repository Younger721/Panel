package com.panel.knowledge.api;

import com.panel.knowledge.application.KbConceptView;
import com.panel.knowledge.application.KbGraphExtractionService;
import com.panel.knowledge.application.KbGraphService;
import com.panel.knowledge.application.KbGraphStats;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbGraphController {

    private final KbGraphExtractionService extraction;
    private final KbGraphService graph;
    private final CurrentActorProvider actors;

    public KbGraphController(KbGraphExtractionService extraction, KbGraphService graph,
                             CurrentActorProvider actors) {
        this.extraction = extraction;
        this.graph = graph;
        this.actors = actors;
    }

    @PostMapping("/{kbId}/graph/rebuild")
    public ApiResponse<Map<String, Object>> rebuild(@PathVariable Long kbId) {
        require("knowledge:write");
        if (!extraction.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "图谱抽取未启用：请设置 panel.kb.graph.enabled=true");
        }
        int documents = extraction.rebuildKb(kbId);
        return ApiResponse.success(Map.of("documents", documents, "enabled", true));
    }

    @GetMapping("/{kbId}/graph/stats")
    public ApiResponse<KbGraphStats> stats(@PathVariable Long kbId) {
        require("knowledge:read");
        return ApiResponse.success(graph.stats(kbId));
    }

    @GetMapping("/{kbId}/concepts")
    public ApiResponse<List<KbConceptView>> concepts(@PathVariable Long kbId,
                                                     @RequestParam(defaultValue = "100") int limit) {
        require("knowledge:read");
        return ApiResponse.success(graph.concepts(kbId, limit));
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
