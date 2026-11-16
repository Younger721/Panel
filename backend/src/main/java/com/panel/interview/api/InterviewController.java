package com.panel.interview.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.panel.interview.application.InterviewProgressListener;
import com.panel.interview.application.InterviewService;
import com.panel.interview.application.InterviewService.AnswerResult;
import com.panel.interview.application.InterviewService.SessionView;
import com.panel.interview.application.InterviewStudyPlanService;
import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import com.panel.shared.security.SecurityContextPropagation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模拟面试接口。
 *
 * <p>同步语义：每次调用会跑 1-2 次模型（出题或评分），耗时 3-15 秒。
 * 提交作答请带上 {@code seq}（前端看到的题号）：服务端用它做幂等校验，
 * 双击/重试/两个标签页并发时只有一个请求能推进到下一题。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/sessions")
@SecurityRequirement(name = "bearerAuth")
public class InterviewController {

    private final InterviewService service;
    private final InterviewStudyPlanService studyPlan;
    private final CurrentActorProvider actors;

    public InterviewController(InterviewService service, InterviewStudyPlanService studyPlan,
                               CurrentActorProvider actors) {
        this.service = service;
        this.studyPlan = studyPlan;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SessionView> create(@Valid @RequestBody CreateSessionRequest request) {
        require("interview:start");
        return ApiResponse.success(service.create(request.resumeId(), request.questionCount(),
                request.followUpLimit(), persona(request.persona()), request.jd(),
                InterviewProgressListener.NOOP));
    }

    /**
     * 这场面试暴露的弱项 + 对应的复习材料。
     *
     * <p>面试报告里的 skillScores 低于 60 的项，会去知识库里按名字找
     * 相关概念、题目和文档。会话还没结束或报告没生成时返回 409。</p>
     */
    @GetMapping("/{id}/study-plan")
    public ApiResponse<InterviewStudyPlanService.StudyPlan> studyPlan(@PathVariable Long id) {
        require("report:read");
        return ApiResponse.success(studyPlan.plan(id));
    }

    /** 历史面试记录（只返回当前用户自己的；平台管理员看全部）。 */
    @GetMapping
    public ApiResponse<java.util.List<InterviewService.SessionSummary>> list() {
        require("interview:read");
        return ApiResponse.success(service.list());
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
        return ApiResponse.success(service.answer(id, request.answer(), request.seq()));
    }

    /**
     * 主动结束一场没答完的面试。
     *
     * <p>没有这个出口，关掉浏览器就等于把会话永久留在"进行中"：历史页一直挂着，
     * 报告永远打不开，语音票据还能继续签发。</p>
     */
    @PostMapping("/{id}/abandon")
    public ApiResponse<SessionView> abandon(@PathVariable Long id) {
        require("interview:answer");
        return ApiResponse.success(service.abandon(id));
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
            SessionView view = service.create(request.resumeId(), request.questionCount(),
                    request.followUpLimit(), persona(request.persona()), request.jd(), sender(emitter));
            send(emitter, "done", view);
        });
    }

    /** 提交回答（流式）：{@code agent} → {@code evaluation} → {@code question} / {@code report} → {@code done}。 */
    @PostMapping(value = "/{id}/answers/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> answerStream(@PathVariable Long id,
                                                   @Valid @RequestBody SubmitAnswerRequest request) {
        require("interview:answer");
        return stream(emitter -> {
            AnswerResult result = service.answer(id, request.answer(), request.seq(), sender(emitter));
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
        // 虚拟线程不继承请求线程的 SecurityContext，而模型调用要按 actor 记用量账，
        // 所以把请求上下文一起带进执行线程（跑完自动清掉）。
        executor.execute(SecurityContextPropagation.wrap(() -> {
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
        }));
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private static InterviewProgressListener sender(SseEmitter emitter) {
        return (event, payload) -> send(emitter, event, payload);
    }

    /**
     * 推事件给浏览器。
     *
     * <p><b>断线绝不能往上抛</b>：用户切走标签页、网络抖动都会让 {@code send} 失败，
     * 而这一步发生在编排流程里——抛出去会让已经花了 40 秒模型调用的作答整体回滚，
     * 用户回来发现自己白答了、token 还照扣。这里只记日志，编排继续跑完并落库。</p>
     */
    private static void send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (Exception ex) {
            // 客户端断开：数据已经/即将落库，这里咽掉即可
        }
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private static InterviewerPersona persona(String code) {
        return InterviewerPersona.fromCode(code);
    }

    /**
     * 岗位 JD 可选：不传就是纯简历面试（和加 JD 之前一样），
     * 传了才会跑 JobFitAgent，让选题偏向 JD 要求的技能。
     */
    public record CreateSessionRequest(@NotNull Long resumeId, Integer questionCount, Integer followUpLimit,
                                       String persona, String jd) {
    }

    /**
     * @param seq 前端当前看到的题号。带上它才能挡住"答案被记到下一题上"：
     *            重试时若服务端 pending 已经推进，说明这次提交是过期的，直接 409 让前端刷新。
     */
    public record SubmitAnswerRequest(String answer, Integer seq) {
    }
}
