# CLAUDE.md — Hệ thống quản lý bán hàng tích hợp AI (Nhóm 17)

Hướng dẫn cho Claude (và cho người) khi làm việc trong repo này.

## Luật commit và GitHub (bắt buộc)

- **Không dùng tài khoản GitHub của Claude (hay bất kỳ bot nào) để commit hoặc push.** Mọi commit dùng đúng tài khoản Git của thành viên đang làm việc trên máy đó (`git config user.name` / `user.email` của máy, ví dụ `NguyenTrongHieutt <nguyentronghieu2kk5@gmail.com>` trên máy của Hiếu).
- Không thêm dòng `Co-Authored-By: Claude …` hay bất kỳ dòng ghi công AI nào vào commit message hoặc mô tả PR.
- Không đổi `git config` của máy để commit dưới tên người khác.
- Chỉ commit hoặc push khi thành viên yêu cầu.

## Tài liệu gốc

- Kiến trúc (bắt buộc tuân theo): `../_bmad-output/planning-artifacts/architecture/architecture-he-thong-quan-ly-ban-hang-ai-2026-11-05/ARCHITECTURE-SPINE.md` (AD-1 … AD-22). Bản dễ đọc: `../Tai-lieu-kien-truc-He-thong-quan-ly-ban-hang-AI.docx`.
- Yêu cầu: `../_bmad-output/planning-artifacts/prds/prd-he-thong-quan-ly-ban-hang-ai-2026-11-05/prd.md`.
- Phân công và lịch: `../Phan-cong-cong-viec-He-thong-quan-ly-ban-hang-AI.docx`.
- Trong repo: `docs/` (hợp đồng API, mã lỗi, quy ước).

## Ai giữ phần nào

| Người | Vai trò | Backend | Khác |
|---|---|---|---|
| Anh | A – Giao dịch | `inventory`, `sales` | `frontend/src/features/sales`, `features/inventory`, `frontend/src/shared` (trừ `shared/api/aiSearch.ts`) |
| Hiếu | B – Danh mục và Nền tảng | `platform`, `catalog` | `features/platform`, `features/catalog`, `deploy/`, README |
| Lân | C – Phân tích và AI | `reporting`, `devtools.seed` | `ai-service/`, `eval/`, `features/reporting`, `features/assistant`, `shared/api/aiSearch.ts` |

## Quy tắc code quan trọng (tóm tắt spine)

- Mỗi bảng chỉ module chủ được ghi (AD-3). Module khác gọi API công khai ở package gốc của module chủ.
- Mọi truy cập DB nằm trong transaction; ngữ cảnh Cửa hàng đặt bằng `set_config('app.store_id', …, true)` (AD-5).
- Mọi thay đổi Tồn kho đi qua `inventory.StockService.apply` (AD-6). Giá vốn chỉ ở `stock.avg_unit_cost` (AD-15).
- POST tạo chứng từ mang `Idempotency-Key` (AD-7). Tiền là `bigint` đồng, số lượng `integer`, thời gian `timestamptz` UTC, cắt ngày theo `Asia/Ho_Chi_Minh`, lấy giờ qua `Clock` (AD-8).
- Frontend không tự tính tiền; chỉ hiện số từ `POST /api/v1/sales/orders/quote` (AD-8).
- Phân quyền mặc định `denyAll`; DTO cho Nhân viên không có Giá vốn, lợi nhuận (AD-16).
- Migration Flyway: `backend/src/main/resources/db/migration/V<yyyyMMddHHmm>__<module>_<mô-tả>.sql`, không sửa file đã merge (AD-4).
- Mỗi Cửa hàng có một quầy bán (PRD mục 5).

## Lệnh thường dùng

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d db   # chỉ database
cd backend && ./mvnw spring-boot:run                                           # backend :8080
cd backend && ./mvnw verify                                                    # test (cần Docker cho Testcontainers)
cd frontend && npm run dev                                                     # frontend :5173
cd ai-service && uv run uvicorn app.main:app --reload --port 8000              # dịch vụ AI
cd ai-service && uv run pytest
```

Trước khi merge: `./mvnw verify`, `uv run pytest`, `npm run lint && npm test`.
