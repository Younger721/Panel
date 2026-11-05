package com.nova.audit.domain.repository;

import com.nova.audit.domain.OperationAudit;

import java.util.List;

public interface OperationAuditRepository {
    OperationAudit save(OperationAudit audit);
    List<OperationAudit> findAll();
    List<OperationAudit> findAllByTenantId(Long tenantId);
}
