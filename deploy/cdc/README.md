# Kho báo cáo Q1/Q3

Kho PostgreSQL riêng lưu bản sao bốn bảng bán hàng, chỉ gồm các cột Q1/Q3 sử dụng. API truy vấn bằng `warehouse_reader`, chỉ có quyền SELECT và policy RLS theo cửa hàng. Worker dùng `warehouse_writer`; thông tin đăng nhập nguồn chỉ cấp cho Debezium.

Debezium pgoutput đọc WAL trực tiếp, không có bảng outbox. Connector gom bản ghi của bốn bảng, metadata `BEGIN`/`END`, thông báo snapshot và heartbeat vào `banhang.reporting`, **đúng một partition**, `cleanup.policy=delete`. Không đổi số partition hoặc bật compaction: thứ tự trong stream là hợp đồng an toàn của worker. Các bảng nguồn không được TRUNCATE; thao tác này cần dựng lại snapshot.

Worker giữ trọn một giao dịch nguồn trong giao dịch kho. Số bản ghi và `transaction.total_order` phải khớp metadata END; sau đó các thay đổi và `warehouse.cdc_state.next_offset` được commit cùng lúc. Offset Kafka được commit sau. Khi khởi động lại, checkpoint trong kho quyết định vị trí đọc. Một advisory lock chặn hai worker đồng thời. Version theo khóa chính và LSN giữ lại cả bản ghi đã xóa, nên phát lại giao dịch cũ không ghi đè bản mới hoặc khôi phục dữ liệu đã xóa.

Mã giao dịch PostgreSQL của Debezium có dạng `XID:LSN`, trong đó LSN thay đổi theo từng thao tác, kể cả BEGIN và END. Worker đối chiếu phần XID ổn định trong cùng giao dịch; thứ tự dòng và tổng số dòng vẫn được kiểm tra đầy đủ. LSN đầy đủ của từng dòng dùng riêng cho chống phát lại.

Các dòng snapshot có version ban đầu bằng 0 để giao dịch đã ghi WAL trước mốc snapshot nhưng commit sau mốc đó vẫn được áp dụng. Thông báo STARTED đặt kho chưa sẵn sàng và xóa mirror cũ; COMPLETED mới cho phép chuyển sang đọc kho. Marker cũng hoạt động khi nguồn hoàn toàn trống.

API bật qua `APP_REPORTING_WAREHOUSE_ENABLED=true`, URL JDBC, `APP_REPORTING_WAREHOUSE_USERNAME=warehouse_reader` và `APP_REPORTING_WAREHOUSE_PASSWORD`. `APP_REPORTING_WAREHOUSE_MAX_AGE` mặc định `30s`. Q1/Q3 dùng kho khi snapshot hoàn tất, consumer đã đến high watermark Kafka và heartbeat/poll còn mới; các trường hợp còn lại đọc cơ sở dữ liệu nghiệp vụ. Q3 từ kho không dùng cache cục bộ. Q4 và dashboard giữ luồng đọc hiện có.

Chủ cửa hàng xem `/api/v1/reporting/warehouse/status` để kiểm tra checkpoint, heartbeat và trạng thái bắt kịp. `heartbeatAgeMillis` là tuổi heartbeat, không phải phép đo khoảng cách WAL với primary. CDC nhất quán sau một độ trễ; không đảm bảo báo cáo đọc ngay giao dịch vừa bán. Nếu connector không phát heartbeat khi nguồn nhàn rỗi, API chuyển về luồng nghiệp vụ sau thời hạn cấu hình.

Worker nhận các biến libpq `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, cùng `KAFKA_BOOTSTRAP_SERVERS` và `CDC_TOPIC` (mặc định `banhang.reporting`). `python /app/worker.py --healthcheck` kiểm tra lần poll Kafka thành công trong 45 giây và trạng thái không lỗi; quá trình snapshot chưa hoàn tất vẫn có thể healthy.

Nếu checkpoint thấp hơn retention đầu topic hoặc topic bị tạo lại, worker dừng và API đọc nghiệp vụ. Phải dừng worker/connector, giữ dữ liệu nghiệp vụ, rồi dựng lại đồng bộ **kho, topic CDC và trạng thái offset/slot của connector** để chạy initial snapshot mới. Không đặt offset thủ công để bỏ qua khoảng trống, không xóa topic lệnh POS hoặc volume nghiệp vụ để sửa kho.

Kiểm thử worker dùng một PostgreSQL dùng riêng, được phép xóa schema `app`/`warehouse`:

```powershell
$env:CDC_TEST_DSN = 'postgresql://postgres:<test-password>@127.0.0.1:<test-port>/<test-database>'
python -m unittest discover -s deploy/cdc -p test_worker.py -v
```

`ReportingWarehouseIntegrationTest` chạy PostgreSQL thật và kiểm tra truy vấn Q1/Q3, ngày Việt Nam, giá vốn chụp lúc bán, trả hàng, hủy đơn, RLS, chỉ đọc và các điều kiện fallback. Không chạy bộ kiểm thử worker trên kho demo hoặc cơ sở dữ liệu nghiệp vụ.
