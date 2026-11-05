# Báo cáo giai đoạn 0 (GĐ0): Chuẩn bị chung

Người làm: Hiếu (làm toàn bộ GĐ0 thay cho cả nhóm). Ngày xong: 06/11/2026. Tài liệu căn cứ: Kế hoạch phân công v1.1 mục 3, spine kiến trúc AD-1 … AD-22.

## Kết quả từng việc

| Mã | Việc | Kết quả | Ở đâu |
|---|---|---|---|
| CB-01 | Thống nhất kiến trúc, cài môi trường | Xong. Script kiểm tra môi trường; README ghi phiên bản cần cài | `scripts/check-env.sh`, `README.md` |
| CB-07 | Repo, Docker Compose, README | Xong. 4 container `web`, `backend`, `ai-service`, `db` + job `seed`; nginx; `.env.example`; khôi phục demo; CODEOWNERS; quy ước nhánh | `deploy/`, `CONTRIBUTING.md`, `CODEOWNERS` |
| CB-02 | Thiết kế CSDL, migration đầu tiên | Xong. 5 migration cho toàn bộ bảng của 5 module + schema `ai`; RLS trên mọi bảng nghiệp vụ; 4 role; tồn kho theo Biến thể; bảng lô, điểm tích lũy có sẵn | `deploy/db/init.sql`, `backend/src/main/resources/db/migration/`, `docs/database.md` |
| CB-05 | Khung giao diện chung | Xong. Bố cục, menu theo vai trò, đăng nhập, client API duy nhất, các thành phần dùng chung, trang giữ chỗ cho mọi màn hình; lint chặn import chéo feature | `frontend/` |
| CB-08 | Khung dịch vụ AI, chạy thử torch và Gemini | Xong khung + spike torch. Spike Gemini chờ khóa | `ai-service/`, `ai-service/SPIKES.md` |
| CB-04 | Khung backend | Xong. Modulith 6 module; đăng nhập cookie JWT, `/me`, phân quyền mặc định cấm; hook RLS; Problem Details; `X-Request-Id`; Nhật ký thao tác; mã Đơn hàng; chống tạo trùng; tài khoản thử | `backend/` |
| CB-06 | Dữ liệu mẫu đầu vào, khung chương trình nạp, script tính đáp án | Xong. 2 Cửa hàng mẫu, mỗi cửa hàng 150 Sản phẩm, 50 Khách hàng, 5 Nhà cung cấp, 500 Đơn hàng trong 60 ngày; script đáp án độc lập Q1–Q9 | `deploy/seed/`, `eval/`, `backend/.../devtools/seed/SeedRunner.java` |
| CB-03 | Tài liệu API các điểm phụ thuộc chéo | Xong. 13 điểm phụ thuộc, interface Java ở package gốc mỗi module, danh sách mã lỗi | `docs/api/contracts.md`, `docs/errors.md` |

## Bốn điểm chạy thử (Deferred của spine)

| # | Chạy thử | Kết quả |
|---|---|---|
| 1 | torch 2.15.0 CPU trên Python 3.14 | **Đạt** trên Windows và trong image Linux; không cần lùi về Python 3.13 |
| 2 | Flyway 12.4 trên PostgreSQL 18 | **Đạt** (test Testcontainers với `pgvector/pgvector:pg18` và `init.sql` thật) |
| 3 | Spring Modulith 2.1.2 + springdoc 3.2.0 trên Spring Boot 4.1.2 | **Đạt** (`ApplicationModules.verify()` và `/v3/api-docs`) |
| 4 | Gemini gọi 9 công cụ trên `gemini-3.5-flash-lite` | **Chưa chạy**: cần `GEMINI_API_KEY`. Script sẵn sàng: `uv run python spikes/gemini_tools_spike.py` (25 câu, tiêu chí ≥ 90%, có chế độ `--mode structured`) |

## Test đang có

| Phần | Số test | Nội dung |
|---|---|---|
| backend (`./mvnw verify`) | 21 | Ranh giới module; chỉ module chủ ghi bảng; cấm gọi giờ hệ thống; RLS 2 Cửa hàng; `@TenantId`; phân quyền; đăng nhập, cookie; Problem Details; chống trùng; mã đơn; nhật ký; hợp đồng JWT; OpenAPI |
| ai-service (`uv run pytest`) | 124 | JWT (cả token do Java ký), Problem Details, nhúng (mock), 9 công cụ Q1–Q9, quy đổi khoảng ngày, hạn mức, change-feed |
| frontend (`npm test`) | 67 | Client API, 401, Idempotency-Key, MoneyText, phân trang, route guard, menu theo vai trò, aiSearch (1,5 giây), giỏ hàng |
| eval | — | `py -3.14 eval/answers/compute_answers.py --check` và `--selftest` |

## Quyết định chốt thêm trong GĐ0 (đã ghi vào spine/memlog)

- Một bảng chung `idempotency_record` của platform; mọi module gọi `IdempotencyService.execute` từ controller.
- Giảm giá toàn đơn và phần đổi điểm chia theo tỷ lệ, **làm tròn xuống**, phần dư vào dòng cuối; tiền hoàn cũng làm tròn xuống, lần trả cuối nhận phần dư (AD-8). Code của Anh phải khớp `eval/answers/compute_answers.py`.
- `SEED_ANCHOR_DATE` trống = `2026-12-16` (ngày neo của đáp án). Đặt bằng ngày demo thì chạy lại script đáp án với `--anchor`.
- Lỗi 401 thống nhất là `/errors/common/unauthorized` ở backend và ai-service.
- Tìm kiếm thường cho màn bán hàng là `GET /api/v1/sales/sellable?q=` (Anh); `aiSearch.ts` đã gọi sẵn endpoint này khi AI chậm.

## Việc cần mỗi người làm tiếp (GĐ1)

- **Hiếu:** TK-00 đăng ký Cửa hàng, TK-01 (bỏ đăng nhập tạm `dev.*` khi có seed), TK-03, catalog (SP-01 phát `VariantCreated`, SP-05 `searchForSale`), change-feed `/internal/catalog/product-changes` với datasource `sync_reader`. Điền tên GitHub của Anh, Lân vào `CODEOWNERS`.
- **Anh:** `StockService.apply` (KHO-01) kèm API `/inventory/ledger`, `/sales/orders/quote` (DH-03) với quy tắc làm tròn ở trên, màn bán hàng; nhận sự kiện `VariantCreated`/`VariantDeactivated`.
- **Lân:** AI-01 (nhúng thêm bản bỏ dấu, nạp model khi khởi động vì lần đầu mất khoảng 13 giây), BC-02/04/05 theo định nghĩa trong `eval/README.md`; chốt tham số Q4–Q6 với `ai-service/app/qa/tools.py`; chạy spike Gemini khi có khóa.

## Lưu ý trên máy của Hiếu

Cổng 5432, 8080, 5173 đang bị dự án khác dùng, nên `deploy/.env` trên máy này đặt `DB_HOST_PORT=5433`, `WEB_PORT=8090`; backend chạy local dùng `SERVER_PORT=8081` và Vite dùng `BACKEND_URL=http://localhost:8081`.
