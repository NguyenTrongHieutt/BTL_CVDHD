package vn.nhom17.banhang.platform.internal.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.nhom17.banhang.platform.internal.security.SecurityProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class PlatformConfig {

    /** AD-8: mọi module lấy giờ qua Clock được inject; test và seed thay bằng Clock cố định. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** AD-9: băm mật khẩu bằng DelegatingPasswordEncoder (mặc định bcrypt). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
