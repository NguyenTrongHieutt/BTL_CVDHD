package vn.nhom17.banhang.catalog.internal.supplier;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Chỉ thấy Nhà cung cấp của Cửa hàng hiện tại ({@code @TenantId} và RLS). */
public interface SupplierRepository extends JpaRepository<SupplierEntity, UUID> {

    /** Nhà cung cấp chưa xóa mềm; id của Cửa hàng khác cũng là "không có". */
    Optional<SupplierEntity> findByIdAndDeletedAtIsNull(UUID id);
}
