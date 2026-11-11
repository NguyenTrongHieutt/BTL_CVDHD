package vn.nhom17.banhang.catalog.internal.category;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Danh mục (FR-4, SP-04). Xem: cả hai vai trò (ô lọc ở màn Sản phẩm cần); thêm, đổi tên, xóa: chỉ Chủ cửa hàng.
 * Quyền khai báo ở {@code SecurityConfig} của platform (AD-16). Không có {@code Idempotency-Key}: Danh mục không phải
 * chứng từ.
 */
@RestController
@RequestMapping("/api/v1/catalog/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    /** Tên được cắt khoảng trắng hai đầu; bắt buộc, tối đa 100 ký tự (kiểm ở {@link CategoryService}). */
    record CategoryRequest(String name) {
    }

    @GetMapping
    PageResponse<CategoryView> list(@RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return categories.list(page, size);
    }

    @PostMapping
    ResponseEntity<CategoryView> create(@RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categories.create(request.name()));
    }

    @PutMapping("/{id}")
    CategoryView rename(@PathVariable UUID id, @RequestBody CategoryRequest request) {
        return categories.rename(id, request.name());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        categories.delete(id);
        return ResponseEntity.noContent().build();
    }
}
