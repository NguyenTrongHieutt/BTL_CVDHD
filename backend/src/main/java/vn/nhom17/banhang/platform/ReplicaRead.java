package vn.nhom17.banhang.platform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Cho phép một API tra cứu GET dùng bản sao khi dữ liệu đã bắt kịp mốc được kiểm tra. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReplicaRead {}
