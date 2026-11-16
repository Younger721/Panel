package com.panel.tenancy.domain.repository;

import com.panel.tenancy.domain.model.TenantSettings;

import java.util.Optional;

public interface TenantSettingsRepository {

    TenantSettings save(TenantSettings settings);

    Optional<TenantSettings> findByTenantId(Long tenantId);
}
