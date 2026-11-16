package com.panel.iam.application;

import com.panel.iam.application.command.AcceptInvitationCommand;
import com.panel.iam.application.command.InviteUserCommand;
import com.panel.iam.application.result.AuthResult;
import com.panel.iam.domain.model.RoleCode;
import com.panel.shared.security.CurrentActor;

import java.time.Instant;

public interface UserInvitationService {
    InvitationIssued invite(CurrentActor actor, InviteUserCommand command);
    AuthResult accept(AcceptInvitationCommand command);

    record InvitationIssued(String rawToken, Long tenantId, String email,
                            RoleCode role, Instant expiresAt) {}
}
