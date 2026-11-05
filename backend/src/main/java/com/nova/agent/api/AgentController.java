package com.nova.agent.api;

import com.nova.agent.application.AgentManagementService;
import com.nova.agent.application.AgentView;
import com.nova.agent.application.CreateAgentCommand;
import com.nova.agent.application.PromptHistoryView;
import com.nova.agent.application.UpdateAgentCommand;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent 查询与 prompt 更新；create/update/disable 由服务层暂返回 FORBIDDEN（二期开放）。 */
@RestController
@RequestMapping("/api/v1/agents")
@SecurityRequirement(name = "bearerAuth")
public class AgentController {

    private final AgentManagementService service;
    private final CurrentActorProvider actors;

    public AgentController(AgentManagementService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public ApiResponse<List<AgentView>> list() {
        return ApiResponse.success(service.list(actors.requireCurrent()));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentView> find(@PathVariable Long id) {
        return ApiResponse.success(service.find(actors.requireCurrent(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentView> create(@Valid @RequestBody AgentRequest request) {
        return ApiResponse.success(service.create(actors.requireCurrent(), request.toCreateCommand()));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentView> update(@PathVariable Long id, @Valid @RequestBody AgentRequest request) {
        return ApiResponse.success(service.update(actors.requireCurrent(), id, request.toUpdateCommand()));
    }

    @PutMapping("/{id}/prompt")
    public ApiResponse<AgentView> updatePrompt(@PathVariable Long id,
                                               @Valid @RequestBody PromptRequest request) {
        return ApiResponse.success(service.updatePrompt(
                actors.requireCurrent(), id, request.systemPrompt()));
    }

    /** 门店提示词历史（最近若干版，倒序） */
    @GetMapping("/{id}/prompt/history")
    public ApiResponse<List<PromptHistoryView>> promptHistory(@PathVariable Long id) {
        return ApiResponse.success(service.promptHistory(actors.requireCurrent(), id));
    }

    /** 回滚到指定历史版本；回滚本身也会追加一条历史 */
    @PostMapping("/{id}/prompt/rollback")
    public ApiResponse<AgentView> rollbackPrompt(@PathVariable Long id,
                                                 @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.success(service.rollbackPrompt(
                actors.requireCurrent(), id, request.revision()));
    }

    /** 平台应急清空门店补充规则（仅平台管理员） */
    @PostMapping("/{id}/prompt/clear")
    public ApiResponse<AgentView> clearPrompt(@PathVariable Long id) {
        return ApiResponse.success(service.clearPrompt(actors.requireCurrent(), id));
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<AgentView> disable(@PathVariable Long id) {
        return ApiResponse.success(service.disable(actors.requireCurrent(), id));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<AgentView> activate(@PathVariable Long id) {
        return ApiResponse.success(service.activate(actors.requireCurrent(), id));
    }

    public record AgentRequest(
            @NotBlank String name,
            String description,
            @NotBlank String systemPrompt,
            boolean knowledgeEnabled,
            @NotEmpty List<String> enabledTools) {
        CreateAgentCommand toCreateCommand() {
            return new CreateAgentCommand(name, description, systemPrompt, knowledgeEnabled, enabledTools);
        }

        UpdateAgentCommand toUpdateCommand() {
            return new UpdateAgentCommand(name, description, systemPrompt, knowledgeEnabled, enabledTools);
        }
    }

    public record PromptRequest(@NotBlank @Size(max = 4000) String systemPrompt) {
    }

    public record RollbackRequest(@NotNull Integer revision) {
    }
}
