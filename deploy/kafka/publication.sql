\set ON_ERROR_STOP on
-- Chạy sau Flyway, bằng postgres; Debezium không có quyền tạo publication hoặc sửa bảng.
GRANT SELECT ON app.sales_order, app.sales_order_line, app.sales_return, app.sales_return_line
    TO cdc_reader;
ALTER TABLE app.sales_order REPLICA IDENTITY FULL;
ALTER TABLE app.sales_order_line REPLICA IDENTITY FULL;
ALTER TABLE app.sales_return REPLICA IDENTITY FULL;
ALTER TABLE app.sales_return_line REPLICA IDENTITY FULL;

SELECT 'CREATE PUBLICATION pos_reporting FOR TABLE app.sales_order, app.sales_order_line, app.sales_return, app.sales_return_line'
WHERE NOT EXISTS (SELECT FROM pg_publication WHERE pubname = 'pos_reporting') \gexec
ALTER PUBLICATION pos_reporting SET TABLE
    app.sales_order, app.sales_order_line, app.sales_return, app.sales_return_line;
