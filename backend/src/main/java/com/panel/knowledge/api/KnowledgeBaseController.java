package com.panel.knowledge.api;

import com.panel.knowledge.api.request.CreateKnowledgeBaseRequest;
import com.panel.knowledge.api.request.UpdateKnowledgeBaseRequest;
import com.panel.knowledge.application.KnowledgeBaseService;
import com.panel.knowledge.application.KnowledgeBaseView;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库（库维度）接口。
 *
 * <p>文档、切片、检索、问答在后续阶段挂到 /api/v1/kb/{id}/… 下；这里先提供库的 CRUD。</p>
 */
@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;
    private final CurrentActorProvider actors;

    public KnowledgeBaseController(KnowledgeBaseService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseView>> list() {
        require("knowledge:read");
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseView> get(@PathVariable Long id) {
        require("knowledge:read");
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KnowledgeBaseView> create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        CurrentActor actor = require("knowledge:write");
        return ApiResponse.success(service.create(request.toCommand(), actor.userId()));
    }

    @PatchMapping("/{id}")
    public ApiResponse<KnowledgeBaseView> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        require("knowledge:write");
        return ApiResponse.success(service.update(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id,
                       @RequestParam(defaultValue = "false") boolean force) {
        require("knowledge:write");
        service.delete(id, force);
    }

    private CurrentActor require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }
}
