package com.panel.tenancy.application.port;

import com.panel.tenancy.domain.model.Tenant;

@FunctionalInterface
public interface TenantProvisioningContributor {
    void contribute(Tenant tenant);
}
