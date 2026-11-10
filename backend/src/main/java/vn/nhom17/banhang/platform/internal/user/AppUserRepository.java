package vn.nhom17.banhang.platform.internal.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Chỉ thấy tài khoản của Cửa hàng hiện tại ({@code @TenantId} và RLS). */
public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    /**
     * Đọc và khóa dòng ({@code SELECT … FOR UPDATE}) để đổi trạng thái, mật khẩu: hai lần khóa cùng lúc chạy lần lượt,
     * lần sau thấy trạng thái lần trước đã ghi nên Nhật ký không ghi hai lần. Id của Cửa hàng khác là "không có".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUserEntity u where u.id = :id")
    Optional<AppUserEntity> findForUpdate(@Param("id") UUID id);
}
