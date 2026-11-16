package com.panel.knowledge.api;

import com.panel.knowledge.api.request.KbChatRequest;
import com.panel.knowledge.application.KbChatCitation;
import com.panel.knowledge.application.KbChatListener;
import com.panel.knowledge.application.KbChatMessageView;
import com.panel.knowledge.application.KbChatOptions;
import com.panel.knowledge.application.KbChatService;
import com.panel.knowledge.application.KbChatSessionView;
import com.panel.knowledge.application.KbSearchStage;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库问答（K3）。
 *
 * <p>流式协议（SSE）：{@code session → stage × N → citations → token × N → done}，
 * 失败时补一条 {@code error}。前端据此画思考链路并逐字出答案。</p>
 */
@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbChatController {

    private final KbChatService chat;
    private final CurrentActorProvider actors;

    public KbChatController(KbChatService chat, CurrentActorProvider actors) {
        this.chat = chat;
        this.actors = actors;
    }

    @GetMapping("/{kbId}/chat/sessions")
    public ApiResponse<List<KbChatSessionView>> sessions(@PathVariable Long kbId) {
        return ApiResponse.success(chat.sessions(kbId, actorId()));
    }

    @GetMapping("/{kbId}/chat/sessions/{sessionId}/messages")
    public ApiResponse<List<KbChatMessageView>> messages(@PathVariable Long kbId,
                                                         @PathVariable Long sessionId) {
        return ApiResponse.success(chat.messages(kbId, sessionId, actorId()));
    }

    @DeleteMapping("/{kbId}/chat/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long kbId, @PathVariable Long sessionId) {
        chat.deleteSession(kbId, sessionId, actorId());
        return ApiResponse.success(null);
    }

    @PostMapping(value = "/{kbId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable Long kbId,
                                             @Valid @RequestBody KbChatRequest request) {
        Long userId = actorId();
        SseEmitter emitter = new SseEmitter(180_000L);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        emitter.onCompletion(executor::shutdown);
        emitter.onTimeout(() -> {
            executor.shutdownNow();
            emitter.complete();
        });
        emitter.onError(error -> executor.shutdownNow());

        KbChatOptions options = new KbChatOptions(request.topK(), request.mode(),
                request.rerank(), request.publishedOnly());
        executor.execute(() -> {
            try {
                chat.streamAnswer(kbId, request.sessionId(), request.question(), options, userId,
                        new SseChatListener(emitter));
            } catch (Exception ex) {
                try {
                    send(emitter, "error", Map.of("message",
                            ex.getMessage() == null ? "问答服务暂时不可用" : ex.getMessage()));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            } finally {
                emitter.complete();
            }
        });
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private Long actorId() {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission("knowledge:read")) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor.userId();
    }

    private record SseChatListener(SseEmitter emitter) implements KbChatListener {

        @Override
        public void onSession(Long sessionId, String title) {
            send(emitter, "session", Map.of("id", sessionId, "title", title));
        }

        @Override
        public void onStage(KbSearchStage stage) {
            send(emitter, "stage", stage);
        }

        @Override
        public void onCitations(List<KbChatCitation> citations) {
            send(emitter, "citations", citations);
        }

        @Override
        public void onToken(String token) {
            send(emitter, "token", Map.of("text", token));
        }

        @Override
        public void onDone(com.panel.knowledge.application.KbChatAnswer answer) {
            send(emitter, "done", answer);
        }
    }

    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("client disconnected during kb chat stream", ex);
        }
    }
}
