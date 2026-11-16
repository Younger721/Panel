package com.panel.ai.harness.infrastructure;

import com.panel.ai.harness.domain.PlatformHarnessConfig;
import com.panel.ai.harness.domain.PlatformHarnessConfigRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
class JpaPlatformHarnessConfigRepository implements PlatformHarnessConfigRepository {

    private final SpringDataPlatformHarnessConfigRepository delegate;

    JpaPlatformHarnessConfigRepository(SpringDataPlatformHarnessConfigRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public PlatformHarnessConfig save(PlatformHarnessConfig config) {
        return delegate.save(config);
    }

    @Override
    public Optional<PlatformHarnessConfig> findSingleton() {
        return delegate.findById(PlatformHarnessConfig.SINGLETON_ID);
    }
}
