package vn.nhom17.banhang.platform.internal.write;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.nhom17.banhang.platform.BackofficeCommandLane;

/** Hai listener/group độc lập; mỗi partition giữ thứ tự cửa hàng của riêng luồng đó. */
final class BackofficeCommandListeners {
    private BackofficeCommandListeners() {}

    @Component
    @ConditionalOnProperty(name = "app.backoffice.queue.consume-enabled", havingValue = "true")
    static class Quick {
        private final BackofficeCommandProcessor processor;
        private final JsonMapper json;
        Quick(BackofficeCommandProcessor processor, JsonMapper json) { this.processor = processor; this.json = json; }

        @KafkaListener(id = "backoffice-write-worker", topics = "${app.backoffice.queue.topic:backoffice.write-commands.v1}",
                groupId = "${app.backoffice.queue.consumer-group:backoffice-write-worker-v1}",
                containerFactory = "backofficeKafkaListenerContainerFactory",
                autoStartup = "${app.backoffice.queue.auto-startup:true}")
        void receive(ConsumerRecord<String, String> record, Acknowledgment ack) {
            process(record, ack, processor, json, BackofficeCommandLane.QUICK);
        }
    }

    @Component
    @ConditionalOnProperty(name = "app.backoffice.queue.file-consume-enabled", havingValue = "true")
    static class File {
        private final BackofficeCommandProcessor processor;
        private final JsonMapper json;
        File(BackofficeCommandProcessor processor, JsonMapper json) { this.processor = processor; this.json = json; }

        @KafkaListener(id = "backoffice-file-worker", topics = "${app.backoffice.queue.file-topic:backoffice.file-commands.v1}",
                groupId = "${app.backoffice.queue.file-consumer-group:backoffice-file-worker-v1}",
                containerFactory = "backofficeKafkaListenerContainerFactory",
                autoStartup = "${app.backoffice.queue.auto-startup:true}")
        void receive(ConsumerRecord<String, String> record, Acknowledgment ack) {
            process(record, ack, processor, json, BackofficeCommandLane.FILE);
        }
    }

    private static void process(ConsumerRecord<String, String> record, Acknowledgment ack,
            BackofficeCommandProcessor processor, JsonMapper json, BackofficeCommandLane lane) {
        var command = json.readValue(record.value(), BackofficeCommand.class);
        if (command.storeId() == null || !command.storeId().toString().equals(record.key())) {
            throw new IllegalArgumentException("Kafka command key does not match storeId");
        }
        processor.process(command, lane);
        // Đã commit tác dụng nghiệp vụ và kết quả cuối trước ACK; crash tại đây phát lại an toàn.
        ack.acknowledge();
    }
}
