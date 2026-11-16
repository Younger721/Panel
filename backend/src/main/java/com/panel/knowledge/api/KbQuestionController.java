package com.panel.knowledge.api;

import com.panel.knowledge.application.KbQuestionPage;
import com.panel.knowledge.application.KbQuestionReferenceService;
import com.panel.knowledge.application.KbQuestionReferenceView;
import com.panel.knowledge.application.KbQuestionService;
import com.panel.knowledge.application.KbQuestionView;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kb")
@SecurityRequirement(name = "bearerAuth")
public class KbQuestionController {

    private final KbQuestionService questions;
    private final KbQuestionReferenceService references;
    private final CurrentActorProvider actors;

    public KbQuestionController(KbQuestionService questions,
                                KbQuestionReferenceService references,
                                CurrentActorProvider actors) {
        this.questions = questions;
        this.references = references;
        this.actors = actors;
    }

    @GetMapping("/{kbId}/questions")
    public ApiResponse<KbQuestionPage> list(@PathVariable Long kbId,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        require("knowledge:read");
        return ApiResponse.success(questions.list(kbId, limit, offset));
    }

    @GetMapping("/questions/{questionId}")
    public ApiResponse<KbQuestionView> detail(@PathVariable Long questionId) {
        require("knowledge:read");
        return ApiResponse.success(questions.detail(questionId));
    }

    @GetMapping("/questions/{questionId}/references")
    public ApiResponse<List<KbQuestionReferenceView>> references(@PathVariable Long questionId) {
        require("knowledge:read");
        return ApiResponse.success(references.references(questionId));
    }

    private void require(String permission) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
