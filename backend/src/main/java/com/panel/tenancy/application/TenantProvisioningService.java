package com.panel.tenancy.application;

import com.panel.iam.domain.model.UserAccount;
import com.panel.tenancy.domain.model.Tenant;

public interface TenantProvisioningService {
    ProvisionedTenant provision(Tenant tenant, Administrator administrator);

    record Administrator(String email, String username, String passwordHash, String displayName) {
        public Administrator(String email, String passwordHash, String displayName) {
            this(email, null, passwordHash, displayName);
        }
    }

    record ProvisionedTenant(Tenant tenant, UserAccount administrator) {}
}
