# Hệ thống quản lý bán hàng tích hợp AI — Nhóm 17

Web SaaS cho cửa hàng bán lẻ nhỏ: bán hàng tại quầy, kho, khách hàng, báo cáo, cùng Trợ lý AI (tìm kiếm sản phẩm theo ngữ nghĩa, hỏi đáp dữ liệu bằng tiếng Việt).

| Thư mục | Nội dung | Công nghệ |
|---|---|---|
| `backend/` | Modular monolith: `platform`, `catalog`, `inventory`, `sales`, `reporting`, `devtools.seed` | Java 25, Spring Boot 4.1.2, Spring Modulith 2.1.2 |
| `frontend/` | SPA | React 19, TypeScript 7, Vite 8, Ant Design 6 |
| `ai-service/` | Dịch vụ AI | Python 3.14, FastAPI, sentence-transformers, Gemini |
| `deploy/` | Docker Compose, nginx, khởi tạo database, dữ liệu mẫu | PostgreSQL 18 + pgvector |
| `eval/` | Bộ câu hỏi đánh giá AI, script tính đáp án | Python |
| `docs/` | Hợp đồng API, mã lỗi, quy ước | |

Hướng dẫn cài đặt và chạy: xem mục "Chạy dự án" (cập nhật ở giai đoạn 0).
