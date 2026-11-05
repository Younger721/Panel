package com.nova.audit.infrastructure.persistence;

import com.nova.audit.domain.OperationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SpringDataOperationAuditRepository extends JpaRepository<OperationAudit, Long> {
    List<OperationAudit> findAllByOrderByCreatedAtDesc();
    List<OperationAudit> findAllByTenantId(Long tenantId);
}
