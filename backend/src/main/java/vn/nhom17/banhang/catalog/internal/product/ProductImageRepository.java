package vn.nhom17.banhang.catalog.internal.product;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ảnh Sản phẩm, theo id Sản phẩm; chỉ thấy ảnh của Cửa hàng hiện tại ({@code @TenantId} và RLS). */
public interface ProductImageRepository extends JpaRepository<ProductImageEntity, UUID> {
}
