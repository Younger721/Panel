package com.nova.iam.application.command;

import com.nova.iam.domain.model.RoleCode;

public record InviteUserCommand(String email, RoleCode role) {
}
