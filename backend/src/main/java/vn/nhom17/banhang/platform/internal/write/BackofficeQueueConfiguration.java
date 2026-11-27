package vn.nhom17.banhang.platform.internal.write;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
class BackofficeQueueConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.backoffice.queue.publish-enabled", havingValue = "true")
    static class Publisher {
        @Bean
        DefaultKafkaProducerFactory<String, String> backofficeProducerFactory(KafkaProperties kafka,
                @Value("${app.backoffice.queue.publish-timeout-ms:10000}") long timeout) {
            if (timeout < 1 || timeout > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid publish timeout");
            Map<String, Object> props = new HashMap<>(kafka.buildProducerProperties());
            props.put(ProducerConfig.ACKS_CONFIG, "all");
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Math.min(timeout, 5000));
            props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(timeout, 5000));
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) timeout);
            return new DefaultKafkaProducerFactory<>(props);
        }

        @Bean
        KafkaTemplate<String, String> backofficeKafkaTemplate(
                @Qualifier("backofficeProducerFactory") DefaultKafkaProducerFactory<String, String> producer) {
            return new KafkaTemplate<>(producer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("${app.backoffice.queue.consume-enabled:false} or ${app.backoffice.queue.file-consume-enabled:false}")
    @EnableKafka
    @EnableScheduling
    static class Consumer {
        @Bean
        DefaultKafkaConsumerFactory<String, String> backofficeConsumerFactory(KafkaProperties kafka) {
            Map<String, Object> props = new HashMap<>(kafka.buildConsumerProperties());
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
            // Một batch nhập tối đa 1000 dòng có thể dài hơn thao tác JSON ngắn.
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000);
            return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> backofficeKafkaListenerContainerFactory(
                @Qualifier("backofficeConsumerFactory") DefaultKafkaConsumerFactory<String, String> consumer) {
            var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
            factory.setConsumerFactory(consumer);
            factory.setConcurrency(1);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            factory.getContainerProperties().setSyncCommits(true);
            var retry = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
            retry.setClassifications(Map.of(), true);
            retry.setAckAfterHandle(false);
            factory.setCommonErrorHandler(retry);
            return factory;
        }
    }
}
