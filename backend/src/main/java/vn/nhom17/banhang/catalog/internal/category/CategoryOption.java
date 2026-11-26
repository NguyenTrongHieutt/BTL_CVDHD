package vn.nhom17.banhang.catalog.internal.category;

import java.util.UUID;

/** Nhãn tối thiểu để khôi phục Danh mục đã chọn trong bộ lọc từ URL. */
record CategoryOption(UUID id, String name) {
}
