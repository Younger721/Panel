package com.panel.audit.domain.repository;

import com.panel.audit.domain.OperationAudit;

import java.util.List;

public interface OperationAuditRepository {
    OperationAudit save(OperationAudit audit);
    List<OperationAudit> findAll();
    List<OperationAudit> findAllByTenantId(Long tenantId);
}
