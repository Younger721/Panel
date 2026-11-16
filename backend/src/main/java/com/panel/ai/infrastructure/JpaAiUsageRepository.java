package com.panel.ai.infrastructure;

import com.panel.ai.application.AiUsageRecord;
import com.panel.ai.application.AiUsageRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JpaAiUsageRepository implements AiUsageRepository {
    private final SpringDataAiUsageRepository delegate;

    public JpaAiUsageRepository(SpringDataAiUsageRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public AiUsageRecord save(AiUsageRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("usage record required");
        }
        // tenant 允许为空：后台任务（简历解析）没有请求上下文，只知道数据属于谁。
        // 早期这里强制要求 tenant，于是后台链路一条用量都落不了库——调用量最大的一块
        // （切分 + 逐模块批注）在成本看板里完全隐形。user_id 仍然必须有，
        // 保证每条记录都能追溯到具体的人，随时可以反查租户。
        if (record.userId() == null) {
            throw new IllegalArgumentException("usage record needs userId for traceability");
        }
        return delegate.save(AiUsageEntity.from(record)).toRecord();
    }

    @Override
    public List<AiUsageRecord> findAll() {
        return delegate.findAllByOrderByCreatedAtDesc().stream()
                .map(AiUsageEntity::toRecord).toList();
    }

    @Override
    public List<AiUsageRecord> findAllByTenantId(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        return delegate.findAllByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(AiUsageEntity::toRecord).toList();
    }
}
