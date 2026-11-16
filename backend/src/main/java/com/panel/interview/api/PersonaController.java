package com.panel.interview.api;

import com.panel.interview.domain.model.InterviewerPersona;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * 可选面试官人设。
 *
 * <p>放在后端而不是前端写死：人设同时决定音色和追问阈值，
 * 这两样都是服务端行为，前端只负责展示文案。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/personas")
@SecurityRequirement(name = "bearerAuth")
public class PersonaController {

    private final CurrentActorProvider actors;

    public PersonaController(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @GetMapping
    public ApiResponse<List<PersonaView>> list() {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission("interview:start")) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        List<PersonaView> views = Arrays.stream(InterviewerPersona.values())
                .map(persona -> new PersonaView(persona.name(), persona.label(), persona.title(),
                        persona.description(), persona.followUpThreshold()))
                .toList();
        return ApiResponse.success(views);
    }

    public record PersonaView(String code, String label, String title, String description,
                              int followUpThreshold) {
    }
}
