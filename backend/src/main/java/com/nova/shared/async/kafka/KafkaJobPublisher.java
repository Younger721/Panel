package com.nova.shared.async.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 发「有新任务」的唤醒消息。
 *
 * <p>设计要点：<b>发送失败只记日志，不抛出</b>。任务状态已经落在 {@code async_job}
 * 表里（PENDING），Kafka 只是把 5 秒的轮询延迟压到毫秒级；发不出去最多退化成轮询，
 * 不能让入队主流程失败。</p>
 */
@Component
public class KafkaJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaJobPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final KafkaJobQueueProperties properties;

    public KafkaJobPublisher(KafkaTemplate<String, String> kafka, KafkaJobQueueProperties properties) {
        this.kafka = kafka;
        this.properties = properties;
    }

    /**
     * @param jobId    任务主键，消费端据此从 DB 抢租约
     * @param tenantId 用作消息 key：同一租户落同一分区，保证该租户任务的处理顺序
     */
    public void wake(Long jobId, Long tenantId) {
        if (jobId == null) {
            return;
        }
        String key = tenantId == null ? "0" : tenantId.toString();
        String payload = "{\"jobId\":" + jobId + "}";
        try {
            kafka.send(properties.getJobTopic(), key, payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // broker 不可达时异常只会出现在 future 里，不会抛到调用方
                            log.warn("Kafka wake-up failed for job {} (polling fallback will pick it up): {}",
                                    jobId, ex.toString());
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("Kafka wake-up rejected for job {} (polling fallback will pick it up): {}", jobId, ex.toString());
        }
    }

    /**
     * 任务达到最大重试次数后投递死信，便于人工排查/重放。
     * 与唤醒消息一样：失败只记日志，DB 里的 FAILED 状态才是权威记录。
     */
    public void deadLetter(Long jobId, String type, String error) {
        if (jobId == null) {
            return;
        }
        String payload = "{\"jobId\":" + jobId
                + ",\"type\":\"" + (type == null ? "" : type.replace("\"", "'"))
                + "\",\"error\":\"" + (error == null ? "" : error.replace("\"", "'").replace("\n", " "))
                + "\"}";
        try {
            kafka.send(properties.getDlqTopic(), String.valueOf(jobId), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Kafka DLQ publish failed for job {}: {}", jobId, ex.toString());
                        }
                    });
        } catch (RuntimeException ex) {
            log.warn("Kafka DLQ publish rejected for job {}: {}", jobId, ex.toString());
        }
    }
}
