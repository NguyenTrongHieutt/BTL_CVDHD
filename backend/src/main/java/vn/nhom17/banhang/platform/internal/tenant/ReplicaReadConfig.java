package vn.nhom17.banhang.platform.internal.tenant;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.nhom17.banhang.platform.ReplicaRead;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.db.replica-enabled", havingValue = "true")
@EnableScheduling
class ReplicaReadConfig {
    @Bean(destroyMethod = "close")
    ReplicaDataSource dataSource(DataSourceProperties properties, Environment environment,
                                 @Value("${app.db.replica-urls:}") String replicaUrls) {
        var urls = Arrays.stream(replicaUrls.split(",")).map(String::strip).filter(url -> !url.isEmpty()).toList();
        if (urls.size() != 2 || urls.get(0).equals(urls.get(1)) || urls.contains(properties.determineUrl())) {
            throw new IllegalArgumentException("Cần cấu hình hai URL replica khác nhau và khác primary");
        }

        HikariDataSource primary = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(primary));
        var replicas = urls.stream().map(url -> {
            var pool = new HikariDataSource();
            pool.setJdbcUrl(url);
            pool.setUsername(primary.getUsername());
            pool.setPassword(primary.getPassword());
            pool.setReadOnly(true);
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(400);
            pool.setValidationTimeout(250);
            pool.setInitializationFailTimeout(-1);
            pool.addDataSourceProperty("connectTimeout", "1");
            pool.addDataSourceProperty("ApplicationName", "banhang-replica-read");
            return pool;
        }).toList();
        return new ReplicaDataSource(primary, replicas);
    }

    @Bean
    WebMvcConfigurer replicaReadMvcConfigurer(ReplicaDataSource dataSource) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new AsyncHandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        if ("GET".equals(request.getMethod()) && !"true".equals(request.getHeader("X-Read-Primary"))
                                && handler instanceof HandlerMethod method && method.hasMethodAnnotation(ReplicaRead.class)) {
                            dataSource.beginRead();
                        }
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                Object handler, Exception failure) {
                        dataSource.endRead();
                    }

                    @Override
                    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                                               Object handler) {
                        dataSource.endRead();
                    }
                });
            }
        };
    }
}
