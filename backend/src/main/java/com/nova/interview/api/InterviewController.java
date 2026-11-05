package com.nova.interview.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nova.interview.application.InterviewService;
import com.nova.interview.application.InterviewService.AnswerResult;
import com.nova.interview.application.InterviewService.SessionView;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 模拟面试接口。
 *
 * <p>同步语义：每次调用会跑 1-2 次模型（出题或评分），耗时 3-15 秒。
 * 这里刻意不开 SSE——先让编排正确，流式是壳，后面加不影响接口形状。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/sessions")
@SecurityRequirement(name = "bearerAuth")
public class InterviewController {

    private final InterviewService service;
    private final CurrentActorProvider actors;

    public InterviewController(InterviewService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionView> create(@Valid @RequestBody CreateSessionRequest request) {
        require("interview:start");
        return ApiResponse.success(service.create(request.resumeId(), request.turnBudget()));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionView> detail(@PathVariable Long id) {
        require("interview:read");
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/answers")
    public ApiResponse<AnswerResult> answer(@PathVariable Long id,
                                            @Valid @RequestBody SubmitAnswerRequest request) {
        require("interview:answer");
        return ApiResponse.success(service.answer(id, request.answer()));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<JsonNode> report(@PathVariable Long id) {
        require("report:read");
        return ApiResponse.success(service.report(id));
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    public record CreateSessionRequest(@NotNull Long resumeId, Integer turnBudget) {
    }

    public record SubmitAnswerRequest(String answer) {
    }
}
