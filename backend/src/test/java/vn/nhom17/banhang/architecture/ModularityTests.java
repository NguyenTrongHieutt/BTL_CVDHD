package vn.nhom17.banhang.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import vn.nhom17.banhang.BanhangBackendApplication;

/** AD-2: chiều phụ thuộc giữa module đúng theo sơ đồ của spine; chỉ dùng API ở package gốc của module khác. */
class ModularityTests {

    @Test
    void moduleDependenciesFollowTheSpine() {
        ApplicationModules.of(BanhangBackendApplication.class).verify();
    }
}
