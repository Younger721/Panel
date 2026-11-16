package com.panel.iam.application.command;

import com.panel.iam.domain.model.RoleCode;

public record InviteUserCommand(String email, RoleCode role) {
}
