package com.panel.shared.async;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * MySQL-backed queue. State lives in {@code async_job}; claim uses {@code SKIP LOCKED}
 * so workers never wait on each other. All SQL is shared with the Kafka queue through
 * {@link JdbcJobStateStore}, so both modes behave identically.
 */
@Component
@ConditionalOnProperty(name = "panel.job-queue", havingValue = "mysql")
public class MySqlPersistentJobQueue implements PersistentJobQueue {

    private final JdbcJobStateStore store;

    public MySqlPersistentJobQueue(JdbcJobStateStore store) {
        this.store = store;
    }

    @Override
    public Long enqueue(String type, Long tenantId) {
        return store.enqueue(type, tenantId);
    }

    @Override
    public Optional<PersistentJob> claimNext(Instant now, Duration lease) {
        return store.claimNext(now, lease);
    }

    @Override
    public void advance(Long id) {
        store.advance(id);
    }

    @Override
    public void retry(Long id, String error, Instant now) {
        store.retry(id, error, now);
    }

    @Override
    public void complete(Long id) {
        store.complete(id);
    }

    @Override
    public void recoverExpired(Instant now) {
        store.recoverExpired(now);
    }

    @Override
    public PersistentJob find(Long id) {
        return store.find(id);
    }
}
