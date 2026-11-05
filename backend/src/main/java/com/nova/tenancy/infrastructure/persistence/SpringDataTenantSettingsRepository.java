package com.nova.tenancy.infrastructure.persistence;

import com.nova.tenancy.domain.model.TenantSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataTenantSettingsRepository extends JpaRepository<TenantSettings, Long> {

    Optional<TenantSettings> findByTenantId(Long tenantId);
}
