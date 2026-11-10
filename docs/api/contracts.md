# Hợp đồng API giữa các thành viên (CB-03)

Tài liệu này chốt mọi chỗ một người cần chức năng của người khác (mục 6 Kế hoạch phân công). Bên dùng code theo đúng hợp đồng này, dùng **API giả** khi bên cung cấp chưa xong. Đổi hợp đồng: mở PR sửa file này, cần bên cung cấp và bên dùng cùng duyệt.

Hợp đồng chạy thật là **OpenAPI do springdoc sinh** (`/v3/api-docs`, giao diện `/swagger-ui.html`) cho REST, và **interface Java ở package gốc mỗi module** cho lời gọi trong backend. File này mô tả trước khi code có, để không ai phải chờ.

## 1. Quy ước chung (AD-19)

| Mục | Quy ước |
|---|---|
| Đường dẫn | `/api/v1/<module>/…` (backend), `/ai/…` (dịch vụ AI), `/internal/…` (chỉ dịch vụ AI gọi backend, nginx không mở ra ngoài) |
| JSON | `camelCase`; tiền là số nguyên đồng (`long`); số lượng là `int`; thời điểm là ISO-8601 UTC (`2026-11-06T02:15:00Z`); ngày là `yyyy-MM-dd` theo lịch Việt Nam |
| Phân trang | Request `page` (từ 0), `size` (mặc định 20, tối đa 100), `sort=<field>,asc\|desc`. Phản hồi `{items, total, page, size}` |
| Khoảng ngày | `fromDate`, `toDate`, **gồm cả hai đầu**, tối đa 366 ngày |
| Lỗi | RFC 9457 Problem Details: `type`, `title`, `status`, `detail`, `instance`, `requestId`, `errors[]` (lỗi nhập liệu). Danh sách `type`: [docs/errors.md](../errors.md) |
| Đăng nhập | Cookie `access_token` (HttpOnly, SameSite=Strict). Dịch vụ AI gọi `reporting` bằng `Authorization: Bearer <token của người dùng>` |
| Tạo chứng từ | Header `Idempotency-Key: <uuid>` (AD-7). Lần đầu `201`; gửi lại cùng nội dung `200` + `Idempotent-Replay: true`; cùng khóa khác nội dung `409` |
| Truy vết | Header `X-Request-Id` đi qua mọi khối |

## 2. Bảng điểm phụ thuộc chéo

| # | Bên cung cấp | API | Bên dùng | Loại |
|---|---|---|---|---|
| 1 | Hiếu – platform | Đăng nhập, `/me`, `CurrentContext`, `TenantScope` (mục 3.1) | Anh, Lân | Mềm (đã có từ GĐ0) |
| 2 | Hiếu – catalog | `VariantLookup.getForSale`, `searchForSale` (3.2) | Anh (sales) | Mềm |
| 3 | Hiếu – platform | `StoreSettings` (3.1) | Anh (VietQR, hóa đơn) | Mềm |
| 4 | Hiếu – catalog | `CustomerLookup`, REST Khách hàng (3.2) | Anh (DH-06, KH-04) | Mềm |
| 5 | Hiếu – catalog | `SupplierLookup` (3.2) | Anh (KHO-03) | Mềm |
| 6 | Hiếu – catalog | Sự kiện `VariantCreated`, `VariantDeactivated` (3.2) | Anh (inventory) | Mềm |
| 7 | Hiếu – catalog | Change-feed `/internal/catalog/product-changes` (3.2) | Lân (đồng bộ chỉ mục) | Mềm |
| 8 | Anh – inventory | `GET /api/v1/inventory/ledger` (3.3) | Hiếu (màn KHO-07) | **Cứng** |
| 9 | Anh – sales | `GET /api/v1/sales/orders?customerId=`, `…/purchase-summary` (3.4) | Hiếu (màn KH-03) | **Cứng** |
| 10 | Anh – inventory, sales | `StockQuery`, `/inventory/stock`, `/inventory/low-stock`, `/sales/sellable` (3.3, 3.4) | Lân (BC-01, AI-02), frontend | Mềm |
| 11 | Lân – ai-service | `GET /ai/search` + `shared/api/aiSearch.ts` (3.6) | Anh (TH-01) | **Cứng** |
| 12 | Lân – reporting | `GET /api/v1/reporting/q/{Qn}` (3.5) | Lân (ai-service), frontend báo cáo | Mềm |
| 13 | Hiếu, Anh | API ghi của platform, catalog, inventory, sales | Lân (SD-01 nạp dữ liệu mẫu) | **Cứng** |

