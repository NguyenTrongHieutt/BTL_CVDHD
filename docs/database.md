# Cơ sở dữ liệu (CB-02)

PostgreSQL 18 + pgvector (image `pgvector/pgvector:pg18`), một database `banhang` với hai schema `app` và `ai`. Chủ thiết kế: Hiếu; cả nhóm cùng duyệt.

## Khởi tạo và migration (AD-4)

- `deploy/db/init.sql` chạy **một lần** khi volume `db` còn trống: tạo 4 role, cài extension `vector`, `pg_trgm`, `unaccent`, tạo schema. Mật khẩu đọc từ biến môi trường bằng `\getenv`.
- **Chỉ Flyway trong backend chạy DDL**, bằng role `app_owner`, cho cả schema `ai`. File: `backend/src/main/resources/db/migration/V<yyyyMMddHHmm>__<module>_<mô-tả>.sql`. Không sửa file đã merge; muốn đổi thì thêm file mới.
- Migration chạm bảng của module khác cần chủ module đó duyệt; chạm cột mà Q1–Q9 đọc cần Lân duyệt (CODEOWNERS).

| File | Nội dung |
|---|---|
| `V202611060800__platform_init.sql` | Hàm chung `app.current_store_id()`, `app.f_unaccent()`, `app.enable_tenant_rls()`; bảng `store`, `app_user`, `order_counter`, `audit_log`, `idempotency_record`; hàm `auth_lookup` |
| `V202611060810__catalog_init.sql` | `category`, `product`, `product_variant`, `customer`, `supplier`; view `catalog_product_changes` |
| `V202611060820__inventory_init.sql` | `stock`, `stock_ledger`, `lot`, `purchase_receipt(_line)`, `stocktake(_line)` |
| `V202611060830__sales_init.sql` | `sales_order(_line)`, `payment`, `sales_return(_line)`, `loyalty_account`, `loyalty_ledger` |
| `V202611060840__ai_init.sql` | Schema `ai`: `product_embedding` (vector 384), `ai_quota`, `ai_query_log`, `sync_cursor` |

## Ai được ghi bảng nào (AD-3)

| Module | Người | Bảng |
|---|---|---|
| `platform` | Hiếu | `store`, `app_user`, `audit_log`, `order_counter`, `idempotency_record` |
| `catalog` | Hiếu | `category`, `product`, `product_variant`, `customer`, `supplier` |
| `inventory` | Anh | `stock`, `stock_ledger`, `lot`, `purchase_receipt`, `purchase_receipt_line`, `stocktake`, `stocktake_line` |
| `sales` | Anh | `sales_order`, `sales_order_line`, `payment`, `sales_return`, `sales_return_line`, `loyalty_account`, `loyalty_ledger` |
| `ai-service` | Lân | schema `ai` |
| `reporting` | Lân | không ghi; là nơi duy nhất được đọc thẳng bảng của module khác |

Test `ArchitectureRulesTest.onlyTheOwnerModuleWritesItsTables` chặn lệnh INSERT/UPDATE/DELETE nằm ngoài module chủ.

## Tách dữ liệu giữa Cửa hàng (AD-5)

- Mọi bảng nghiệp vụ có `store_id uuid not null` và bật `ENABLE` + `FORCE ROW LEVEL SECURITY` với policy `store_id = app.current_store_id()`. Bảng `store` lọc theo `id`. `ai.sync_cursor` là bảng toàn cục.
- Backend đặt ngữ cảnh một lần ở đầu mỗi transaction (`TenantTransactionManager`): `set_config('app.store_id', …, true)`. **Truy vấn ngoài transaction thấy 0 dòng.**
- Lớp thứ hai: entity JPA có `@TenantId` (ví dụ `AppUserEntity`).

| Role | Ai dùng | Quyền |
|---|---|---|
| `app_owner` | Flyway; chủ hàm `auth_lookup` | Sở hữu mọi object, `BYPASSRLS` |
| `app_backend` | datasource chính của backend | DML trên `app` (trừ UPDATE/DELETE `audit_log`); không `BYPASSRLS` |
| `sync_reader` | datasource change-feed (GĐ1) | `BYPASSRLS`; chỉ SELECT `product`, `product_variant`, `category`, view `catalog_product_changes` |
| `ai_service` | dịch vụ AI | DML trên `ai`; không có quyền gì trên `app` |

**Lưu ý khi test:** không kết nối bằng `app_owner` hay superuser, vì hai role này bỏ qua RLS. `TestcontainersConfiguration` đã cấu hình đúng.

## Quy ước dữ liệu (AD-8, AD-19)

- Tiền `bigint` (đồng), số lượng `integer`, khóa chính `uuid`, thời gian `timestamptz` UTC; cắt ngày theo `Asia/Ho_Chi_Minh`.
- Tồn kho khóa theo `variant_id`; mọi Sản phẩm có ít nhất một Biến thể (`is_default`). Giá vốn bình quân chỉ ở `stock.avg_unit_cost`.
- Sản phẩm, Biến thể, Khách hàng, Nhà cung cấp chỉ xóa mềm (`deleted_at`). Khóa ngoại giữa hai module đặt `ON DELETE RESTRICT`.
- Mọi index trên bảng nghiệp vụ bắt đầu bằng `store_id`.
