# TH-02 — Hàng đợi bán hàng, replica và kho báo cáo

Overlay `compose.kafka.yml` mở rộng bản bốn service trong `docker-compose.yml`. Cụm nghiệp vụ có đúng ba node PostgreSQL 18: `db` ghi, `db-replica-1` và `db-replica-2` nhận WAL vật lý và chỉ đọc. `warehouse` là database báo cáo riêng. Hai replica sao chép bất đồng bộ; đây chưa phải cấu hình tự động bầu primary hay chuyển đổi khi lỗi.

`backend` xác thực request rồi đẩy lệnh vào `sales.order-commands.v1`, khóa Kafka là UUID cửa hàng. `order-worker` dùng cùng image nhưng chỉ bật consumer, ghi giao dịch vào primary. Topic có ba partition để các cửa hàng có thể được xử lý độc lập; lệnh của một cửa hàng giữ thứ tự. Producer chờ `acks=all` và bật idempotence. Demo dùng một broker KRaft và replication factor/min ISR bằng 1; volume giữ dữ liệu qua restart nhưng một broker không bảo vệ được khi mất đĩa. Môi trường nhiều máy cần ba broker, replication factor 3, min ISR 2 và giao thức bảo mật thích hợp trước khi đổi cấu hình.

`backoffice-worker` ghi thay đổi sản phẩm, biến thể, khách hàng, nhập kho, kiểm kê và ngưỡng tồn. Lệnh JSON vào `backoffice.write-commands.v1`. Xem trước và xác nhận nhập CSV/XLSX cho danh mục và phiếu nhập vào `backoffice.file-commands.v1`, bằng consumer riêng. Ba topic có thứ tự độc lập. Kiểm tra và khóa ghi nghiệp vụ ở primary vẫn bảo đảm các giao dịch chạm cùng tồn kho phối hợp đúng. API quản trị trả `202`, mã yêu cầu và URL trạng thái. Màn quản trị chờ kết quả của worker trước khi thông báo lưu xong. Gửi lại một yêu cầu chưa có kết quả cần dùng đúng `Idempotency-Key` và nội dung cũ.

Tệp nhập được ghi theo luồng vào volume `backoffice-import-files`. Kafka giữ tham chiếu và metadata thay vì sao chép cả nội dung file vào log. Giới hạn mặc định là 2 MiB và 1.000 dòng mỗi file, tổng vùng file 256 MiB. Worker dọn tệp khi đã commit kết quả cuối cùng, gồm cả lỗi nghiệp vụ. Khi API chưa rõ Kafka đã nhận lệnh hay chưa, tệp được giữ để có thể thử lại. Tệp còn tồn đọng hết hạn sau 7 ngày. Tác vụ định kỳ dọn phần dữ liệu lớn trong kết quả đã lưu sau 7 ngày và giữ thông tin tối thiểu để lần phát lại không thực hiện nghiệp vụ lần nữa. Các giới hạn nghiệp vụ, xem trước và token xác nhận áp dụng như khi chạy đồng bộ.

API trả `413` khi tệp vượt kích thước và `503` khi vùng tệp đầy hoặc không ghi được. Worker trả lỗi riêng khi tham chiếu đã hết hạn hoặc nội dung không khớp. Theo dõi lag của consumer tệp trước khi thay đổi TTL hoặc giới hạn dung lượng.

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

Các job `db-replication-setup`, `kafka-topics`, `backoffice-files-setup`, `cdc-publication`, `cdc-register` phải kết thúc với mã 0. `db-replication-setup` tạo/cập nhật hai role và hai physical slot; replica mới lấy `pg_basebackup -R`. `backoffice-files-setup` cấp quyền trên volume tệp cho tài khoản ứng dụng. Backend chạy Flyway trước khi `cdc-publication` tạo publication. Connector chỉ đăng ký sau khi publication và Connect sẵn sàng. Không xóa volume để nâng cấp; sao lưu trước migration. `dc stop` và `dc start` giữ queue, offset, dữ liệu nghiệp vụ và warehouse.

Tạo dữ liệu kiểm tra qua API/UI nghiệp vụ bình thường; job `seed` hiện chỉ kiểm tra cấu trúc fixture, chưa nạp dữ liệu. Dữ liệu mới sẽ đi qua WAL sang warehouse. Luồng bán bất đồng bộ cần đồng thời có `order-worker` chạy. UI nhận ACK của Kafka thì hoàn tất thao tác nhập đơn ngay; đơn xuất hiện trong danh sách sau khi worker ghi xong. Chấp nhận vào queue chưa đồng nghĩa giao dịch đã ghi xong; luồng POS không poll trạng thái từng yêu cầu.

## Biến môi trường ứng dụng

