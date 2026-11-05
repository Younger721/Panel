package com.nova.iam.api.response;

import com.nova.iam.application.UserAdministrationService;
import com.nova.iam.domain.model.RoleCode;

public record RoleResponse(Long id, RoleCode code, String name, String status, int permissionCount) {
    public static RoleResponse from(UserAdministrationService.RoleView role) {
        return new RoleResponse(role.id(), role.code(), role.name(), role.status(), role.permissionCount());
    }
}
