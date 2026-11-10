package vn.nhom17.banhang.catalog.internal.supplier;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.PageResponse;

/**
 * Nhà cung cấp (FR-23, KHO-02). Xem: cả hai vai trò; thêm, sửa: chỉ Chủ cửa hàng. Quyền khai báo ở
 * {@code SecurityConfig} của platform (AD-16). Không có xóa (ngoài phạm vi), không có {@code Idempotency-Key}: Nhà
 * cung cấp không phải chứng từ; gửi lại cùng mã đã bị unique chặn ({@code 409 supplier-code-taken}).
 */
@RestController
@RequestMapping(SupplierController.PATH)
class SupplierController {

    static final String PATH = "/api/v1/catalog/suppliers";

    private final SupplierService suppliers;

    SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    /**
     * Body thêm, sửa; kiểm ở {@link SupplierService}, trả lỗi theo trường.
     *
     * @param name    bắt buộc, tối đa 200 ký tự
     * @param code    tùy chọn, tối đa 30 ký tự: chữ cái không dấu, chữ số, {@code -}, {@code _}; lưu chữ HOA
     * @param phone   tùy chọn, tối đa 20 ký tự
     * @param address tùy chọn, tối đa 300 ký tự
     * @param note    tùy chọn, tối đa 500 ký tự, được xuống dòng
     */
    record SupplierRequest(String name, String code, String phone, String address, String note) {
    }

    /**
     * Tìm theo tên (không dấu, không phân biệt hoa thường), mã (bắt đầu bằng) hoặc số điện thoại (khớp một phần).
     * {@code sort}: {@code name} (mặc định) hoặc {@code createdAt}, kèm {@code asc} hay {@code desc}.
     */
    @GetMapping
    PageResponse<SupplierView> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false) String sort) {
        return suppliers.list(q, page, size, sort);
    }

    @GetMapping("/{id}")
    SupplierView get(@PathVariable UUID id) {
        return suppliers.get(id);
    }

    @PostMapping
    ResponseEntity<SupplierView> create(@RequestBody SupplierRequest request) {
        SupplierView created = suppliers.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.supplierId())).body(created);
    }

    /** Thay toàn bộ: trường tùy chọn thiếu hoặc rỗng thì xóa giá trị cũ. */
    @PutMapping("/{id}")
    SupplierView update(@PathVariable UUID id, @RequestBody SupplierRequest request) {
        return suppliers.update(id, request);
    }
}
