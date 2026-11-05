package vn.nhom17.banhang.platform.internal.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.nhom17.banhang.platform.CurrentContext;

/**
 * Hook DUY NHẤT đặt ngữ cảnh Cửa hàng cho database (AD-5 mục 4): ngay khi transaction bắt đầu, chạy
 * {@code set_config('app.store_id', <store>, true)}. Giá trị chỉ sống trong transaction, nên truy vấn nằm ngoài
 * transaction sẽ thấy 0 dòng (RLS), không thấy dữ liệu của Cửa hàng khác.
 */
class TenantTransactionManager extends JpaTransactionManager {

    private final transient CurrentContext context;

    TenantTransactionManager(EntityManagerFactory emf, CurrentContext context) {
        super(emf);
        this.context = context;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        Optional<UUID> storeId = context.storeIdIfPresent();
        if (storeId.isEmpty()) {
            return; // chưa đăng nhập: RLS trả 0 dòng
        }
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        holder.getEntityManager()
                .createNativeQuery("select set_config('app.store_id', :storeId, true)")
                .setParameter("storeId", storeId.get().toString())
                .getSingleResult();
    }
}
