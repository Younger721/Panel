package com.panel.agent.infrastructure.persistence;

import com.panel.agent.domain.model.AgentStatus;
import com.panel.agent.domain.model.TenantAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SpringDataTenantAgentRepository extends JpaRepository<TenantAgent, Long> {
    Optional<TenantAgent> findByTenantIdAndId(Long tenantId, Long id);
    List<TenantAgent> findAllByTenantId(Long tenantId);

    List<TenantAgent> findAllByTenantIdAndStatus(Long tenantId, AgentStatus status);

    long countByTenantId(Long tenantId);

    List<TenantAgent> findTop200ByOrderByIdDesc();
}
