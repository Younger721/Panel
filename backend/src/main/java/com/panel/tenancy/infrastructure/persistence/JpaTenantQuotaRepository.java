package com.panel.tenancy.infrastructure.persistence;

import com.panel.tenancy.domain.model.TenantQuota;
import com.panel.tenancy.domain.repository.TenantQuotaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class JpaTenantQuotaRepository implements TenantQuotaRepository {

    private final SpringDataTenantQuotaRepository delegate;

    JpaTenantQuotaRepository(SpringDataTenantQuotaRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public TenantQuota save(TenantQuota quota) {
        return delegate.save(quota);
    }

    @Override
    public Optional<TenantQuota> findByTenantId(Long tenantId) {
        return delegate.findByTenantId(tenantId);
    }
}
