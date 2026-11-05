package com.nova.tenancy.application;

public record PlatformStatsView(
        long totalTenants,
        long activeTenants,
        long totalAiCallsThisMonth,
        long tenantsWithAiModuleEnabled) {
}
