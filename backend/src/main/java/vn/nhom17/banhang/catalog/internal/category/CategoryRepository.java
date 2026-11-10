package vn.nhom17.banhang.catalog.internal.category;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Chỉ thấy Danh mục của Cửa hàng hiện tại ({@code @TenantId} và RLS). SP-01 dùng để kiểm {@code categoryId}. */
public interface CategoryRepository extends JpaRepository<CategoryEntity, UUID> {
}
