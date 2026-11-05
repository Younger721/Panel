package com.nova.agent.infrastructure.persistence;

import com.nova.agent.domain.model.TenantAgentPromptHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface SpringDataTenantAgentPromptHistoryRepository extends JpaRepository<TenantAgentPromptHistory, Long> {

    List<TenantAgentPromptHistory> findAllByAgentIdOrderByRevisionDesc(Long agentId);

    List<TenantAgentPromptHistory> findTop20ByAgentIdOrderByRevisionDesc(Long agentId);

    Optional<TenantAgentPromptHistory> findByAgentIdAndRevision(Long agentId, int revision);

    @Query("select coalesce(max(h.revision), 0) from TenantAgentPromptHistory h where h.agentId = :agentId")
    int findMaxRevision(@Param("agentId") Long agentId);

    void deleteByAgentIdAndRevisionLessThan(Long agentId, int revision);
}
