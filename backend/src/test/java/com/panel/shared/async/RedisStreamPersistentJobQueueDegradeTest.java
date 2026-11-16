package com.panel.shared.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本机 Redis 是 3.2（没有 Streams），XADD / XGROUP 会报 unknown command。
 * 之前这个异常冒到调度线程顶层把整个进程带走，所以这里钉住降级行为：
 * 不抛异常、改走 ZSET 索引，任务照样能被领取。
 */
class RedisStreamPersistentJobQueueDegradeTest {

    private static final String XADD_ERROR = "ERR unknown command 'XADD'";

    private record Fixture(StringRedisTemplate redis, ZSetOperations<String, String> zsets) {
    }

    @SuppressWarnings("unchecked")
    private Fixture brokenStreams() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);

        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn((HashOperations) hashes);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(redis.opsForStream()).thenReturn(streams);

        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenReturn(1L);
        when(streams.add(any())).thenThrow(new RedisSystemException("xadd", new RuntimeException(XADD_ERROR)));
        when(streams.createGroup(anyString(), any(), anyString()))
                .thenThrow(new RedisSystemException("xgroup", new RuntimeException(XADD_ERROR)));

        // 任务元数据落在 Hash 里，第一次进 ZSET 待领
        when(hashes.entries(anyString())).thenReturn(Map.of(
                "type", "knowledge.ingest",
                "tenantId", "1",
                "status", "PENDING",
                "attempts", "0",
                "nextAttemptAt", "0"));
        when(zsets.range(anyString(), anyLong(), anyLong())).thenReturn(Set.of("1"));
        return new Fixture(redis, zsets);
    }

    @Test
    @DisplayName("Streams 不可用时 enqueue 不抛异常，任务改投 ZSET 索引")
    void enqueueSurvivesBrokenStreams() {
        Fixture fixture = brokenStreams();
        var queue = new RedisStreamPersistentJobQueue(fixture.redis(), "dev", 5, 30);

        assertThatCode(() -> queue.enqueue("knowledge.ingest", 1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Streams 不可用时 claimNext 从 ZSET 取任务并置为 RUNNING")
    void claimNextFallsBackToReadySet() {
        Fixture fixture = brokenStreams();
        var queue = new RedisStreamPersistentJobQueue(fixture.redis(), "dev", 5, 30);

        Optional<PersistentJob> claimed = queue.claimNext(Instant.now(), Duration.ofMinutes(5));

        assertThat(claimed).isPresent();
        assertThat(claimed.get().id()).isEqualTo(1L);
        assertThat(claimed.get().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.get().type()).isEqualTo("knowledge.ingest");
    }
}
