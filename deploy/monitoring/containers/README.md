# Metrics tài nguyên container trên Docker Desktop Windows

Collector chạy trên máy Windows bằng Docker CLI. Exporter BusyBox trong Compose chỉ đọc tệp kết quả và phục vụ cho Prometheus trong mạng nội bộ. Không chuyển Docker socket vào container và không mở cổng exporter ra máy chủ.

## Chạy

Thêm `deploy/compose.container-metrics.yml` sau các overlay Compose đang dùng, với cùng tên project. Thu mẫu đầu tiên trước khi khởi động exporter:

```powershell
$project = 'nhom17-cloudflare-demo' # Tên project thực tế của lần triển khai.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File deploy/monitoring/containers/collect.ps1 -Project $project
```

Sau đó duy trì collector trong một tiến trình giám sát hoặc phiên PowerShell:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File deploy/monitoring/containers/collect.ps1 -Project $project -Watch -IntervalSeconds 30
```

Có thể đặt `CONTAINER_METRICS_PROJECT` thay cho `-Project`. `CONTAINER_METRICS_SERVICES` là danh sách dịch vụ phân tách bằng dấu phẩy, hoặc dùng `-Services web,backend,ai-service`. Mặc định collector chỉ chọn các dịch vụ lâu dài của demo, bỏ các job khởi tạo và container chạy một lần. Cần chạy một collector cho mỗi tệp đầu ra; `-OutputPath` cho phép đổi vị trí khi thử nghiệm.

Prometheus cần thêm job vào cấu hình scrape của cùng stack:

```yaml
  - job_name: container-metrics
    metrics_path: /metrics.txt
    fallback_scrape_protocol: PrometheusText0.0.4
    static_configs:
      - targets: [container-metrics-exporter:9101]
```

Tệp được ghi vào `deploy/monitoring/containers/data/metrics.txt` bằng thao tác thay thế nguyên tử. Thư mục `data` được mount read-only vào exporter. Collector giữ mẫu cũ khi lệnh Docker thất bại; dùng `time() - docker_container_collector_last_success_timestamp_seconds` để nhận ra lần thu đã quá hạn.

## Số liệu

Mỗi container chỉ có ba nhãn `project`, `service`, `replica` lấy từ Compose; trạng thái có thêm nhãn `state`. Không xuất tên container, ID, đường dẫn, token hoặc biến môi trường làm nhãn.

| Metric | Ý nghĩa |
| --- | --- |
| `docker_container_cpu_percent` | CPU do `docker stats` báo; 100% tương đương một lõi. |
| `docker_container_cpu_limit_cores` | Giới hạn CPU cấu hình; 0 nghĩa là không giới hạn. |
| `docker_container_memory_usage_bytes` | RAM đang dùng theo `docker stats`. |
| `docker_container_memory_limit_bytes` | Giới hạn RAM cấu hình; 0 nghĩa là không giới hạn. |
| `docker_container_network_receive_bytes_total`, `docker_container_network_transmit_bytes_total` | Byte mạng nhận/gửi. |
| `docker_container_block_read_bytes_total`, `docker_container_block_write_bytes_total` | Byte đọc/ghi thiết bị khối. |
| `docker_container_restarts_total` | Số lần khởi động lại của instance hiện tại. |
| `docker_container_state` | 1 cho trạng thái hiện tại, 0 cho trạng thái khác. |
| `docker_container_collector_containers`, `docker_container_collector_last_success_timestamp_seconds` | Số container được chọn và thời điểm thu thành công. |

`docker stats` làm tròn số byte khi hiển thị, nên các chỉ số byte chỉ chính xác đến độ phân giải của Docker CLI. Counter và số lần restart có thể về 0 khi container được thay bằng instance mới; nhãn `service` và `replica` tiếp tục dùng lại.