## 3. Chi tiết

### 3.1 platform (Hiếu) — GĐ0 (đăng nhập, `/me`, API Java); GĐ1 (đăng ký Cửa hàng)

| API | Mô tả |
|---|---|
| `POST /api/v1/platform/auth/login` | Body `{loginKey, password}` → `200 Me` + cookie. `loginKey` được cắt khoảng trắng, đổi chữ thường, và `+84…` đổi thành `0…` (cùng quy tắc với đăng ký). Sai thông tin hoặc tài khoản bị khóa: `401 /errors/platform/invalid-credentials` (một thông báo chung) |
| `POST /api/v1/platform/auth/logout` | Xóa cookie, `204` |
| `GET /api/v1/platform/me` | `Me = {userId, storeId, storeName, role: "OWNER"\|"STAFF", displayName}` |
| `POST /api/v1/platform/stores` | Đăng ký Cửa hàng (FR-43, GĐ1), công khai. Body `{storeName, loginKey, password, displayName}` + header `Idempotency-Key` (UUID). Tạo Cửa hàng cùng tài khoản Chủ cửa hàng → `201 Me` + cookie như đăng nhập. Gửi lại cùng khóa, cùng nội dung, đúng mật khẩu: `200 Me` + `Idempotent-Replay: true` + cookie. Lỗi: trùng `loginKey` (kể cả khác hoa thường, `+84`/`0`): `409 /errors/platform/login-taken`; cùng khóa nhưng khác nội dung hoặc sai mật khẩu: `409 /errors/common/idempotency-key-reuse`; phát lại cho tài khoản đã bị khóa: `401 /errors/platform/invalid-credentials`; thiếu khóa hoặc khóa không phải UUID hợp lệ: `422 /errors/common/idempotency-key-missing`; dữ liệu sai: `400 /errors/common/validation` với `errors[]` theo trường. Quy tắc trường: `storeName` 1–200 ký tự, `displayName` 1–100 ký tự (không được chỉ gồm khoảng trắng); `loginKey` ≤ 254 ký tự, là số điện thoại (`0` hoặc `+84`, rồi 9 chữ số; lưu dạng `0…`) hoặc email ASCII, lưu chữ thường; `password` 8–72 ký tự và ≤ 72 byte UTF-8 |
| Java `CurrentContext` | `storeId()`, `userId()`, `role()`, `isOwner()` |
| Java `TenantScope.runAs(storeId, …)` | Chỉ cho seed, test, đăng ký Cửa hàng |
| Java `StoreSettings.current()` | Tên, địa chỉ, điện thoại, chân hóa đơn, `bankBin`, `bankAccountNo`, `bankAccountName`, tỷ lệ tích điểm, số ngày cảnh báo hết hạn |
| Java `AuditLog.record(action, entityId, before, after)` | Gọi trong transaction của thao tác; `action` lấy từ enum `AuditAction` |
| Java `OrderCodes.nextOrderCode()` | `DH-xxxxxx`, trong transaction của Đơn hàng |
| Java `IdempotencyService.execute(...)` | Gọi từ controller, ngoài transaction (xem Javadoc) |
| Java `PageResponse<T>`, `ProblemException`, `TimeZones` | Dùng chung |

### 3.2 catalog (Hiếu) — GĐ1

