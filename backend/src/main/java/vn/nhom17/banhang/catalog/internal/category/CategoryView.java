package vn.nhom17.banhang.catalog.internal.category;

import java.util.UUID;

/**
 * Một dòng Danh mục trả cho frontend. {@code productCount}: mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã
 * ngừng kinh doanh (đúng như khóa ngoại {@code RESTRICT} chặn); lớn hơn 0 thì không xóa được.
 */
record CategoryView(UUID id, String name, long productCount) {
}
