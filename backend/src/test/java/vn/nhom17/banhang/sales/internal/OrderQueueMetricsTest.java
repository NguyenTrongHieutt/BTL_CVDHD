package vn.nhom17.banhang.sales.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.metrics.KafkaMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;

class OrderQueueMetricsTest {
    @Test
    void customFactoriesUseTheBootNativeMetricsListeners() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(OrderQueueConfiguration.class)
                .withPropertyValues("app.sales.queue.publish-enabled=true", "app.sales.queue.consume-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer = context.getBean("salesOrderProducerFactory", DefaultKafkaProducerFactory.class);
                    var consumer = context.getBean("salesOrderConsumerFactory", DefaultKafkaConsumerFactory.class);
                    assertThat(producer.getListeners()).hasSize(1).first().isInstanceOf(MicrometerProducerListener.class);
                    assertThat(consumer.getListeners()).hasSize(1).first().isInstanceOf(MicrometerConsumerListener.class);
                });
    }
}
