package com.agentic.analytics;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic clickTopic(@Value("${app.kafka.click-topic}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic(@Value("${app.kafka.dead-letter-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    /** Three quick retries, then park the record on the DLT so one poison message never blocks the partition. */
    @Bean
    DefaultErrorHandler errorHandler(KafkaOperations<Object, Object> template,
                                     @Value("${app.kafka.dead-letter-topic}") String dlt) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (rec, ex) -> new TopicPartition(dlt, 0));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3));
    }
}