| API | Mô tả |
|---|---|
| Java `VariantLookup.getForSale(ids)` | `List<SellableVariant{variantId, productId, productName, variantName, sku, barcode, unit, salePrice}>`, chỉ Biến thể còn kinh doanh |
| Java `VariantLookup.searchForSale(q, categoryId, minPrice, maxPrice, page, size)` | Tìm kiếm thường FR-5 (không dấu qua `app.f_unaccent`, theo tên, SKU, mã vạch) |
| Java `CustomerLookup.findById`, `findByPhone` | `CustomerSummary{customerId, name, phone}` |
| Java `SupplierLookup.findById` | `SupplierSummary{supplierId, code, name}` |
| Sự kiện `VariantCreated(storeId, variantId, openingQuantity, openingUnitCost)` | Phát trong transaction tạo Biến thể; `inventory` nghe `BEFORE_COMMIT` |
| Sự kiện `VariantDeactivated(storeId, variantId)` | Phát cho từng Biến thể khi ngừng kinh doanh |
| `GET /api/v1/catalog/products?q=&categoryId=&status=&page=&size=` | Màn quản lý Sản phẩm, cả hai vai trò. `PageResponse<Product>`, mới nhất trước. Bản hiện tại (SP-01) chỉ nhận `page`, `size` và **bỏ qua** `q`, `categoryId`, `status`; SP-05 thêm các tham số này |
| `GET /api/v1/catalog/products/{id}` | Cả hai vai trò. `Product = {id, name, categoryId, categoryName, unit, description, hasVariants, status, createdAt, updatedAt, variants: [{id, sku, barcode, name, salePrice, isDefault, status}]}`, Biến thể mặc định đứng đầu. Không có Giá vốn (AD-15, AD-16). Id không có hoặc của Cửa hàng khác: `404 /errors/common/not-found` |
| `POST /api/v1/catalog/products` | Chủ cửa hàng (FR-1, SP-01). Header `Idempotency-Key` (UUID, loại chứng từ `CATALOG_PRODUCT`, vì có thể kèm Tồn kho đầu kỳ `OPENING`). Body `{name, categoryId?, unit?, description?, salePrice, sku?, barcode?, openingQuantity?, openingUnitCost?}` → `201 Product` (một Biến thể mặc định, header `Location`) và phát `VariantCreated(storeId, variantId, openingQuantity, openingUnitCost)`; gửi lại cùng khóa, cùng nội dung: `200` + `Idempotent-Replay: true`, không phát sự kiện lần hai. **Chuỗi:** bỏ ký tự định dạng vô hình (mô tả giữ U+200C, U+200D của emoji ghép), khoảng trắng Unicode (U+00A0, U+3000…) thành dấu cách thường, chuẩn NFC, cắt khoảng trắng hai đầu; độ dài đếm theo code point sau khi chuẩn hóa (chữ gõ dạng tổ hợp NFD, emoji đều là một ký tự). Từ chối ký tự điều khiển, surrogate lẻ, và ở trường một dòng cả U+2028, U+2029; chỉ `description` được xuống dòng (`\n`) và tab. **Trường:** `name` bắt buộc, ≤ 200; `categoryId` phải là Danh mục của Cửa hàng (báo cùng lượt với lỗi trường khác); `unit` ≤ 30, trống thì "cái"; `description` ≤ 2000; `sku` ≤ 50, trống thì sinh `SP-` + 8 ký tự đầu (viết hoa) của id Biến thể, sinh lại nếu đã có; `barcode` ≤ 50, chỉ chữ cái không dấu và chữ số. `sku`, `barcode` lưu **chữ HOA**, nên khác hoa thường vẫn là trùng; duy nhất trong một Cửa hàng (Biến thể chưa xóa mềm), Cửa hàng khác dùng lại được. **Số:** số nguyên JSON, `1500.0` được nhận, `1.5` bị từ chối. `salePrice` bắt buộc, `openingUnitCost` mặc định 0: tiền 0 … 9 007 199 254 740 991 (`Number.MAX_SAFE_INTEGER`); `openingQuantity` 0 … 2 147 483 647, mặc định 0; `openingUnitCost` phải > 0 khi `openingQuantity > 0`, và `openingQuantity × openingUnitCost` ≤ 9 007 199 254 740 991. `openingUnitCost` không lưu ở catalog, không có trong phản hồi. **Lỗi:** dữ liệu sai `400 /errors/common/validation` với `errors[]` theo trường (mọi trường trong một lần); trùng mã vạch `409 /errors/catalog/barcode-taken`; trùng SKU `409 /errors/catalog/sku-taken`; thiếu khóa hoặc khóa không phải UUID `422 /errors/common/idempotency-key-missing` (báo sau lỗi dữ liệu); cùng khóa khác nội dung `409 /errors/common/idempotency-key-reuse` |
| `GET /api/v1/catalog/categories?page=&size=` | Danh mục (FR-4), cả hai vai trò (ô lọc theo Danh mục). `PageResponse<{id, name, productCount}>`, sắp theo tên không dấu, không phân biệt hoa thường. `productCount`: mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh |
| `POST /api/v1/catalog/categories` | Chủ cửa hàng. Body `{name}` (cắt khoảng trắng, bắt buộc, ≤ 100 ký tự) → `201 {id, name, productCount: 0}`. Không có `Idempotency-Key`. Trùng tên trong Cửa hàng (không phân biệt hoa thường): `409 /errors/catalog/category-name-taken`; tên sai: `400 /errors/common/validation` trên trường `name` |
| `PUT /api/v1/catalog/categories/{id}` | Chủ cửa hàng. Đổi tên, body `{name}` → `200 {id, name, productCount}`; cập nhật `product.changed_at` của các Sản phẩm thuộc Danh mục (AD-12). Trùng tên: `409 category-name-taken`; id không có hoặc của Cửa hàng khác: `404 /errors/common/not-found` |
| `DELETE /api/v1/catalog/categories/{id}` | Chủ cửa hàng → `204`. Còn Sản phẩm (mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh): `409 /errors/catalog/category-in-use`; id không có hoặc của Cửa hàng khác: `404 /errors/common/not-found` |
| `GET/POST/PUT /api/v1/catalog/customers…` | Khách hàng (cả hai vai trò). `POST` tạo nhanh chỉ cần `phone` (FR-14) |
| `GET /internal/catalog/product-changes?after=<cursor>&limit=500` | Header `X-Service-Key`. Phản hồi `{items: [{storeId, productId, searchText, status: "ACTIVE"\|"INACTIVE", changedAt}], nextCursor}`. `after` bỏ trống = từ đầu. `nextCursor` là chuỗi mờ (base64url của `changedAt\|productId`), `null` khi hết |

