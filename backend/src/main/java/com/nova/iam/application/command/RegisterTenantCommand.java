package com.nova.iam.application.command;

public record RegisterTenantCommand(
        String tenantCode,
        String tenantName,
        String email,
        String password,
        String displayName) {
}
