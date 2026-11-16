package com.panel.iam.api.response;

import com.panel.iam.application.UserAdministrationService;
import com.panel.iam.domain.model.RoleCode;
import com.panel.iam.domain.model.UserStatus;

import java.util.Set;

public record UserResponse(Long id, Long tenantId, String tenantName, String username, String email, String displayName, String phone,
                           UserStatus status, Set<RoleCode> roles) {
    public static UserResponse from(UserAdministrationService.UserView user) {
        return new UserResponse(user.id(), user.tenantId(), null, user.username(), user.email(), user.displayName(), user.phone(),
                user.status(), user.roles());
    }
    public static UserResponse from(UserAdministrationService.UserSummary user) {
        return new UserResponse(user.id(), user.tenantId(), user.tenantName(), user.username(), user.email(), user.displayName(), user.phone(),
                user.status(), user.roles());
    }
}