### 3.3 inventory (Anh) — GĐ1–GĐ2

| API | Mô tả |
|---|---|
| Java `StockService.apply(documentType, documentId, lines)` | `lines: [{lineNo, variantId, delta, unitCost}]` → `[{lineNo, variantId, unitCost, quantityAfter}]`. Thiếu hàng: `422 /errors/inventory/insufficient-stock` (rollback cả chứng từ) |
| Java `StockQuery.find(ids)`, `lowStock()` | `StockView{variantId, quantity, minQuantity, avgUnitCost, active}` |
| `GET /api/v1/inventory/stock?q=&categoryId=&belowMin=&includeInactive=&page=&size=` | Cả hai vai trò; `avgUnitCost` chỉ có khi là Chủ cửa hàng (AD-16) |
| `GET /api/v1/inventory/low-stock` | Cả hai vai trò: `[{variantId, productName, variantName, quantity, minQuantity}]` |
| `GET /api/v1/inventory/ledger?variantId=&documentType=&fromDate=&toDate=&page=&size=` | Chủ cửa hàng (màn KHO-07 của Hiếu): `PageResponse<{createdAt, documentType, documentId, variantId, productName, variantName, delta, quantityAfter, actorName}>` |

### 3.4 sales (Anh) — GĐ1–GĐ2

