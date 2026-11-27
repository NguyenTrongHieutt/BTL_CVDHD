package vn.nhom17.banhang.sales.internal;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaProperties.class)
class OrderQueueConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.sales.queue.publish-enabled", havingValue = "true")
    static class Publisher {
        @Bean
        DefaultKafkaProducerFactory<String, String> salesOrderProducerFactory(KafkaProperties kafka,
                @Value("${app.sales.queue.publish-timeout-ms:10000}") long timeoutMillis,
                ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {
            if (timeoutMillis < 1 || timeoutMillis > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("app.sales.queue.publish-timeout-ms không hợp lệ");
            }
            Map<String, Object> properties = new HashMap<>(kafka.buildProducerProperties());
            // Không cho cấu hình môi trường vô tình hạ mức xác nhận của API xuống leader-only/no-ack.
            properties.put(ProducerConfig.ACKS_CONFIG, "all");
            properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
            properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            properties.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
            properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            properties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Math.min(timeoutMillis, 5000));
            properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(timeoutMillis, 5000));
            properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) timeoutMillis);
            var factory = new DefaultKafkaProducerFactory<String, String>(properties);
            customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
            return factory;
        }

        @Bean
        KafkaTemplate<String, String> salesOrderKafkaTemplate(
                @Qualifier("salesOrderProducerFactory") DefaultKafkaProducerFactory<String, String> factory) {
            return new KafkaTemplate<>(factory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.sales.queue.consume-enabled", havingValue = "true")
    @EnableKafka
    static class Consumer {
        @Bean
        DefaultKafkaConsumerFactory<String, String> salesOrderConsumerFactory(KafkaProperties kafka,
                @Value("${app.sales.queue.consumer-group:sales-order-worker-v1}") String group,
                ObjectProvider<DefaultKafkaConsumerFactoryCustomizer> customizers) {
            Map<String, Object> properties = new HashMap<>(kafka.buildConsumerProperties());
            properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
            var factory = new DefaultKafkaConsumerFactory<>(properties, new StringDeserializer(), new StringDeserializer());
            customizers.orderedStream().forEach(customizer -> customizer.customize(factory));
            return factory;
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> salesOrderKafkaListenerContainerFactory(
                @Qualifier("salesOrderConsumerFactory") DefaultKafkaConsumerFactory<String, String> factory) {
            var container = new ConcurrentKafkaListenerContainerFactory<String, String>();
            container.setConsumerFactory(factory);
            container.setConcurrency(1);
            container.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            container.getContainerProperties().setSyncCommits(true);
            // Giữ lệnh lỗi tại partition hiện tại, không đưa sang retry topic làm đảo thứ tự cửa hàng.
            var retry = new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
            retry.setClassifications(Map.of(), true);
            retry.setAckAfterHandle(false);
            container.setCommonErrorHandler(retry);
            return container;
        }
    }
}
