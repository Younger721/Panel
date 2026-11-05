package com.nova.iam.application;

import com.nova.iam.application.command.LoginCommand;
import com.nova.iam.application.command.LogoutCommand;
import com.nova.iam.application.command.RefreshCommand;
import com.nova.iam.application.command.RegisterTenantCommand;
import com.nova.iam.application.result.AuthResult;
import com.nova.shared.security.CurrentActor;

public interface AuthApplicationService {

    AuthResult registerTenant(RegisterTenantCommand command);

    AuthResult login(LoginCommand command);

    AuthResult refresh(RefreshCommand command);

    void logout(CurrentActor actor, LogoutCommand command);
}
