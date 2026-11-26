-- Module catalog (chủ: Hiếu). Tìm tên Sản phẩm, nhãn Biến thể và tên Khách hàng (SP-05, SP-07, KH-02).
-- Lưu đúng biểu thức khớp tên của SearchText: bỏ dấu, chữ thường, gộp khoảng trắng; không cắt hai đầu.
-- f_unaccent đã IMMUTABLE, STRICT: tên NULL vẫn cho search_name NULL. PostgreSQL tự tính khi INSERT/đổi tên,
-- kể cả khi ghi bằng JPA hoặc SQL trực tiếp; không thêm cột vào entity hay truyền giá trị cho cột sinh.
-- Chỉ dùng search_name để lọc; sắp tên vẫn lower(app.f_unaccent(name)) như trước (không gộp khoảng trắng).
-- ADD ... STORED tính lại các dòng hiện có và khóa đọc/ghi tới cuối transaction; triển khai trước code đọc cột.
-- Quyền trên bảng, chủ sở hữu app_owner và policy RLS hiện có được giữ nguyên; không cần cấp quyền mới.

ALTER TABLE app.product
    ADD COLUMN search_name text GENERATED ALWAYS AS
        (regexp_replace(lower(app.f_unaccent(name)), '\s+', ' ', 'g')) STORED;

ALTER TABLE app.product_variant
    ADD COLUMN search_name text GENERATED ALWAYS AS
        (regexp_replace(lower(app.f_unaccent(name)), '\s+', ' ', 'g')) STORED;

ALTER TABLE app.customer
    ADD COLUMN search_name text GENERATED ALWAYS AS
        (regexp_replace(lower(app.f_unaccent(name)), '\s+', ' ', 'g')) STORED;
