package com.panel.knowledge.api;

import com.panel.knowledge.api.request.GradeQuizRequest;
import com.panel.knowledge.api.request.StartQuizRequest;
import com.panel.knowledge.application.KbInterviewBackfillService;
import com.panel.knowledge.application.KbQuizService;
import com.panel.knowledge.application.KbQuizViews;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
 * 刷题：抽题 → 展开答案 → 自评 → 错题定位知识点。
 *
 * <p>题面接口不带答案，答案只在 reveal 下发；自评结果写进 kb_quiz_attempt，
 * 错题本与掌握度都从这张表推导。</p>
 */
@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbQuizController {

    private final KbQuizService quiz;
    private final KbInterviewBackfillService interviewBackfill;
    private final CurrentActorProvider actors;

    public KbQuizController(KbQuizService quiz, KbInterviewBackfillService interviewBackfill,
                            CurrentActorProvider actors) {
        this.quiz = quiz;
        this.interviewBackfill = interviewBackfill;
        this.actors = actors;
    }

    @PostMapping("/{kbId}/quiz")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KbQuizViews.Session> start(@PathVariable Long kbId,
                                                  @RequestBody(required = false) @Valid StartQuizRequest request) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.start(kbId, actor.userId(), request == null ? null : request.size()));
    }

    @GetMapping("/quiz/sessions/{sessionId}")
    public ApiResponse<KbQuizViews.Session> session(@PathVariable Long sessionId) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.session(sessionId, actor.userId()));
    }

    @PostMapping("/quiz/attempts/{attemptId}/reveal")
    public ApiResponse<KbQuizViews.Reveal> reveal(@PathVariable Long attemptId) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.reveal(attemptId, actor.userId()));
    }

    @PostMapping("/quiz/attempts/{attemptId}/grade")
    public ApiResponse<KbQuizViews.Grade> grade(@PathVariable Long attemptId,
                                                @Valid @RequestBody GradeQuizRequest request) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.grade(attemptId, actor.userId(), request.result()));
    }

    @GetMapping("/{kbId}/quiz/stats")
    public ApiResponse<KbQuizViews.Stats> stats(@PathVariable Long kbId) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.stats(kbId, actor.userId()));
    }

    @GetMapping("/{kbId}/quiz/wrong-book")
    public ApiResponse<List<KbQuizViews.WrongItem>> wrongBook(@PathVariable Long kbId,
                                                              @RequestParam(defaultValue = "50") int limit) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.wrongBook(kbId, actor.userId(), limit));
    }

    /** 能力值总览：叶子知识点按弱到强排，父节点是按题量加权聚合出来的"整块弱"。 */
    @GetMapping("/{kbId}/quiz/skills")
    public ApiResponse<KbQuizViews.Skills> skills(@PathVariable Long kbId) {
        CurrentActor actor = require("knowledge:read");
        return ApiResponse.success(quiz.skills(kbId, actor.userId()));
    }

    /**
     * 面试信号回填：把 {@code interview_turn} 的 skill + final_score 折算成知识点能力值。
     *
     * <p>{@code dryRun=true}（默认）只看匹配结果不写库；{@code createMissing=true} 会把匹配不上的
     * skill 建成 {@code rootName} 下的叶子节点。重复调用是幂等的（turn 级台账去重）。</p>
     */
    @PostMapping("/{kbId}/quiz/skills/interview-backfill")
    public ApiResponse<KbQuizViews.InterviewBackfill> backfillInterview(
            @PathVariable Long kbId,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean createMissing,
            @RequestParam(defaultValue = "面试评估") String rootName) {
        CurrentActor actor = require("knowledge:write");
        Long targetUser = userId == null ? actor.userId() : userId;
        return ApiResponse.success(interviewBackfill.backfill(kbId, targetUser, dryRun, createMissing, rootName));
    }

    private CurrentActor require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return actor;
    }
}
