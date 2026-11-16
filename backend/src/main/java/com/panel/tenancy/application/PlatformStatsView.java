package com.panel.tenancy.application;

public record PlatformStatsView(
        long totalTenants,
        long activeTenants,
        long totalAiCallsThisMonth,
        long tenantsWithAiModuleEnabled) {
}
