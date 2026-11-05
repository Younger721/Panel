package com.nova.tenancy.application.port;

import com.nova.tenancy.domain.model.Tenant;

@FunctionalInterface
public interface TenantProvisioningContributor {
    void contribute(Tenant tenant);
}
