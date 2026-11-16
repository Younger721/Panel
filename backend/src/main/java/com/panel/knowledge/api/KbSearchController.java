package com.panel.knowledge.api;

import com.panel.knowledge.api.request.KbSearchRequest;
import com.panel.knowledge.application.KbSearchResult;
import com.panel.knowledge.application.KbSearchService;
import com.panel.knowledge.infrastructure.search.KbSearchFilters;
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

@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbSearchController {

    private final KbSearchService search;
    private final CurrentActorProvider actors;

    public KbSearchController(KbSearchService search, CurrentActorProvider actors) {
        this.search = search;
        this.actors = actors;
    }

    @PostMapping("/{kbId}/search")
    public ApiResponse<KbSearchResult> search(@PathVariable Long kbId,
                                              @Valid @RequestBody KbSearchRequest request) {
        require("knowledge:read");
        int topK = request.topK() == null ? 8 : request.topK();
        boolean rerank = request.rerank() == null || request.rerank();
        boolean publishedOnly = Boolean.TRUE.equals(request.publishedOnly());
        return ApiResponse.success(search.search(kbId, request.query(), topK, request.mode(),
                rerank, publishedOnly, filtersOf(request)));
    }

    /**
     * 检索（流式）：每进入/完成一个阶段推一条 {@code stage}，最后推 {@code result} 与 {@code done}。
     *
     * <p>前端据此把「文档范围 → BM25 → 向量 → RRF → rerank → 阈值筛选」逐个点亮，
     * 而不是先干等一个黑盒结果。检索本身是阻塞 IO（ES / Milvus / 百炼），放虚拟线程执行。</p>
     */
    @PostMapping(value = "/{kbId}/search/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> searchStream(@PathVariable Long kbId,
                                                   @Valid @RequestBody KbSearchRequest request) {
        require("knowledge:read");
        int topK = request.topK() == null ? 8 : request.topK();
        boolean rerank = request.rerank() == null || request.rerank();
        boolean publishedOnly = Boolean.TRUE.equals(request.publishedOnly());

        SseEmitter emitter = new SseEmitter(60_000L);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        emitter.onCompletion(executor::shutdown);
        emitter.onTimeout(() -> {
            executor.shutdownNow();
            emitter.complete();
        });
        emitter.onError(error -> executor.shutdownNow());
        executor.execute(() -> {
            try {
                KbSearchResult result = search.search(kbId, request.query(), topK, request.mode(),
                        rerank, publishedOnly, filtersOf(request), stage -> send(emitter, "stage", stage));
                send(emitter, "result", result);
                send(emitter, "done", Map.of("latencyMs", result.debug().latencyMs()));
            } catch (Exception ex) {
                try {
                    send(emitter, "error", Map.of("message",
                            ex.getMessage() == null ? "检索服务暂时不可用" : ex.getMessage()));
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

    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException ex) {
            throw new IllegalStateException("client disconnected during kb search stream", ex);
        }
    }

    private static KbSearchFilters filtersOf(KbSearchRequest request) {
        return new KbSearchFilters(request.company(), request.difficulty(), request.year(),
                request.tags() == null ? List.of() : request.tags(),
                request.categoryIds() == null ? List.of() : request.categoryIds());
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
