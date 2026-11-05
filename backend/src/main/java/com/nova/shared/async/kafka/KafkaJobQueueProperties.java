package com.nova.shared.async.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Kafka 任务分发相关配置（{@code nova.kafka.*}）。
 *
 * <p>只描述「消息怎么走」，不描述 broker 怎么连——broker 地址仍由
 * {@code spring.kafka.bootstrap-servers} 控制，避免同一件事两个配置源。</p>
 */
@Component
@ConfigurationProperties(prefix = "nova.kafka")
public class KafkaJobQueueProperties {

    /** 新任务可被领取的唤醒 topic。 */
    private String jobTopic = "nova.async.job.ready";

    /** 连续失败达到上限后转人工的 topic。 */
    private String dlqTopic = "nova.async.job.dlq";

    /** 消费者组：同一组内的实例分摊分区，不重复消费。 */
    private String consumerGroup = "nova-ingest-workers";

    /** 唤醒 topic 分区数（本地单节点默认 3，但只有一个消费者实例时仍是顺序处理）。 */
    private int jobTopicPartitions = 3;

    /** 副本数：本地单 broker 只能是 1。 */
    private short replicas = 1;

    /** 是否在启动时自动创建 topic。 */
    private boolean autoCreateTopics = true;

    public String getJobTopic() {
        return jobTopic;
    }

    public void setJobTopic(String jobTopic) {
        this.jobTopic = jobTopic;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public void setDlqTopic(String dlqTopic) {
        this.dlqTopic = dlqTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getJobTopicPartitions() {
        return jobTopicPartitions;
    }

    public void setJobTopicPartitions(int jobTopicPartitions) {
        this.jobTopicPartitions = jobTopicPartitions;
    }

    public short getReplicas() {
        return replicas;
    }

    public void setReplicas(short replicas) {
        this.replicas = replicas;
    }

    public boolean isAutoCreateTopics() {
        return autoCreateTopics;
    }

    public void setAutoCreateTopics(boolean autoCreateTopics) {
        this.autoCreateTopics = autoCreateTopics;
    }
}
