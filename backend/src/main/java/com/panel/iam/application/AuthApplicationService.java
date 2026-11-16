package com.panel.iam.application;

import com.panel.iam.application.command.LoginCommand;
import com.panel.iam.application.command.LogoutCommand;
import com.panel.iam.application.command.RefreshCommand;
import com.panel.iam.application.command.RegisterTenantCommand;
import com.panel.iam.application.result.AuthResult;
import com.panel.shared.security.CurrentActor;

public interface AuthApplicationService {

    AuthResult registerTenant(RegisterTenantCommand command);

    AuthResult login(LoginCommand command);

    AuthResult refresh(RefreshCommand command);

    void logout(CurrentActor actor, LogoutCommand command);
}
