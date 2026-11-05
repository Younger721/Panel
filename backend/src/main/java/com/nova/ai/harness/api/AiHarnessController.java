package com.nova.ai.harness.api;

import com.nova.ai.application.AiChatView;
import com.nova.ai.harness.application.AiHarnessService;
import com.nova.ai.harness.application.HarnessConfigView;
import com.nova.ai.harness.application.AiChatTurn;
import com.nova.ai.harness.AiProgressEvent;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.api.TraceIdFilter;
import com.nova.shared.error.BusinessException;
import com.nova.shared.error.ErrorCode;
import com.nova.shared.security.ActorAccess;
import com.nova.shared.security.CurrentActorProvider;
import com.nova.shared.security.CurrentActor;
import com.nova.shared.security.TenantContextHolder;
import org.slf4j.MDC;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/harness")
@SecurityRequirement(name = "bearerAuth")
public class AiHarnessController {

    private final AiHarnessService service;
    private final CurrentActorProvider actors;

    public AiHarnessController(AiHarnessService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    /** 获取当前用户可见的 Harness 配置与插件目录（门店侧只读，提示词由平台+门店 Agent 两层维护） */
    @GetMapping("/config")
    public ApiResponse<HarnessConfigView> config() {
        return ApiResponse.success(service.getConfig(actors.requireCurrent()));
    }

    /** 插件化对话：按已启用插件组装上下文后调用模型 */
    @PostMapping("/chat")
    public ApiResponse<AiChatView> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(service.chat(
                actors.requireCurrent(),
                request.sessionId(),
                request.question(),
                request.plugins()));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.http.ResponseEntity<SseEmitter> chatStream(@Valid @RequestBody ChatRequest request) {
        CurrentActor actor = actors.requireCurrent();
        // SSE 在异步线程执行，Filter 的 finally 会先清掉 TenantContextHolder，此处显式传递顶栏门店上下文
        Long scopedTenantId = ActorAccess.tenantId(actor);
        SseEmitter emitter = new SseEmitter(300_000L);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ai-harness-stream");
            thread.setDaemon(true);
            return thread;
        });
        emitter.onCompletion(executor::shutdown);
        emitter.onTimeout(() -> {
            executor.shutdownNow();
            emitter.complete();
        });
        executor.execute(() -> {
            try {
                if (scopedTenantId != null) {
                    TenantContextHolder.set(scopedTenantId);
                }
                AiChatView result = service.chat(actor, request.sessionId(), request.question(), request.plugins(), request.history(),
                        token -> sendToken(emitter, token),
                        progress -> sendProgress(emitter, progress));
                emitter.send(SseEmitter.event().name("done").data(result));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(buildStreamError(ex)));
                } catch (Exception ignored) {
                    // 客户端已断开连接
                }
                emitter.complete();
            } finally {
                TenantContextHolder.clear();
            }
        });
        return org.springframework.http.ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private static Map<String, String> buildStreamError(Exception ex) {
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? "AI 服务暂时不可用"
                : ex.getMessage();
        String code = ex instanceof BusinessException business
                ? business.errorCode().name()
                : ErrorCode.DEPENDENCY_UNAVAILABLE.name();
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            return Map.of("message", message, "code", code);
        }
        return Map.of("message", message, "code", code, "traceId", traceId);
    }

    private static void sendToken(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(token));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Client disconnected during AI response", exception);
        }
    }

    private static void sendProgress(SseEmitter emitter, AiProgressEvent progress) {
        try {
            String eventName = switch (progress.stage()) {
                case "tool_decision", "tool_start", "tool_result" -> progress.stage();
                default -> "progress";
            };
            emitter.send(SseEmitter.event().name(eventName).data(progress));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Client disconnected during AI progress", exception);
        }
    }

    /**
     * @param plugins 仅平台管理员用于会话内收窄能力；门店侧忽略——门店只允许改提示词，
     *                能用哪些工具由平台配置与角色模板决定
     */
    public record ChatRequest(
            @NotBlank String sessionId,
            @NotBlank String question,
            List<String> plugins,
            List<AiChatTurn> history) {
    }
}
