package vn.nhom17.banhang.catalog.internal.customer;

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
 * Khách hàng (FR-18 KH-01, FR-19 KH-02): xem, tìm, thêm, sửa; cả Chủ cửa hàng và Nhân viên. Quyền khai báo ở
 * {@code SecurityConfig} của platform (AD-16). Không có xóa (ngoài phạm vi), không có {@code Idempotency-Key}:
 * gửi lại cùng số điện thoại đã bị unique chặn, nhận {@code 409} kèm {@code existingCustomerId}.
 */
@RestController
@RequestMapping(CustomerController.PATH)
class CustomerController {

    static final String PATH = "/api/v1/catalog/customers";

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    /**
     * Body thêm, sửa; kiểm ở {@link CustomerService}, trả lỗi theo trường.
     *
     * @param phone bắt buộc: {@code 0} + 9 chữ số hoặc {@code +84} + 9 chữ số, được có dấu cách, dấu chấm, gạch ngang;
     *              lưu dạng {@code 0…}
     * @param name  tùy chọn, tối đa 100 ký tự
     * @param note  tùy chọn, tối đa 500 ký tự, được xuống dòng
     */
    record CustomerRequest(String phone, String name, String note) {
    }

    /** Tìm theo tên (không dấu, không phân biệt hoa thường) hoặc số điện thoại (khớp một phần). */
    @GetMapping
    PageResponse<CustomerView> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return customers.list(q, page, size);
    }

    @GetMapping("/{id}")
    CustomerView get(@PathVariable UUID id) {
        return customers.get(id);
    }

    @PostMapping
    ResponseEntity<CustomerView> create(@RequestBody CustomerRequest request) {
        CustomerView created = customers.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.customerId())).body(created);
    }

    /** Thay toàn bộ hồ sơ: {@code name}, {@code note} thiếu hoặc rỗng thì xóa giá trị cũ. */
    @PutMapping("/{id}")
    CustomerView update(@PathVariable UUID id, @RequestBody CustomerRequest request) {
        return customers.update(id, request);
    }
}
