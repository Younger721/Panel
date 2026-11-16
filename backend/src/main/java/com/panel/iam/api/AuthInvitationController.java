package com.panel.iam.api;

import com.panel.iam.api.request.AcceptInvitationRequest;
import com.panel.iam.api.response.AuthResponse;
import com.panel.iam.application.UserInvitationService;
import com.panel.shared.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/auth/invitations")
public class AuthInvitationController {
    private final UserInvitationService service;

    public AuthInvitationController(UserInvitationService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping("/accept")
    public ApiResponse<AuthResponse> accept(@Valid @RequestBody AcceptInvitationRequest request) {
        return ApiResponse.success(AuthResponse.from(service.accept(request.toCommand())));
    }
}
