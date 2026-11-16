package com.panel.knowledge.application;

import com.panel.shared.async.AsyncJobDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 唤醒消息的消费端。
 *
 * <p>刻意忽略消息体内容：消息只表示「有任务可干了」，真正的任务来自
 * {@code async_job} 的 {@code claimNext}。于是重复投递、乱序、甚至伪造的消息体
 * 都不会破坏状态机——最多多做一次空转查询。</p>
 *
 * <p>交给 {@link AsyncJobDispatcher} 而不是某个具体 worker：同一张任务表上有
 * 知识入库、简历解析等多类任务，按类型路由才不会互相误领。</p>
 */
@Component
@ConditionalOnProperty(name = "panel.job-queue", havingValue = "kafka")
public class KnowledgeIngestKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestKafkaListener.class);

    private final AsyncJobDispatcher dispatcher;

    public KnowledgeIngestKafkaListener(AsyncJobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(
            topics = "${panel.kafka.job-topic:panel.async.job.ready}",
            groupId = "${panel.kafka.consumer-group:panel-ingest-workers}")
    public void onJobReady(String payload) {
        log.debug("Wake-up received: {}", payload);
        dispatcher.pollOnce();
    }
}
