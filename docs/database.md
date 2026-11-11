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
| `V202611102330__catalog_feed_view_and_index.sql` | Thay view `catalog_product_changes` (`search_text` không còn `{}`, ghép giá trị thuộc tính); index `product_changed_feed_idx (changed_at, id)` |
| `V202611102354__catalog_product_image.sql` | `product_image` (ảnh Sản phẩm, SP-01b) |

## Ai được ghi bảng nào (AD-3)

| Module | Người | Bảng |
|---|---|---|
| `platform` | Hiếu | `store`, `app_user`, `audit_log`, `order_counter`, `idempotency_record` |
| `catalog` | Hiếu | `category`, `product`, `product_variant`, `product_image`, `customer`, `supplier` |
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
| `sync_reader` | datasource thứ hai của backend, chỉ change-feed dùng | `BYPASSRLS`; chỉ SELECT `product`, `product_variant`, `category`, view `catalog_product_changes` |
| `ai_service` | dịch vụ AI | DML trên `ai`; không có quyền gì trên `app` |

**Lưu ý khi test:** không kết nối bằng `app_owner` hay superuser, vì hai role này bỏ qua RLS. `TestcontainersConfiguration` đã cấu hình đúng.

### Change-feed Sản phẩm (AD-12)

`GET /internal/catalog/product-changes` (hợp đồng: [docs/api/contracts.md](api/contracts.md) mục 3.2) là một trong hai ngoại lệ đọc vượt Cửa hàng (AD-5 mục 6):

- **Kết nối riêng** (`catalog.internal.feed.SyncReaderJdbc`): role `sync_reader`, cùng URL với datasource chính, mật khẩu `DB_SYNC_PASSWORD` (thuộc tính `app.catalog.sync-datasource.*`; thiếu mật khẩu thì backend dừng ngay lúc khởi động). Chỉ đọc (`readOnly` của Hikari và `readOnlyMode=always` của PgJDBC), pool tối đa 2 kết nối, chờ kết nối tối đa 5 giây, mỗi câu lệnh tối đa 8 giây. Pool nằm trong bean `SyncReaderJdbc`, **không** là bean `DataSource`: `DataSource`, `JdbcTemplate`, JPA, Flyway, `TenantTransactionManager` vẫn là datasource chính `app_backend` (không `BYPASSRLS`), và health `db` của actuator không kiểm pool này (sai mật khẩu sync không làm `/actuator/health` thành DOWN). Không có transaction manager, mỗi lần đọc là một câu lệnh autocommit; chỉ code change-feed trong `catalog.internal.feed` dùng nó, và chỉ đọc view `catalog_product_changes`.
- **View** `catalog_product_changes` (`security_invoker = true`, nên chạy bằng quyền người đọc: `sync_reader` thấy mọi Cửa hàng, `app_backend` vẫn chịu RLS; định nghĩa hiện tại ở `V202611102330__catalog_feed_view_and_index.sql`): một dòng mỗi Sản phẩm. `search_text` = tên Sản phẩm, tên Danh mục, rồi nhãn và giá trị thuộc tính (`jsonb_each_text`) của các Biến thể chưa xóa mềm (Biến thể mặc định trước, rồi theo ngày tạo; giá trị theo tên khóa), cách nhau một dấu cách, bỏ phần rỗng. `status` là `INACTIVE` khi đã ngừng kinh doanh hoặc xóa mềm.
- **Thứ tự và con trỏ:** sắp theo `(changed_at, product_id)`; trang sau đọc `(changed_at, product_id) > (:t, :id)` với `(:t, :id)` lấy từ con trỏ mờ (base64url của `changed_at` đủ micro giây và `product_id`).
- **Ngoại lệ AD-4 về index:** `product_changed_feed_idx` trên `app.product (changed_at, id)` **không** bắt đầu bằng `store_id`, vì thứ tự đọc của feed đi qua mọi Cửa hàng. Đây là index duy nhất được phép như vậy, đi cùng ngoại lệ đọc vượt Cửa hàng ở trên.
- **`changed_at` đổi khi nào:** đặt bằng giờ lúc ghi (`Clock` của backend, AD-8), nên chỉ hứa là **đổi**, không hứa luôn tăng. Đổi khi thêm Sản phẩm; sửa tên, Danh mục, mô tả hoặc đơn vị (SP-02; chỉ đổi giá, SKU, mã vạch thì không); ngừng kinh doanh (SP-03); đổi tên Danh mục chứa Sản phẩm (SP-04). Sửa Biến thể (nhãn, thuộc tính) chưa đổi `changed_at`: chờ SP-07. Đổi, gỡ ảnh (SP-01b) không đổi `changed_at`: ảnh không thuộc `search_text`.
- **Độ trễ an toàn 5 giây** (`ProductChangeRepository.SAFETY_LAG`): chỉ đọc dòng có `changed_at <=` giờ `Clock` của backend − 5 giây, cùng nguồn giờ với lúc ghi `changed_at`. `changed_at` lấy giờ lúc ghi, trước khi commit; transaction commit muộn mà `changed_at` sớm hơn dòng đã đọc sẽ bị con trỏ bỏ qua nếu không có khoảng trễ này. **Giả định:** mọi bên ghi commit trong vòng 5 giây sau khi lấy giờ. Ghi chậm hơn (hoặc giờ bị lùi) thì thay đổi có thể bị bỏ sót; đường phục hồi là dịch vụ AI xóa con trỏ khi khởi động và đánh chỉ mục lại toàn bộ.

