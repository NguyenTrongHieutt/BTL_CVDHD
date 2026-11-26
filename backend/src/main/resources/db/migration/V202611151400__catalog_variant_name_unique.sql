-- Module catalog (chủ: Hiếu). Biến thể Sản phẩm (FR-7, SP-07).
--
-- Hai Biến thể chưa xóa mềm của cùng một Sản phẩm không trùng nhãn, không phân biệt hoa thường (409
-- /errors/catalog/variant-name-taken). Biến thể đã ngừng kinh doanh vẫn giữ nhãn của nó (ngừng là một chiều, không
-- xóa). Biến thể mặc định của Sản phẩm một Biến thể không có nhãn (NULL) nên không tính.

CREATE UNIQUE INDEX product_variant_name_uq ON app.product_variant (store_id, product_id, lower(name))
    WHERE deleted_at IS NULL AND name IS NOT NULL;
