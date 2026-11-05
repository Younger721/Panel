package com.nova.shared.async.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 声明式建 topic（{@code KafkaAdmin} 在 broker 可达时自动创建，已存在则跳过）。
 *
 * <p>收敛在代码里而非手工执行脚本，是为了让新环境（换机器、CI）启动即自愈；
 * 生产环境若由运维统一建 topic，把 {@code nova.kafka.auto-create-topics} 关掉即可。</p>
 */
@Configuration
@ConditionalOnProperty(name = "nova.job-queue", havingValue = "kafka")
public class KafkaTopicConfiguration {

    @Bean
    public NewTopic novaAsyncJobReadyTopic(KafkaJobQueueProperties properties) {
        return TopicBuilder.name(properties.getJobTopic())
                .partitions(properties.getJobTopicPartitions())
                .replicas(properties.getReplicas())
                .build();
    }

    @Bean
    public NewTopic novaAsyncJobDlqTopic(KafkaJobQueueProperties properties) {
        return TopicBuilder.name(properties.getDlqTopic())
                .partitions(1)
                .replicas(properties.getReplicas())
                .build();
    }
}
