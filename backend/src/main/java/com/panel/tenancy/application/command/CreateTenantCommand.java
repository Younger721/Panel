package com.panel.tenancy.application.command;

public record CreateTenantCommand(
        String tenantName,
        String adminEmail,
        String adminPassword,
        String adminDisplayName) {
}
