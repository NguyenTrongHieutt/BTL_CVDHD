# Theo dõi hệ thống trên Docker Compose

Dùng `compose.monitoring.yml` cùng các file Compose đang chạy. Thêm overlay sau các file cấu hình cơ sở và dùng lại đúng tên project, tệp môi trường và volume của lần triển khai hiện tại. Trước khi theo dõi lần đầu, cần bản build backend và AI có mã xuất metrics.

Ví dụ cho bộ Docker Compose chạy Kafka. Với demo HTTPS dùng thêm các overlay cho kho tệp và proxy đã cấu hình của demo.

```powershell
$files = @('-f', 'deploy/docker-compose.yml', '-f', 'deploy/compose.kafka.yml', '-f', 'deploy/compose.monitoring.yml')
$environment = @('--env-file', 'deploy/.env', '--env-file', 'deploy/kafka.local')
$project = 'banhang' # Thay bằng tên project của lần triển khai hiện tại.
docker compose -p $project @environment @files config --quiet
docker compose -p $project @environment @files up -d --build --wait
```

Mở [Prometheus](http://127.0.0.1:19090/) hoặc [trang Targets](http://127.0.0.1:19090/targets) trên máy chủ. Từng bản API và từng worker có target riêng. Dùng `up` để kiểm tra Prometheus có đọc được target hay không và `probe_success` để xem kết quả kiểm tra HTTP/TCP. Mạng công khai của web chỉ nhận yêu cầu ứng dụng. Cổng metrics và màn hình Prometheus dùng cho vận hành nội bộ.

## Xem log

```powershell
docker compose -p $project @environment @files logs --since 30m --tail 300 --timestamps backend order-worker backoffice-worker ai-service cdc-worker web
docker compose -p $project @environment @files logs --follow --tail 100 backend web
docker compose -p $project @environment @files logs --since 1h --tail 500 --timestamps --no-color > server-last-hour.log
```

Tra `X-Request-Id` trả về khi cần đối chiếu log web và backend. Log web ghi thời gian, mã HTTP, địa chỉ upstream và thời gian upstream. Log Docker lưu theo bộ đệm có xoay vòng trên mỗi container. Xuất file trước khi thay container nếu cần giữ lịch sử sự cố và lưu file trong thư mục vận hành.

## Metrics thường dùng

```promql
up
probe_success
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))
histogram_quantile(0.95, sum by (le, route) (rate(banhang_ai_request_duration_seconds_bucket[5m])))
hikaricp_connections_active
nginx_connections_active
```

Metrics HTTP, JVM và Hikari từ backend và worker, thời gian các API AI và kết nối Nginx có chuỗi số liệu để xem theo thời gian. PostgreSQL, Kafka và các dịch vụ hạ tầng có probe riêng cho trạng thái kết nối. Probe TCP chỉ xác nhận cổng còn trả lời. Chỉ số WAL, độ trễ consumer group và độ trễ truy vấn cơ sở dữ liệu cần bộ thu chuyên dụng nếu dùng để giám sát sâu. Mở tab **Alerts** để xem quy tắc và trạng thái cảnh báo cục bộ.

Prometheus giữ các khối số liệu tối đa 7 ngày hoặc đến giới hạn 512 MB. Dung lượng sử dụng còn gồm WAL của Prometheus. Mỗi container theo dõi giữ tối đa hai phần log cỡ 5 MB.

## Dashboard Grafana

Thêm overlay `deploy/compose.grafana.yml` sau overlay Prometheus. Grafana lưu tài khoản và cấu hình trong volume `grafana-data`. Nguồn Prometheus và dashboard **Tổng quan vận hành** được nạp từ các tệp trong `deploy/monitoring/grafana/`.

Tạo tệp mật khẩu cho lần khởi tạo và chỉ định đường dẫn tuyệt đối. Giữ tệp này trong thư mục cá nhân hoặc trong thư mục bí mật của máy chủ.

```powershell
$credentialDir = Join-Path $env:LOCALAPPDATA 'BanhangMonitoring'
New-Item -ItemType Directory -Path $credentialDir -Force | Out-Null
$env:GRAFANA_ADMIN_PASSWORD_FILE = Join-Path $credentialDir 'grafana-admin-password.txt'
if (!(Test-Path -LiteralPath $env:GRAFANA_ADMIN_PASSWORD_FILE)) {
  [IO.File]::WriteAllText($env:GRAFANA_ADMIN_PASSWORD_FILE,
    [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)))
}
$files += @('-f', 'deploy/compose.grafana.yml')
docker compose -p $project @environment @files config --quiet
docker compose -p $project @environment @files up -d --no-deps --wait grafana
```

Đăng nhập tài khoản `admin` bằng mật khẩu trong tệp và mở [Tổng quan vận hành](http://127.0.0.1:13000/d/banhang-overview/). Cổng `13000` chỉ lắng nghe trên máy chủ. Chuỗi độ trễ AI sẽ xuất hiện sau khi có yêu cầu tìm theo mô tả, gợi ý hàng hoặc hỏi đáp. Đổi khoảng thời gian trên dashboard để xem số liệu đã có trong Prometheus.

## Metric hạ tầng và truy vết

Thêm các overlay dưới đây sau khi [Grafana](#dashboard-grafana) đã được cấu hình. Dùng lại project và volume đang chạy. Lần bật đầu tiên khởi động lại primary để nạp `pg_stat_statements`. Hai replica tiếp tục dùng các volume riêng hiện có.

Đặt `DB_MONITOR_PASSWORD` trong tệp `deploy/telemetry.local` và cấp riêng mật khẩu dài cho tài khoản chỉ đọc của bộ thu. Tải [OpenTelemetry Java agent 2.34.0](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/tag/v2.34.0), kiểm tra SHA-256 rồi đặt `OTEL_JAVA_AGENT_FILE` thành đường dẫn tuyệt đối đến JAR. Hàm băm mong đợi là `da44154cea128257779c8a782b9651fd7c0c6aa177cd3810f9a867ac8509555d`.

```powershell
$environment += @('--env-file', 'deploy/telemetry.local')
$files += @('-f', 'deploy/compose.tracing.yml', '-f', 'deploy/compose.otel-java.yml',
  '-f', 'deploy/compose.infra-metrics.yml', '-f', 'deploy/compose.container-metrics.yml',
  '-f', 'deploy/compose.telemetry-scrapes.yml')
docker compose -p $project @environment @files config --quiet
docker compose -p $project @environment @files up -d --wait --scale backend=2
powershell.exe -NoProfile -File deploy/monitoring/containers/collect.ps1 -Project $project -Watch
```

Mở dashboard **Hạ tầng và hàng đợi** trong Grafana. PostgreSQL có khóa, hoạt động truy vấn, WAL và độ trễ replica. Kafka có trạng thái broker, partition và độ trễ tính từ offset đã commit của consumer group. CDC đo khoảng cách từ checkpoint đã commit trong kho báo cáo tới cuối log Kafka và thời gian kể từ heartbeat đã áp dụng. CPU, RAM, mạng, I/O và số lần restart của container lấy từ Docker CLI trên máy chủ. Bộ xuất textfile chỉ đọc tệp số liệu qua mạng Compose.

Để xem một giao dịch, vào **Explore → Tempo** và nhập trace ID trong ô TraceQL. Có thể tìm đơn bán bằng `{ resource.service.name = "banhang-backend" && span.http.route = "/api/v1/sales/orders" }`. Java agent truyền W3C Trace Context khi gửi lệnh Kafka. Worker tiếp tục cùng trace khi ghi PostgreSQL. Collector lọc URL có tham số, nội dung SQL và dữ liệu nhạy cảm trước khi lưu. Mặc định lấy mẫu 10% trace mới và lưu 24 giờ. Đặt `OTEL_TRACES_SAMPLER_ARG=1.0` trong phiên demo giới hạn nếu cần thu mọi yêu cầu mới.

## Cân bằng tải API

Nginx lấy danh sách IP của dịch vụ `backend` qua DNS nội bộ Docker và phân phối yêu cầu `/api/` theo vòng. Tăng chỉ API lên hai bản khi máy chủ còn RAM và PostgreSQL còn đủ kết nối. Giữ một bản mỗi worker theo cấu hình demo.

```powershell
docker compose -p $project @environment @files up -d --no-build --scale backend=2 --wait
docker compose -p $project @environment @files ps backend
```

Có thể kiểm tra phân phối bằng trường `upstream` trong log `web` và mục Targets trong Prometheus. Hai container cùng một máy giúp san tải và xử lý một bản API bị dừng. Máy chủ, Kafka và primary vẫn là các điểm phụ thuộc chung.

## Đưa lên Minikube

Trong môi trường thử nghiệm Kubernetes, chạy API bằng `Deployment` có hai Pod và `Service` loại `ClusterIP` phía sau Ingress. Kubernetes dùng probe để loại Pod chưa sẵn sàng khỏi Service. Giữ worker theo từng vai trò và cấp `PersistentVolumeClaim` cho PostgreSQL, Kafka, kho đối tượng và Prometheus. Prometheus cần khám phá từng Pod hoặc endpoint để lấy chỉ số của cả hai API. Minikube phù hợp để thử mô hình này trên máy phát triển.
