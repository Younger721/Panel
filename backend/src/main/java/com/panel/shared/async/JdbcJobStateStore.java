package com.panel.shared.async;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@code async_job} 表的状态操作，供多种队列实现共用（mysql / kafka）。
 *
 * <p>把 SQL 收在一处，是为了让不同队列实现共享同一套语义：</p>
 * <ul>
 *   <li><b>幂等入队</b>：同一 {@code (type, tenantId)} 只允许一个 PENDING/RUNNING 任务，
 *       终态任务会被重置重放；并发入队靠 MySQL 命名锁串行化。</li>
 *   <li><b>租约领取</b>：{@code FOR UPDATE SKIP LOCKED}，多实例/多线程不会领到同一行。</li>
 *   <li><b>退避重试</b>：{@code base << (attempts-1)}，达到上限转 FAILED。</li>
 * </ul>
 *
 * <p>注意：{@code GET_LOCK/RELEASE_LOCK} 是连接级的，两个调用必须落在同一条连接上，
 * 所以 {@link #enqueue} 必须是事务方法。</p>
 */
@Component
public class JdbcJobStateStore {

    private static final String SELECT_COLUMNS =
            "select id,type,tenant_id,status,attempts,next_attempt_at,lease_until,last_error from async_job";

    private final JdbcTemplate jdbc;

    public JdbcJobStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Long enqueue(String type, Long tenantId) {
        validate(type, tenantId);
        String normalizedType = type.trim();
        long queueTenantId = tenantId == null ? 0L : tenantId;
        String lockName = "panel:job:" + queueTenantId + ":" + normalizedType;
        Boolean locked = jdbc.queryForObject("select get_lock(?, 10)", Boolean.class, lockName);
        if (!Boolean.TRUE.equals(locked)) {
            throw new IllegalStateException("Unable to acquire job enqueue lock");
        }
        try {
            Long existing = findByStatus(normalizedType, queueTenantId,
                    JobStatus.PENDING.name(), JobStatus.RUNNING.name());
            if (existing != null) {
                return existing;
            }
            Long terminal = findByStatus(normalizedType, queueTenantId,
                    JobStatus.COMPLETED.name(), JobStatus.FAILED.name());
            if (terminal != null) {
                jdbc.update("update async_job set status=?, attempts=0, next_attempt_at=now(6), lease_until=null, last_error=null where id=?",
                        JobStatus.PENDING.name(), terminal);
                return terminal;
            }
            // 用数据库 now(6) 而不是 JVM 的 Instant.EPOCH：TIMESTAMP 列在 UTC 会话下
            // 不接受 1970-01-01 00:00:00（MySQL 1292），且让"可领取时间"与库内时钟一致。
            jdbc.update("insert into async_job(type,tenant_id,status,attempts,next_attempt_at) values(?,?,?,0,now(6))",
                    normalizedType, queueTenantId, JobStatus.PENDING.name());
            return jdbc.queryForObject("select last_insert_id()", Long.class);
        } finally {
            jdbc.queryForObject("select release_lock(?)", Integer.class, lockName);
        }
    }

    @Transactional
    public Optional<PersistentJob> claimNext(Instant now, Duration lease) {
        if (now == null || lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("invalid lease");
        }
        List<PersistentJob> rows = jdbc.query(
                SELECT_COLUMNS + " where status=? and next_attempt_at<=? order by id limit 1 for update skip locked",
                (rs, n) -> row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getInt(5), rs.getTimestamp(6), rs.getTimestamp(7), rs.getString(8)),
                JobStatus.PENDING.name(), Timestamp.from(now));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        PersistentJob job = rows.get(0);
        Instant leaseUntil = now.plus(lease);
        jdbc.update("update async_job set status=?,lease_until=? where id=?",
                JobStatus.RUNNING.name(), Timestamp.from(leaseUntil), job.id());
        return Optional.of(new PersistentJob(job.id(), job.type(), job.tenantId(), JobStatus.RUNNING,
                job.attempts(), job.nextAttemptAt(), leaseUntil, job.lastError()));
    }

    public void advance(Long id) {
        updateStatus(id, JobStatus.RUNNING);
    }

    /** 默认策略：最多 5 次，退避 30s << (attempts-1)。 */
    public void retry(Long id, String error, Instant now) {
        retry(id, error, now, 5, 30L);
    }

    public void retry(Long id, String error, Instant now, int maxAttempts, long retryBaseSeconds) {
        PersistentJob job = find(id);
        int attempts = job.attempts() + 1;
        JobStatus status = attempts >= maxAttempts ? JobStatus.FAILED : JobStatus.PENDING;
        long delaySeconds = retryBaseSeconds << Math.max(0, attempts - 1);
        jdbc.update("update async_job set status=?,attempts=?,next_attempt_at=?,lease_until=null,last_error=? where id=?",
                status.name(), attempts, Timestamp.from(now.plusSeconds(delaySeconds)), error, id);
    }

    public void complete(Long id) {
        jdbc.update("update async_job set status=?,lease_until=null where id=?",
                JobStatus.COMPLETED.name(), id);
    }

    public void recoverExpired(Instant now) {
        jdbc.update("update async_job set status=?,lease_until=null where status=? and lease_until<=?",
                JobStatus.PENDING.name(), JobStatus.RUNNING.name(), Timestamp.from(now));
    }

    public PersistentJob find(Long id) {
        return jdbc.queryForObject(SELECT_COLUMNS + " where id=?",
                (rs, n) -> row(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getInt(5), rs.getTimestamp(6), rs.getTimestamp(7), rs.getString(8)), id);
    }

    /** 该任务是否仍处于可领取状态（Kafka 重复消息进来时先判一次，省一次空转）。 */
    public boolean isPending(Long id) {
        try {
            String status = jdbc.queryForObject("select status from async_job where id=?", String.class, id);
            return JobStatus.PENDING.name().equals(status);
        } catch (EmptyResultDataAccessException ex) {
            return false;
        }
    }

    private Long findByStatus(String type, long tenantId, String firstStatus, String secondStatus) {
        try {
            return jdbc.queryForObject(
                    "select id from async_job where type=? and tenant_id=? and status in (?,?) order by id limit 1",
                    Long.class, type, tenantId, firstStatus, secondStatus);
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    private void updateStatus(Long id, JobStatus status) {
        if (jdbc.update("update async_job set status=? where id=?", status.name(), id) == 0) {
            throw new NoSuchElementException("job");
        }
    }

    private static PersistentJob row(long id, String type, long tenant, String status, int attempts,
                                     Timestamp next, Timestamp lease, String error) {
        return new PersistentJob(id, type, tenant == 0 ? null : tenant, JobStatus.valueOf(status), attempts,
                next == null ? null : next.toInstant(), lease == null ? null : lease.toInstant(), error);
    }

    private static void validate(String type, Long tenant) {
        if (type == null || type.isBlank() || tenant != null && tenant <= 0) {
            throw new IllegalArgumentException("invalid job");
        }
    }
}
