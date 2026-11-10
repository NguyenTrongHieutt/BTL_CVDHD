# Danh sách mã lỗi (Problem Details `type`)

Mọi lỗi API trả RFC 9457 Problem Details (AD-19). Mỗi module chỉ thêm mã vào nhóm của mình; frontend dịch `type` sang câu tiếng Việt trong `frontend/src/shared/messages.vi.ts`.

| `type` | HTTP | Khi nào | Nhóm |
|---|---|---|---|
| `/errors/common/validation` | 400 | Dữ liệu nhập sai; có `errors[]` gồm `field`, `message` và có thể có `code` | common |
| `/errors/common/method-not-allowed` | 405 | Sai phương thức HTTP | common |
| `/errors/common/unauthorized` | 401 | Chưa đăng nhập, token hết hạn hoặc sai | common |
| `/errors/common/forbidden` | 403 | Sai vai trò, hoặc route chưa khai báo (mặc định cấm, AD-16) | common |
| `/errors/common/not-found` | 404 | Không có, hoặc là dữ liệu của Cửa hàng khác (AD-5) | common |
| `/errors/common/idempotency-key-missing` | 422 | Tạo chứng từ mà thiếu `Idempotency-Key`, hoặc khóa không phải UUID hợp lệ | common |
| `/errors/common/idempotency-key-reuse` | 409 | Cùng `Idempotency-Key` nhưng nội dung khác (AD-7) | common |
| `/errors/common/internal` | 500 | Lỗi không mong đợi; báo kèm `requestId` | common |
| `/errors/platform/invalid-credentials` | 401 | Sai tên đăng nhập hoặc mật khẩu, hoặc tài khoản đã bị khóa khi đăng nhập (một thông báo chung, không lộ tài khoản có tồn tại hay bị khóa, FR-44) | platform |
| `/errors/platform/account-locked` | 401 | Tài khoản bị khóa khi đang dùng (AD-9) | platform |
| `/errors/platform/login-taken` | 409 | Tên đăng nhập, số điện thoại hoặc email đã dùng (AD-9) | platform |
| `/errors/catalog/category-name-taken` | 409 | Cửa hàng đã có Danh mục cùng tên, không phân biệt hoa thường (unique `category_name_uq`, FR-4) | catalog |
| `/errors/catalog/category-in-use` | 409 | Xóa Danh mục còn Sản phẩm: mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh (khóa ngoại `RESTRICT`, FR-4) | catalog |
| `/errors/catalog/barcode-taken` | 409 | Cửa hàng đã có Biến thể (chưa xóa mềm) dùng mã vạch này (unique `product_variant_barcode_uq`, FR-1). Mã vạch lưu chữ HOA nên khác hoa thường vẫn là trùng. Cửa hàng khác dùng cùng mã vạch thì được | catalog |
| `/errors/catalog/sku-taken` | 409 | Cửa hàng đã có Biến thể (chưa xóa mềm) dùng SKU người dùng nhập (unique `product_variant_sku_uq`, FR-1). SKU lưu chữ HOA nên khác hoa thường vẫn là trùng. SKU tự sinh không gây lỗi này (sinh lại tới khi còn trống) | catalog |
| `/errors/catalog/product-inactive` | 409 | Sửa Sản phẩm đã ngừng kinh doanh (FR-2, SP-02). Ngừng kinh doanh là một chiều, không mở bán lại (FR-3, AD-14); gửi y nguyên cũng bị từ chối | catalog |
| `/errors/catalog/customer-phone-taken` | 409 | Thêm Khách hàng, hoặc sửa sang số điện thoại mà Khách hàng khác (chưa xóa mềm) của Cửa hàng đang dùng (unique `customer_phone_uq`, FR-18). Số so sau khi chuẩn hóa (`+84…` là `0…`, bỏ dấu cách, dấu chấm, gạch ngang). Có trường mở rộng `existingCustomerId`: id Khách hàng đang dùng số đó, để mở luôn thay vì thêm mới. Cửa hàng khác dùng cùng số thì được | catalog |
| `/errors/catalog/supplier-code-taken` | 409 | Thêm Nhà cung cấp, hoặc sửa sang mã mà Nhà cung cấp khác (chưa xóa mềm) của Cửa hàng đang dùng (unique `supplier_code_uq`, FR-23). Mã lưu chữ HOA nên khác hoa thường vẫn là trùng. Nhà cung cấp không có mã không bao giờ trùng; trùng tên được phép. Cửa hàng khác dùng cùng mã thì được | catalog |
| `/errors/inventory/insufficient-stock` | 422 | Không đủ hàng; cả chứng từ rollback (AD-6) | inventory |
| `/errors/inventory/variant-inactive` | 409 | Nhập hàng cho Biến thể đã ngừng kinh doanh (AD-14) | inventory |
| `/errors/sales/invalid-state` | 409 | Chuyển trạng thái Đơn hàng không hợp lệ, ví dụ hủy đơn đã hủy hoặc đã có Phiếu trả (AD-17) | sales |
| `/errors/sales/cart-stale` | 409 | Tổng tiền tính lại khác `quotedTotal`, hoặc giá, trạng thái đã đổi (AD-8) | sales |
| `/errors/ai/quota-exceeded` | 429 | Hết 50 câu Hỏi đáp dữ liệu trong ngày (AD-13) | ai |
| `/errors/ai/unavailable` | 503 | Dịch vụ AI không sẵn sàng (nginx hoặc ai-service trả, AD-1) | ai |
| `/errors/ai/not-implemented` | 501 | Endpoint AI chưa làm (khung GĐ0: `/ai/search` trước AI-01, `/ai/ask` trước AI-03) | ai |