| Service | Cấu hình |
|---|---|
| API `backend` | `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`, `APP_SALES_QUEUE_PUBLISH_ENABLED=true`, `APP_SALES_QUEUE_CONSUME_ENABLED=false` |
| `order-worker` | Cùng Kafka; publish `false`, consume `true`; datasource ghi luôn là `db` |
| Hàng đợi quản trị | API bật `APP_BACKOFFICE_QUEUE_PUBLISH_ENABLED`; `backoffice-worker` bật `APP_BACKOFFICE_QUEUE_CONSUME_ENABLED` và `APP_BACKOFFICE_QUEUE_FILE_CONSUME_ENABLED`; hai service dùng chung `APP_BACKOFFICE_FILES_DIRECTORY` |
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

Bốn topic dữ liệu giữ tối đa bảy ngày **hoặc** 256 MiB mỗi partition, điều kiện nào đến trước. Tám partition có tổng ngân sách khoảng 2 GiB cộng segment đang mở. Đây là giới hạn dung lượng, không phải cam kết giữ mọi lệnh đã nhận vô thời hạn. Theo dõi lag worker liên tục; cảnh báo khi có lag kéo dài hoặc worker ngừng chạy, xử lý ngay trước khi vượt retention. Trong vận hành, lịch giám sát gọi script sau và phát cảnh báo khi mã thoát khác 0:

```sh
python3 deploy/kafka/check-capacity.py --project banhang-async --env-file deploy/.env --env-file deploy/kafka.local
```

Script cảnh báo từ 80% giới hạn dung lượng từng partition, từ 100 lệnh chờ trên mỗi partition (`--warn-lag`), hoặc khi còn lệnh mà consumer đã dừng. Nó cũng so sánh offset worker đã xác nhận với offset sớm nhất còn trong Kafka để phát hiện khoảng dữ liệu có nguy cơ đã bị xóa. Không tăng retention trước khi kiểm tra dung lượng đĩa. Khôi phục worker rồi đối chiếu đơn và danh sách yêu cầu lỗi trước khi xử lý backlog. API trạng thái có thể trả `PENDING` cho một yêu cầu đã mất khỏi Kafka nếu primary chưa có kết quả. Khi phát hiện khoảng offset đã mất, đối chiếu primary và gửi lại cùng `Idempotency-Key` với nội dung gốc nếu cần.

PostgreSQL đặt `max_slot_wal_keep_size=512MB`, `max_wal_size=256MB` và `wal_keep_size=64MB`. WAL có thể vượt ngưỡng đến checkpoint và các giới hạn WAL không giới hạn kích thước toàn database. Kiểm tra slot thường xuyên:

```sh
dc exec -T db psql -U postgres -d banhang -c "SELECT slot_name, slot_type, active, wal_status, invalidation_reason, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained FROM pg_replication_slots"
```

Cảnh báo khi slot không active kéo dài, `wal_status` sang `unreserved`/`lost`, hoặc WAL giữ lại đạt 400 MiB. Slot hết WAL cần dừng bên tiêu thụ bị ảnh hưởng và nạp lại replica/snapshot có kế hoạch; script không tự xóa dữ liệu hoặc slot. Giữ backup primary và kiểm tra `docker system df`/ổ đĩa định kỳ. Chỉ xóa volume thuộc project thử nghiệm sau khi xác định không chứa dữ liệu cần giữ.

Các lựa chọn được đối chiếu với [PostgreSQL 18 pg_basebackup](https://www.postgresql.org/docs/18/app-pgbasebackup.html), [giới hạn replication slot](https://www.postgresql.org/docs/18/runtime-config-replication.html), [Debezium 3.4 hỗ trợ PostgreSQL 18](https://debezium.io/releases/3.4/), [thông báo snapshot](https://debezium.io/documentation/reference/3.4/configuration/notification.html) và [cấu hình Docker Kafka](https://github.com/apache/kafka/blob/4.1.1/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml).

## Phương án khi tăng kích thước tệp

- Có thể chuyển volume tệp sang kho đối tượng khi cần chạy worker trên nhiều máy. API cấp URL ký theo cửa hàng và mã tải lên, trình duyệt tải trực tiếp và xác minh checksum, kích thước trước khi tạo lệnh xử lý. Dùng multipart của kho đối tượng nếu cần tiếp tục tệp rất lớn sau khi gián đoạn. [URL tải lên có chữ ký](https://docs.aws.amazon.com/AmazonS3/latest/userguide/PresignedUrlUploadObject.html) · [Multipart upload](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
- Bộ đọc hiện dựng danh sách dòng, còn Excel dùng `XSSFWorkbook`. Để tăng đáng kể số dòng, cần đọc CSV theo luồng và XLSX bằng trình đọc sự kiện, nạp lô vào bảng trung gian bằng `COPY`, phân trang kết quả xem trước rồi áp dụng nghiệp vụ theo loại chứng từ. [Trình đọc sự kiện XLSX của Apache POI](https://poi.apache.org/components/spreadsheet/how-to.html#xssf_sax_api) · [PostgreSQL COPY](https://www.postgresql.org/docs/18/sql-copy.html)
