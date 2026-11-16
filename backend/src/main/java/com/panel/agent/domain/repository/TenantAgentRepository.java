package com.panel.agent.domain.repository;

import com.panel.agent.domain.model.AgentStatus;
import com.panel.agent.domain.model.TenantAgent;

import java.util.List;
import java.util.Optional;

public interface TenantAgentRepository {
    TenantAgent save(TenantAgent agent);

    /** 按主键查找（平台管理员跨门店操作时使用，调用方负责权限判断） */
    Optional<TenantAgent> findById(Long id);

    Optional<TenantAgent> findByTenantIdAndId(Long tenantId, Long id);

    List<TenantAgent> findAllByTenantId(Long tenantId);

    List<TenantAgent> findAllByTenantIdAndStatus(Long tenantId, AgentStatus status);

    List<TenantAgent> findTop200ByOrderByIdDesc();

    long countByTenantId(Long tenantId);

}
