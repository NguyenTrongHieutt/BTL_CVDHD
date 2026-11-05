/**
 * Module sales (chủ: Anh): Đơn hàng, báo giá giỏ hàng ({@code POST /api/v1/sales/orders/quote}), thanh toán,
 * VietQR (AD-22), Phiếu trả hàng, Điểm tích lũy, danh sách hàng bán được.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"inventory", "catalog", "platform"})
package vn.nhom17.banhang.sales;
