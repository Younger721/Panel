package com.nova.tenancy.application;

import com.nova.tenancy.application.port.TenantProvisioningContributor;
import com.nova.tenancy.domain.model.Tenant;
import com.nova.tenancy.domain.model.TenantQuota;
import com.nova.tenancy.domain.repository.TenantQuotaRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(150)
public class TenantQuotaProvisioningContributor implements TenantProvisioningContributor {

    private final TenantQuotaRepository quotas;

    public TenantQuotaProvisioningContributor(TenantQuotaRepository quotas) {
        this.quotas = quotas;
    }

    @Override
    public void contribute(Tenant tenant) {
        if (quotas.findByTenantId(tenant.getId()).isEmpty()) {
            quotas.save(TenantQuota.defaults(tenant.getId()));
        }
    }
}
