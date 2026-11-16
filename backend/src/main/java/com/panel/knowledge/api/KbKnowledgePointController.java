package com.panel.knowledge.api;

import com.panel.knowledge.api.request.CreateCategoryRelationRequest;
import com.panel.knowledge.application.KbCatalogCoverageService;
import com.panel.knowledge.application.KbCategoryRelationService;
import com.panel.knowledge.application.KbQuizViews;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识点侧：目录覆盖率 + 节点之间的横切关系。
 *
 * <p>覆盖率是刷题错题联动的前置指标；关系只做节点级 1 跳，用来替代实体图谱。</p>
 */
@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbKnowledgePointController {

    private final KbCatalogCoverageService coverage;
    private final KbCategoryRelationService relations;
    private final CurrentActorProvider actors;

    public KbKnowledgePointController(KbCatalogCoverageService coverage,
                                      KbCategoryRelationService relations,
                                      CurrentActorProvider actors) {
        this.coverage = coverage;
        this.relations = relations;
        this.actors = actors;
    }

    /** 题库覆盖率：已挂目录 / 未分类 / 低置信。 */
    @GetMapping("/{kbId}/catalog/coverage")
    public ApiResponse<KbQuizViews.Coverage> coverage(@PathVariable Long kbId) {
        require("knowledge:read");
        return ApiResponse.success(coverage.coverage(kbId));
    }

    @GetMapping("/{kbId}/catalog/relations")
    public ApiResponse<List<KbQuizViews.Relation>> relations(@PathVariable Long kbId,
                                                             @RequestParam(required = false) Long categoryId) {
        require("knowledge:read");
        return ApiResponse.success(categoryId == null
                ? relations.listAll(kbId)
                : relations.list(kbId, categoryId));
    }

    @PostMapping("/{kbId}/catalog/relations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KbQuizViews.Relation> create(@PathVariable Long kbId,
                                                    @Valid @RequestBody CreateCategoryRelationRequest request) {
        require("knowledge:write");
        return ApiResponse.success(relations.create(kbId, request.fromCategoryId(), request.toCategoryId(),
                request.relation(), request.weight(), request.note()));
    }

    @DeleteMapping("/{kbId}/catalog/relations/{relationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long kbId, @PathVariable Long relationId) {
        require("knowledge:write");
        relations.delete(kbId, relationId);
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
