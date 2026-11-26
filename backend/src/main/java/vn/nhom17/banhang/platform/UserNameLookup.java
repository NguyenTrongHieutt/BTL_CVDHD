package vn.nhom17.banhang.platform;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Tên hiển thị của người dùng thuộc cửa hàng hiện tại, kể cả tài khoản đã khóa. */
public interface UserNameLookup {
    Map<UUID, String> findNames(Collection<UUID> userIds);
}
