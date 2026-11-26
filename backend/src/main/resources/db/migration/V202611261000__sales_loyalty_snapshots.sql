-- KH-04 / AD-17: giữ tỷ lệ và số điểm thực sự cộng khi bán để hủy/trả không phụ thuộc cài đặt mới.
-- Đơn cũ chưa tích điểm qua nghiệp vụ này nhận 0, không bị trừ điểm khi hủy hoặc trả hàng.
ALTER TABLE app.sales_order
    ADD COLUMN points_earned integer NOT NULL DEFAULT 0 CHECK (points_earned >= 0),
    ADD COLUMN loyalty_earn_amount bigint NOT NULL DEFAULT 0 CHECK (loyalty_earn_amount >= 0);
