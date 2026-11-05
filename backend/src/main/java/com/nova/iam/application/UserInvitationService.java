package com.nova.iam.application;

import com.nova.iam.application.command.AcceptInvitationCommand;
import com.nova.iam.application.command.InviteUserCommand;
import com.nova.iam.application.result.AuthResult;
import com.nova.iam.domain.model.RoleCode;
import com.nova.shared.security.CurrentActor;

import java.time.Instant;

public interface UserInvitationService {
    InvitationIssued invite(CurrentActor actor, InviteUserCommand command);
    AuthResult accept(AcceptInvitationCommand command);

    record InvitationIssued(String rawToken, Long tenantId, String email,
                            RoleCode role, Instant expiresAt) {}
}
