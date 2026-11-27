package vn.nhom17.banhang.catalog.internal.customer;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.nhom17.banhang.platform.PageResponse;
import vn.nhom17.banhang.platform.ReplicaRead;
import vn.nhom17.banhang.platform.BackofficeWriteQueue;

/**
 * Khách hàng (FR-18 KH-01, FR-19 KH-02): xem, tìm, thêm, sửa; cả Chủ cửa hàng và Nhân viên. Quyền khai báo ở
 * {@code SecurityConfig} của platform (AD-16). Không có xóa (ngoài phạm vi), không có {@code Idempotency-Key}:
 * gửi lại cùng số điện thoại đã bị unique chặn, nhận {@code 409} kèm {@code existingCustomerId}.
 * Khi bật hàng đợi back-office, nhận thêm khóa tùy chọn để retry an toàn và trả 202 kèm statusUrl.
 */
@RestController
@RequestMapping(CustomerController.PATH)
class CustomerController {

    static final String PATH = "/api/v1/catalog/customers";

    private final CustomerService customers;
    private final BackofficeWriteQueue queue;

    CustomerController(CustomerService customers, BackofficeWriteQueue queue) {
        this.customers = customers;
        this.queue = queue;
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
    @ReplicaRead
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
    ResponseEntity<?> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody CustomerRequest request) {
        if (queue.enabled()) return queue.submit("CUSTOMER_CREATE", BackofficeWriteQueue.optionalKey(key), request).response();
        CustomerView created = customers.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.customerId())).body(created);
    }

    /** Thay toàn bộ hồ sơ: {@code name}, {@code note} thiếu hoặc rỗng thì xóa giá trị cũ. */
    @PutMapping("/{id}")
    Object update(@PathVariable UUID id, @RequestHeader(name = "Idempotency-Key", required = false) String key,
                        @RequestBody CustomerRequest request) {
        if (queue.enabled()) return queue.submit("CUSTOMER_UPDATE", BackofficeWriteQueue.optionalKey(key),
                new CustomerWriteCommands.Update(id, request)).response();
        return customers.update(id, request);
    }
}
