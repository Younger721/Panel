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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /**
     * 建会话（流式）：先推 {@code agent} 事件（Planner 开始/完成），再推 {@code question}，最后 {@code done}。
     *
     * <p>用虚拟线程执行：模型调用是阻塞 IO，一题 4-8 秒，占着 Tomcat 线程不划算。</p>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> createStream(@Valid @RequestBody CreateSessionRequest request) {
        require("interview:start");
        return stream(emitter -> {
            SessionView view = service.create(request.resumeId(), request.turnBudget(), sender(emitter));
            send(emitter, "done", view);
        });
    }

    /** 提交回答（流式）：{@code agent} → {@code evaluation} → {@code question} / {@code report} → {@code done}。 */
    @PostMapping(value = "/{id}/answers/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> answerStream(@PathVariable Long id,
                                                   @Valid @RequestBody SubmitAnswerRequest request) {
        require("interview:answer");
        return stream(emitter -> {
            AnswerResult result = service.answer(id, request.answer(), sender(emitter));
            send(emitter, "done", result);
        });
    }

    private interface StreamBody {
        void run(SseEmitter emitter) throws Exception;
    }

    private static ResponseEntity<SseEmitter> stream(StreamBody body) {
        SseEmitter emitter = new SseEmitter(300_000L);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        emitter.onCompletion(executor::shutdown);
        emitter.onTimeout(() -> {
            executor.shutdownNow();
            emitter.complete();
        });
        executor.execute(() -> {
            try {
                body.run(emitter);
                emitter.complete();
            } catch (Exception ex) {
                try {
                    send(emitter, "error", Map.of("message",
                            ex.getMessage() == null ? "面试服务暂时不可用" : ex.getMessage()));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }
        });
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private static com.nova.interview.application.InterviewProgressListener sender(SseEmitter emitter) {
        return (event, payload) -> send(emitter, event, payload);
    }

    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("client disconnected during interview stream", ex);
        }
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
