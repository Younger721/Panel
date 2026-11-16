package com.panel.tenancy.infrastructure.persistence;

import com.panel.tenancy.domain.model.TenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataTenantQuotaRepository extends JpaRepository<TenantQuota, Long> {
    Optional<TenantQuota> findByTenantId(Long tenantId);
}
