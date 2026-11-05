package com.nova.tenancy.application.command;

public record CreateTenantCommand(
        String tenantName,
        String adminEmail,
        String adminPassword,
        String adminDisplayName) {
}
