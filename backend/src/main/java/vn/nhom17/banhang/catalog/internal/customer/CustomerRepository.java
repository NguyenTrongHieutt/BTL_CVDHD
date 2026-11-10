package vn.nhom17.banhang.catalog.internal.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Chỉ thấy Khách hàng của Cửa hàng hiện tại ({@code @TenantId} và RLS). */
public interface CustomerRepository extends JpaRepository<CustomerEntity, UUID> {

    /** Khách hàng chưa xóa mềm; id của Cửa hàng khác cũng là "không có". */
    Optional<CustomerEntity> findByIdAndDeletedAtIsNull(UUID id);
}
