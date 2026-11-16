package com.panel.tenancy.application;

import com.panel.tenancy.application.port.TenantProvisioningContributor;
import com.panel.tenancy.domain.model.Tenant;
import com.panel.tenancy.domain.model.TenantSettings;
import com.panel.tenancy.domain.repository.TenantSettingsRepository;
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
