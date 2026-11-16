package com.panel.tenancy.domain.repository;

import com.panel.tenancy.domain.model.TenantQuota;

import java.util.Optional;

public interface TenantQuotaRepository {
    TenantQuota save(TenantQuota quota);

    Optional<TenantQuota> findByTenantId(Long tenantId);
}