### Ảnh Sản phẩm (SP-01b)

Ảnh lưu ngay trong database, bảng `app.product_image` (demo chạy một máy, `reset-demo` khôi phục cả database; không lưu tệp trên ổ đĩa hay dịch vụ ngoài, không thu nhỏ ảnh ở server). Hợp đồng REST: [docs/api/contracts.md](api/contracts.md) mục 3.2.

- **Một ảnh mỗi Sản phẩm:** khóa chính `product_id` (khóa ngoại tới `product`, `ON DELETE RESTRICT`); `store_id` có RLS như mọi bảng nghiệp vụ, index `product_image_store_idx (store_id, product_id)`. Chỉ `app_backend` có quyền; `sync_reader` không đọc (ảnh không có trong change-feed).
- **Kiểu và cỡ:** `content_type` là `image/jpeg`, `image/png` hoặc `image/webp`, lấy từ byte đầu tệp (magic number) chứ không từ `Content-Type` hay tên tệp của client; `content` tối đa 1 048 576 byte (1 MB, CHECK của cột).
- **Phiên bản:** `version` bắt đầu từ 1, tăng mỗi lần tải ảnh lên. `product.image_url` = `/api/v1/catalog/products/<id>/image?v=<version>`, nên đổi ảnh là đổi URL và trình duyệt không giữ ảnh cũ (`GET` trả `Cache-Control: private, max-age=86400`).
- **Gỡ ảnh không xóa dòng:** đặt `content_type = NULL`, `content` rỗng (CHECK `product_image_removed_ck`: hai điều này luôn đi cùng nhau), giữ `version`; `product.image_url` về `NULL`. Lần tải sau ra phiên bản mới; xóa dòng thì sẽ quay về `?v=1`, trùng URL của ảnh cũ mà trình duyệt còn giữ trong cache tới 1 ngày.
- **Ghi:** khóa dòng `product` (`FOR UPDATE`) trước, cùng thứ tự với sửa và ngừng kinh doanh, nên hai lần tải cùng lúc chạy lần lượt. Đổi, gỡ ảnh lấy giờ mới cho `product.updated_at`, không đổi `changed_at`.

## Quy ước dữ liệu (AD-8, AD-19)

- Tiền `bigint` (đồng), số lượng `integer`, khóa chính `uuid`, thời gian `timestamptz` UTC; cắt ngày theo `Asia/Ho_Chi_Minh`.
- Tồn kho khóa theo `variant_id`; mọi Sản phẩm có ít nhất một Biến thể (`is_default`). Giá vốn bình quân chỉ ở `stock.avg_unit_cost`.
- Sản phẩm, Biến thể, Khách hàng, Nhà cung cấp chỉ xóa mềm (`deleted_at`). Khóa ngoại giữa hai module đặt `ON DELETE RESTRICT`.
- Mọi index trên bảng nghiệp vụ bắt đầu bằng `store_id`. Ngoại lệ duy nhất: `product_changed_feed_idx` của change-feed (xem mục Change-feed ở trên).
