package com.nova.tenancy.application;

import com.nova.tenancy.application.port.TenantProvisioningContributor;
import com.nova.tenancy.domain.model.Tenant;
import com.nova.tenancy.domain.model.TenantSettings;
import com.nova.tenancy.domain.repository.TenantSettingsRepository;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(100)
public class TenantSettingsProvisioningContributor implements TenantProvisioningContributor {
    private final TenantSettingsRepository settingsRepository;

    public TenantSettingsProvisioningContributor(TenantSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    @Override
    public void contribute(Tenant tenant) {
        if (settingsRepository.findByTenantId(tenant.getId()).isEmpty()) {
            settingsRepository.save(TenantSettings.create(tenant.getId()));
        }
    }
}
