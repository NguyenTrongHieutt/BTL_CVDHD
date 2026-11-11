package vn.nhom17.banhang.catalog.internal.feed;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Kết nối {@code sync_reader} của change-feed (AD-12); xem {@link SyncReaderJdbc}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SyncReaderProperties.class)
class SyncReaderConfig {

    @Bean(destroyMethod = "close")
    SyncReaderJdbc syncReaderJdbc(SyncReaderProperties props) {
        return new SyncReaderJdbc(props);
    }
}
