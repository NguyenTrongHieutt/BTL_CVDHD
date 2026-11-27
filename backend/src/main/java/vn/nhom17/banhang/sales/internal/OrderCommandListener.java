package vn.nhom17.banhang.sales.internal;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "app.sales.queue.consume-enabled", havingValue = "true")
class OrderCommandListener {
    private final OrderCommandProcessor processor;
    private final JsonMapper json;

    OrderCommandListener(OrderCommandProcessor processor, JsonMapper json) {
        this.processor = processor;
        this.json = json;
    }

    @KafkaListener(id = "sales-order-worker", topics = "${app.sales.queue.topic:sales.order-commands.v1}",
            groupId = "${app.sales.queue.consumer-group:sales-order-worker-v1}",
            containerFactory = "salesOrderKafkaListenerContainerFactory",
            autoStartup = "${app.sales.queue.auto-startup:true}")
    void receive(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        OrderCommand command = json.readValue(record.value(), OrderCommand.class);
        if (!command.storeId().toString().equals(record.key())) {
            throw new IllegalArgumentException("Kafka command key không khớp storeId");
        }
        processor.process(command);
        // createQueued/rejectQueued đã commit primary. Crash ngay trước bước này chỉ phát lại cùng khóa.
        acknowledgment.acknowledge();
    }
}
