package com.nova.ai.api;

import com.nova.ai.application.AiChatService;
import com.nova.ai.application.AiChatView;
import com.nova.shared.api.ApiResponse;
import com.nova.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
@SecurityRequirement(name = "bearerAuth")
public class AiChatController {

    private final AiChatService service;
    private final CurrentActorProvider actors;

    public AiChatController(AiChatService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/chat")
    public ApiResponse<AiChatView> chat(@RequestBody ChatRequest request) {
        return ApiResponse.success(service.chat(
                actors.requireCurrent(), request.sessionId(), request.question()));
    }

    public record ChatRequest(@NotBlank String sessionId, @NotBlank String question) {
    }
}