| API | Mô tả |
|---|---|
| `POST /api/v1/sales/orders/quote` | Không ghi gì (AD-8). Header `Idempotency-Key` của lần thanh toán. Body `{customerId?, lines: [{variantId, quantity, discount?: {type: "PERCENT"\|"AMOUNT", value}}], orderDiscount?, pointsToRedeem?, paymentMethod?: "CASH"\|"BANK_TRANSFER", cashReceived?}` → `{lines: [{lineNo, variantId, productName, variantName, quantity, unitPrice, lineDiscount, orderDiscountShare, netLineAmount}], subtotal, lineDiscountTotal, orderDiscount, pointsDiscount, total, change?, vietqrPayload?, transferRef?, warnings: [{variantId, code: "INSUFFICIENT_STOCK", available}]}` |
| `POST /api/v1/sales/orders` | Như `quote` + `quotedTotal`; header `Idempotency-Key`. Tổng tính lại khác `quotedTotal`: `409 /errors/sales/cart-stale`. Thành công `201 Order` |
| `GET /api/v1/sales/sellable?ids=` hoặc `?q=&categoryId=&minPrice=&maxPrice=&inStock=&page=&size=` | Hàng bán được, gộp catalog + inventory: `{variantId, productId, productName, variantName, sku, barcode, unit, salePrice, quantity, belowMin}` (không có Giá vốn) |
| `GET /api/v1/sales/orders?customerId=&page=&size=` | Màn KH-03 của Hiếu: `PageResponse<{orderId, orderCode, createdAt, status, total, sellerName}>` |
| `GET /api/v1/sales/customers/{customerId}/purchase-summary` | `{orderCount, totalSpent, lastPurchaseAt}` (FR-20) |
| `POST /api/v1/sales/orders/{id}/cancel`, `POST /api/v1/sales/orders/{id}/returns` | Header `Idempotency-Key`; quy tắc AD-16, AD-17 |
| Java `LoyaltyQuery.balanceOf(customerId)` | GĐ3 |

### 3.5 reporting (Lân) — GĐ1–GĐ2

`GET /api/v1/reporting/q/{Qn}` với `Qn` từ `Q1` đến `Q9` (enum `QueryId`), chỉ Chủ cửa hàng. Tham số của từng Qn theo PRD mục 4.6.1 và JSON schema trong `ai-service/app/qa/tools.py` (hai nơi phải khớp; golden test kiểm). Phản hồi dùng chung một khung:

```json
{
  "queryId": "Q1",
  "range": { "fromDate": "2026-11-01", "toDate": "2026-11-06" },
  "params": { "groupBy": "day" },
  "columns": [ { "key": "date", "label": "Ngày", "type": "date" }, { "key": "revenue", "label": "Doanh thu", "type": "money" } ],
  "rows": [ { "date": "2026-11-01", "revenue": 1250000 } ],
  "summary": { "revenue": 5000000 }
}
```

`type` của cột: `text`, `date`, `money`, `int`, `percent`. Mỗi phản hồi luôn có `range` (AD-10).

### 3.6 ai-service (Lân) — GĐ1–GĐ2

| API | Mô tả |
|---|---|
| `GET /ai/search?q=&limit=20` | Cả hai vai trò; `{items: [{productId, score}]}` (AD-12). Không tính Hạn mức AI |
| `POST /ai/ask` | Chỉ Chủ cửa hàng; body `{question}` → `{answer, interpretation, table, chart, reportLink, disclaimer, quota: {used, limit, remaining}, refused}` (AD-11). Hết hạn mức `429 /errors/ai/quota-exceeded` |
| `GET /ai/health` | `{status, modelLoaded}` |
| `frontend/src/shared/api/aiSearch.ts` | `searchProducts(q) → {mode: "semantic"\|"normal", ids}`; chờ tối đa 1,5 giây rồi chuyển sang `GET /api/v1/sales/sellable?q=` |

## 4. API giả khi chưa có bên cung cấp

- **Backend:** viết một bean `@Profile("dev")` hoặc lớp giả trong test hiện thực interface Java ở trên, trả dữ liệu cố định. Xóa khi bên cung cấp bàn giao.
- **Frontend:** mock trong Vitest bằng `vi.fn()` trên `shared/api/client.ts`; khi chạy dev, trang chưa có API hiện thông báo "Sẽ làm ở GĐ…".
- **Dịch vụ AI:** test dùng `httpx.MockTransport` cho `reporting` và change-feed.
