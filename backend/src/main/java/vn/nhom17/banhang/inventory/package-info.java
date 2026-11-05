/**
 * Module inventory (chủ: Anh): Tồn kho, Giá vốn, lịch sử xuất nhập, Phiếu nhập, Phiếu kiểm kho, Lô hàng.
 * Mọi thay đổi Tồn kho đi qua {@link vn.nhom17.banhang.inventory.StockService} (AD-6).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"catalog", "platform"})
package vn.nhom17.banhang.inventory;
