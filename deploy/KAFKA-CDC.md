# TH-02 — Hàng đợi bán hàng, replica và kho báo cáo

Overlay `compose.kafka.yml` mở rộng bản bốn service trong `docker-compose.yml`. Cụm nghiệp vụ có đúng ba node PostgreSQL 18: `db` ghi, `db-replica-1` và `db-replica-2` nhận WAL vật lý và chỉ đọc. `warehouse` là database báo cáo riêng. Hai replica sao chép bất đồng bộ; đây chưa phải cấu hình tự động bầu primary hay chuyển đổi khi lỗi.

`backend` xác thực request rồi đẩy lệnh vào `sales.order-commands.v1`, khóa Kafka là UUID cửa hàng. `order-worker` dùng cùng image nhưng chỉ bật consumer, ghi giao dịch vào primary. Topic có ba partition để các cửa hàng có thể được xử lý độc lập; lệnh của một cửa hàng giữ thứ tự. Producer chờ `acks=all` và bật idempotence. Demo dùng một broker KRaft và replication factor/min ISR bằng 1; volume giữ dữ liệu qua restart nhưng một broker không bảo vệ được khi mất đĩa. Môi trường nhiều máy cần ba broker, replication factor 3, min ISR 2 và giao thức bảo mật thích hợp trước khi đổi cấu hình.

Debezium đọc logical WAL của bốn bảng `app.sales_order`, `app.sales_order_line`, `app.sales_return`, `app.sales_return_line`. Dữ liệu, metadata BEGIN/END, heartbeat và thông báo hoàn tất snapshot được chuyển vào `banhang.reporting`, duy nhất một partition. Không tăng số partition hoặc bật compact cho topic này. CDC worker áp dụng trọn giao dịch vào kho báo cáo và lưu checkpoint cùng transaction. Kafka Connect lưu cấu hình, offset và trạng thái trong ba topic compact riêng.

## Khởi động trên project riêng

Chạy từ gốc repo. Giữ quy trình proxy HTTPS trong [TH-02.md](TH-02.md). Tạo `deploy/kafka.local` từ `deploy/kafka.env.example`, đặt năm mật khẩu riêng. File này được quy tắc `*.local` loại khỏi Git. Dùng cùng bộ mật khẩu khi khởi động lại volume; đổi mật khẩu cần thao tác role tương ứng. Trên Windows chạy `deploy/check-docker-storage.ps1` trước khi pull/build; không dùng `reset-demo` để khởi tạo overlay này.

```sh
dc() {
  docker compose -p banhang-async --env-file deploy/.env --env-file deploy/kafka.local \
    -f deploy/docker-compose.yml -f deploy/compose.kafka.yml -f deploy/compose.server.yml "$@"
}
dc config -q
dc up -d --build --wait --wait-timeout 600
dc ps -a
```

Với PowerShell, thay hàm bằng `function dc { docker compose -p banhang-async --env-file deploy/.env --env-file deploy/kafka.local -f deploy/docker-compose.yml -f deploy/compose.kafka.yml -f deploy/compose.server.yml @args }`.

Đặt `WEB_PORT` thành cổng loopback còn trống và trỏ proxy vào cổng đó. Tên project và volume độc lập với bản demo có sẵn; không dùng tên project của bản demo để thử nghiệm. Chỉ web có cổng host. Kafka 9092, Connect 8083, primary, replica và warehouse đều chỉ ở trong mạng Compose. Khi dùng overlay Kafka để phát triển không có proxy, bỏ `compose.server.yml` và đặt cổng web thích hợp.

Các job `db-replication-setup`, `kafka-topics`, `cdc-publication`, `cdc-register` phải kết thúc với mã 0. `db-replication-setup` tạo/cập nhật hai role và hai physical slot; replica mới lấy `pg_basebackup -R`. Backend chạy Flyway trước khi `cdc-publication` tạo publication. Connector chỉ đăng ký sau khi publication và Connect sẵn sàng. Không xóa volume để nâng cấp; sao lưu trước migration. `dc stop` và `dc start` giữ queue, offset, dữ liệu nghiệp vụ và warehouse.

Tạo dữ liệu kiểm tra qua API/UI nghiệp vụ bình thường; job `seed` hiện chỉ kiểm tra cấu trúc fixture, chưa nạp dữ liệu. Dữ liệu mới sẽ đi qua WAL sang warehouse. Luồng bán bất đồng bộ cần đồng thời có `order-worker` chạy. UI nhận ACK của Kafka thì hoàn tất thao tác nhập đơn ngay; đơn xuất hiện trong danh sách sau khi worker ghi xong. Chấp nhận vào queue chưa đồng nghĩa giao dịch đã ghi xong; luồng POS không poll trạng thái từng yêu cầu.

## Biến môi trường ứng dụng

| Service | Cấu hình |
|---|---|
| API `backend` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `APP_SALES_QUEUE_PUBLISH_ENABLED=true`, `APP_SALES_QUEUE_CONSUME_ENABLED=false` |
| `order-worker` | Cùng Kafka; publish `false`, consume `true`; datasource ghi luôn là `db` |
| Đọc replica của API | `APP_DB_REPLICA_ENABLED=true`; `APP_DB_REPLICA_URLS` là hai JDBC URL phân cách bằng dấu phẩy; giữ login `app_backend` và RLS như primary |
| Kho báo cáo của API | `APP_REPORTING_WAREHOUSE_ENABLED=true`, URL `jdbc:postgresql://warehouse:5432/banhang_warehouse`, username `warehouse_reader`, password `DB_WAREHOUSE_READER_PASSWORD` |
| CDC worker | `KAFKA_BOOTSTRAP_SERVERS`, `CDC_TOPIC=banhang.reporting`, bộ `PGHOST`, `PGDATABASE`, `PGUSER=warehouse_writer`, `PGPASSWORD` |

