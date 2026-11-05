package com.nova.agent.infrastructure.persistence;

import com.nova.agent.domain.model.TenantAgentPromptHistory;
import com.nova.agent.domain.repository.TenantAgentPromptHistoryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
class JpaTenantAgentPromptHistoryRepository implements TenantAgentPromptHistoryRepository {

    private final SpringDataTenantAgentPromptHistoryRepository delegate;

    JpaTenantAgentPromptHistoryRepository(SpringDataTenantAgentPromptHistoryRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public TenantAgentPromptHistory save(TenantAgentPromptHistory history) {
        return delegate.save(history);
    }

    @Override
    public List<TenantAgentPromptHistory> findAllByAgentIdOrderByRevisionDesc(Long agentId) {
        return delegate.findAllByAgentIdOrderByRevisionDesc(agentId);
    }

    @Override
    public List<TenantAgentPromptHistory> findTop20ByAgentIdOrderByRevisionDesc(Long agentId) {
        return delegate.findTop20ByAgentIdOrderByRevisionDesc(agentId);
    }

    @Override
    public Optional<TenantAgentPromptHistory> findByAgentIdAndRevision(Long agentId, int revision) {
        return delegate.findByAgentIdAndRevision(agentId, revision);
    }

    @Override
    public int findMaxRevision(Long agentId) {
        return delegate.findMaxRevision(agentId);
    }

    @Override
    public void deleteByAgentIdAndRevisionLessThan(Long agentId, int revision) {
        delegate.deleteByAgentIdAndRevisionLessThan(agentId, revision);
    }
}
