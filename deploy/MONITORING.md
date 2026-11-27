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

## Phạm vi đo và tracing

Prometheus hiện thu thời gian phản hồi, lỗi và tải HTTP của API, tài nguyên JVM, kết nối trong pool Hikari, metrics Kafka client ở worker, thời gian các tác vụ AI và trạng thái Nginx. Probe của PostgreSQL, Kafka broker, Kafka Connect và kho tệp xác nhận khả năng kết nối hoặc endpoint sức khỏe. Theo dõi sâu còn cần metrics query/lock/WAL của PostgreSQL, tình trạng broker và lag toàn consumer group, độ trễ của CDC, dung lượng kho tệp và tài nguyên của từng container. Có thể xem lag các worker bằng `deploy/kafka/check-capacity.py`.

Hiện log truyền `X-Request-Id` giữa các lời gọi HTTP. Hệ thống chưa thu span hoặc truyền ngữ cảnh tracing qua Kafka. Để xem từng chặng của một giao dịch trong Grafana, cần instrument backend, AI và worker bằng OpenTelemetry, truyền `traceparent` qua HTTP và Kafka, rồi gửi span qua OTLP tới kho trace như Tempo.

## Cân bằng tải API

Nginx lấy danh sách IP của dịch vụ `backend` qua DNS nội bộ Docker và phân phối yêu cầu `/api/` theo vòng. Tăng chỉ API lên hai bản khi máy chủ còn RAM và PostgreSQL còn đủ kết nối. Giữ một bản mỗi worker theo cấu hình demo.

```powershell
docker compose -p $project @environment @files up -d --no-build --scale backend=2 --wait
docker compose -p $project @environment @files ps backend
```

Có thể kiểm tra phân phối bằng trường `upstream` trong log `web` và mục Targets trong Prometheus. Hai container cùng một máy giúp san tải và xử lý một bản API bị dừng. Máy chủ, Kafka và primary vẫn là các điểm phụ thuộc chung.

## Đưa lên Minikube

Trong môi trường thử nghiệm Kubernetes, chạy API bằng `Deployment` có hai Pod và `Service` loại `ClusterIP` phía sau Ingress. Kubernetes dùng probe để loại Pod chưa sẵn sàng khỏi Service. Giữ worker theo từng vai trò và cấp `PersistentVolumeClaim` cho PostgreSQL, Kafka, kho đối tượng và Prometheus. Prometheus cần khám phá từng Pod hoặc endpoint để lấy chỉ số của cả hai API. Minikube phù hợp để thử mô hình này trên máy phát triển.
