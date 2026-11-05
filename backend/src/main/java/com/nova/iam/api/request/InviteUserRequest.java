package com.nova.iam.api.request;

import com.nova.iam.application.command.InviteUserCommand;
import com.nova.iam.domain.model.RoleCode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InviteUserRequest(@NotBlank @Email String email, @NotNull RoleCode role) {
    public InviteUserCommand toCommand() {
        return new InviteUserCommand(email, role);
    }
}
