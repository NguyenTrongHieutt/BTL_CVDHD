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
| `/errors/inventory/insufficient-stock` | 422 | Không đủ hàng; cả chứng từ rollback (AD-6) | inventory |
| `/errors/inventory/variant-inactive` | 409 | Nhập hàng cho Biến thể đã ngừng kinh doanh (AD-14) | inventory |
| `/errors/sales/invalid-state` | 409 | Chuyển trạng thái Đơn hàng không hợp lệ, ví dụ hủy đơn đã hủy hoặc đã có Phiếu trả (AD-17) | sales |
| `/errors/sales/cart-stale` | 409 | Tổng tiền tính lại khác `quotedTotal`, hoặc giá, trạng thái đã đổi (AD-8) | sales |
| `/errors/ai/quota-exceeded` | 429 | Hết 50 câu Hỏi đáp dữ liệu trong ngày (AD-13) | ai |
| `/errors/ai/unavailable` | 503 | Dịch vụ AI không sẵn sàng (nginx hoặc ai-service trả, AD-1) | ai |
| `/errors/ai/not-implemented` | 501 | Endpoint AI chưa làm (khung GĐ0: `/ai/search` trước AI-01, `/ai/ask` trước AI-03) | ai |
