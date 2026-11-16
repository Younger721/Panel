package com.panel.iam.api.request;

import com.panel.iam.application.command.InviteUserCommand;
import com.panel.iam.domain.model.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteUserRequest(@NotBlank @Email String email, @NotNull RoleCode role) {
    public InviteUserCommand toCommand() {
        return new InviteUserCommand(email, role);
    }
}