Role `replica_stream` chỉ phục vụ physical replication. Role `cdc_reader` có `REPLICATION`, `BYPASSRLS` và `SELECT` trên đúng bốn bảng cần snapshot nhiều cửa hàng; không cấp quyền ghi hoặc quyền chủ bảng. Các role này không được dùng làm datasource request. API và worker tiếp tục dùng role nghiệp vụ bình thường. Replica không đáp ứng điều kiện replay/health thì đường đọc trở về primary. Kho báo cáo chưa hoàn tất snapshot hoặc mất độ mới cũng trở về primary. API chỉ chờ replica và warehouse khởi động container; không buộc chúng khỏe để API có thể khởi động lại và phục vụ từ primary trong lúc các bên đọc bị lỗi.

## Kiểm tra hoạt động

```sh
dc exec -T db psql -U postgres -d banhang -c "SELECT application_name, state, sync_state, replay_lsn FROM pg_stat_replication ORDER BY application_name"
dc exec -T db-replica-1 psql -U postgres -d banhang -c "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')"
dc exec -T db-replica-2 psql -U postgres -d banhang -c "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')"
dc exec -T connect curl -fsS http://localhost:8083/connectors/pos-reporting/status
dc exec -T cdc-worker python /app/worker.py --healthcheck
dc exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group sales-order-worker-v1
```

Primary phải thấy hai dòng `streaming`; hai replica trả `t` và `on`. Kiểm tra bán thực tế gồm: gửi lệnh, xác nhận UI xử lý ACK ngay, chờ đơn xuất hiện trong danh sách hoặc truy vấn SQL, đối chiếu đúng một đơn/biến động kho/điểm trên primary, rồi chờ warehouse cập nhật. Lỗi nghiệp vụ sau khi nhận queue được kiểm tra tại `GET /api/v1/sales/order-requests/failures`. Dừng worker sau khi Kafka nhận lệnh, bật lại và xác nhận không nhân đôi đơn. Dừng CDC worker, tạo giao dịch rồi bật lại để kiểm tra replay checkpoint. Kiểm tra cả đọc chéo hai cửa hàng và luồng qua HTTPS; smoke GET trong `deploy/smoke.py` chưa đủ đánh giá queue hoặc CDC.

## Giới hạn tài nguyên và cảnh báo

Docker log xoay vòng 10 MiB × 3/container; Kafka và Connect chỉ log stdout để không tạo kho log tệp riêng. Kafka heap tối đa 512 MiB, container tối đa 1 GiB; primary và mỗi replica dùng shared buffers 64 MiB, tối đa 512 MiB/container. Mỗi tiến trình PostgreSQL bị giới hạn tệp tạm 128 MiB. Các giới hạn này phù hợp demo nhỏ, cần đo lại trước khi tăng tải.

Hai topic dữ liệu giữ tối đa bảy ngày **hoặc** 256 MiB mỗi partition, điều kiện nào đến trước. Queue ba partition dùng khoảng 768 MiB cộng segment đang mở; reporting dùng khoảng 256 MiB cộng segment đang mở. Đây là giới hạn dung lượng, không phải cam kết giữ mọi lệnh đã nhận vô thời hạn. Theo dõi lag worker liên tục; cảnh báo khi có lag kéo dài hoặc worker ngừng chạy, xử lý ngay trước khi vượt retention. Trong vận hành, lịch giám sát gọi script sau và phát cảnh báo khi mã thoát khác 0:

```sh
python3 deploy/kafka/check-capacity.py --project banhang-async --env-file deploy/.env --env-file deploy/kafka.local
```

Script cảnh báo từ 80% giới hạn từng partition; kiểm tra lag bằng lệnh consumer group ở trên để xác định phần chưa xử lý. Không tăng retention trước khi kiểm tra dung lượng đĩa. Không purge queue để chữa lag; khôi phục worker rồi đối chiếu đơn và danh sách yêu cầu lỗi. Nếu lệnh đã vượt retention, đối chiếu offset/biên nhận của yêu cầu với primary để xử lý/retry có kiểm soát bằng cùng Idempotency-Key; không mặc định coi là đã hoàn tất.

PostgreSQL đặt `max_slot_wal_keep_size=512MB`, `max_wal_size=256MB` và `wal_keep_size=64MB`. WAL có thể vượt ngưỡng đến checkpoint và các giới hạn WAL không giới hạn kích thước toàn database. Kiểm tra slot thường xuyên:

```sh
dc exec -T db psql -U postgres -d banhang -c "SELECT slot_name, slot_type, active, wal_status, invalidation_reason, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots"
```

Cảnh báo khi slot không active kéo dài, `wal_status` sang `unreserved`/`lost`, hoặc WAL giữ lại đạt 400 MiB. Slot hết WAL cần dừng bên tiêu thụ bị ảnh hưởng và nạp lại replica/snapshot có kế hoạch; script không tự xóa dữ liệu hoặc slot. Giữ backup primary và kiểm tra `docker system df`/ổ đĩa định kỳ. Chỉ xóa volume thuộc project thử nghiệm sau khi xác định không chứa dữ liệu cần giữ.

Các lựa chọn được đối chiếu với [PostgreSQL 18 pg_basebackup](https://www.postgresql.org/docs/18/app-pgbasebackup.html), [giới hạn replication slot](https://www.postgresql.org/docs/18/runtime-config-replication.html), [Debezium 3.4 hỗ trợ PostgreSQL 18](https://debezium.io/releases/3.4/), [thông báo snapshot](https://debezium.io/documentation/reference/3.4/configuration/notification.html) và [cấu hình Docker Kafka](https://github.com/apache/kafka/blob/4.1.1/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml).
