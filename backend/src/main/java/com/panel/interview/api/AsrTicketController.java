package com.panel.interview.api;

import com.panel.interview.application.AsrTicketService;
import com.panel.shared.api.ApiResponse;
import com.panel.shared.error.BusinessException;
import com.panel.shared.error.ErrorCode;
import com.panel.shared.security.CurrentActor;
import com.panel.shared.security.CurrentActorProvider;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语音输入入场券。
 *
 * <p>前端拿到 ticket 后立刻用它连 {@code /ws/interview/asr}，30 秒内有效、只能用一次。</p>
 */
@RestController
@RequestMapping("/api/v1/interview/sessions")
@SecurityRequirement(name = "bearerAuth")
public class AsrTicketController {

    private final AsrTicketService tickets;
    private final CurrentActorProvider actors;

    public AsrTicketController(AsrTicketService tickets, CurrentActorProvider actors) {
        this.tickets = tickets;
        this.actors = actors;
    }

    @PostMapping("/{id}/asr-ticket")
    public ApiResponse<AsrTicketService.Issued> issue(@PathVariable Long id) {
        CurrentActor actor = actors.requireCurrent();
        if (!actor.hasPermission("interview:answer")) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return ApiResponse.success(tickets.issue(actor, id));
    }
}
