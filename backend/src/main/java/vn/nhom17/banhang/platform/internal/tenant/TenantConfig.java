package vn.nhom17.banhang.platform.internal.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import vn.nhom17.banhang.platform.CurrentContext;

/** Hai lớp tách dữ liệu (AD-5): {@code @TenantId} của Hibernate và hook đặt ngữ cảnh cho RLS. */
@Configuration(proxyBeanMethods = false)
class TenantConfig {

    /** Không có ngữ cảnh thì dùng id rỗng: Hibernate lọc theo nó và RLS cũng trả 0 dòng. */
    static final UUID NO_TENANT = new UUID(0L, 0L);

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf, CurrentContext context) {
        return new TenantTransactionManager(emf, context);
    }

    @Bean
    CurrentTenantIdentifierResolver<UUID> currentTenantIdentifierResolver(CurrentContext context) {
        return new CurrentTenantIdentifierResolver<>() {
            @Override
            public UUID resolveCurrentTenantIdentifier() {
                return context.storeIdIfPresent().orElse(NO_TENANT);
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return false;
            }
        };
    }

    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer(CurrentTenantIdentifierResolver<UUID> resolver) {
        return props -> props.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
