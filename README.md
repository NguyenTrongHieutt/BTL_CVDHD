# Hệ thống quản lý bán hàng tích hợp AI — Nhóm 17

Web SaaS cho cửa hàng bán lẻ nhỏ (mỗi cửa hàng một quầy): bán hàng, kho, khách hàng, báo cáo, cùng Trợ lý AI với tìm kiếm sản phẩm theo ngữ nghĩa và hỏi đáp dữ liệu bằng tiếng Việt.

| Thư mục | Nội dung | Người giữ | Công nghệ |
|---|---|---|---|
| `backend/` | Modular monolith: `platform`, `catalog` (Hiếu), `inventory`, `sales` (Anh), `reporting`, `devtools.seed` (Lân) | cả nhóm | Java 25, Spring Boot 4.1.2, Spring Modulith 2.1.2 |
| `frontend/` | SPA; `src/features/<module>`, `src/shared` | cả nhóm, `shared` do Anh giữ | React 19, TypeScript 7, Vite 8, Ant Design 6 |
| `ai-service/` | Tìm kiếm ngữ nghĩa, Hỏi đáp dữ liệu, Hạn mức AI | Lân | Python 3.14, FastAPI, sentence-transformers, Gemini |
| `deploy/` | Docker Compose, nginx, khởi tạo database; `seed/` dữ liệu mẫu (Lân) | Hiếu | PostgreSQL 18 + pgvector |
| `eval/` | Bộ câu hỏi đánh giá AI, script tính đáp án độc lập | Lân | Python |
| `docs/` | Hợp đồng API, mã lỗi, cơ sở dữ liệu | cả nhóm | |

Tài liệu gốc nằm ngoài repo, ở thư mục đồ án: Tài liệu kiến trúc, Kế hoạch phân công (lịch, sơ đồ mũi tên), PRD và spine kiến trúc (`_bmad-output/`).

## Cài đặt (một lần)

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| Docker Desktop | có Docker Compose v2 trở lên | chạy database, Testcontainers và bản demo |
| JDK | 25 | Maven không cần cài: dùng `./mvnw` (Maven 3.9.16) |
| Node.js | 24 trở lên (image build dùng Node 26) | |
| Python + uv | Python 3.14, uv 0.11+ | `uv` tự cài Python đúng phiên bản nếu thiếu |

```bash
git clone https://github.com/NguyenTrongHieutt/BTL_CVDHD.git
cd BTL_CVDHD
cp deploy/.env.example deploy/.env      # rồi đổi mật khẩu và JWT_SECRET (≥ 32 ký tự); KHÔNG commit file này
```

Nếu máy đã có PostgreSQL ở cổng 5432, đặt `DB_HOST_PORT=5433` trong `deploy/.env`.

## Chạy khi phát triển

```bash
# 1. Database (lần đầu tự chạy deploy/db/init.sql)
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d db

# 2. Backend: http://localhost:8080 (Swagger: /swagger-ui.html). Tự đọc deploy/.env và chạy Flyway.
cd backend && ./mvnw spring-boot:run                 # Windows: mvnw.cmd spring-boot:run; đổi cổng: SERVER_PORT=8081

# 3. Dịch vụ AI: http://localhost:8000/ai/health
cd ai-service && uv sync && uv run uvicorn app.main:app --reload --port 8000

# 4. Frontend: http://localhost:5173 (Vite proxy /api → 8080, /ai → 8000)
cd frontend && npm install && npm run dev
```

**Tài khoản thử (profile `dev`, tự tạo khi backend khởi động):** `dev.chu.a`, `dev.nv.a` (Cửa hàng thử A, có tài khoản VietQR giả), `dev.chu.b`, `dev.nv.b` (Cửa hàng thử B). Mật khẩu: `demo1234`. "chu" là Chủ cửa hàng, "nv" là Nhân viên. Tài khoản này chỉ để phát triển.

**Tài khoản của dữ liệu mẫu (sau khi job seed nạp xong, từ SD-01):** Chủ cửa hàng `0901000001` (Tạp hóa Lan) và `0902000001` (Thời trang Mai); Nhân viên `hoa.lan`, `minh.lan`, `linh.mai`, `tuan.mai`. Mật khẩu `Demo@2026`. Chi tiết: [deploy/seed/README.md](deploy/seed/README.md).

Nếu cổng 8080 đã bị dùng: chạy backend với `SERVER_PORT=8081` và frontend với `BACKEND_URL=http://localhost:8081 npm run dev`.

## Chạy toàn bộ bằng Docker (bản demo)

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build   # http://localhost
bash deploy/reset-demo                     # khôi phục dữ liệu demo (Windows: deploy/reset-demo.ps1)
```

Trên một số máy Windows (Docker Desktop, Compose 2.39), lệnh `docker compose build` hoặc `up --build` build xong (dòng cuối `naming to … done`) nhưng không thoát. Image lúc đó đã có sẵn: bấm Ctrl+C rồi chạy `bash deploy/reset-demo --no-build` hoặc `docker compose … up -d --no-build`.

Đúng 4 container (AD-1): `web` (nginx + React), `backend`, `ai-service`, `db`. nginx định tuyến `/` → SPA, `/api/` → backend, `/ai/` → ai-service; ai-service tắt thì nginx vẫn chạy và trả lỗi 503 dạng JSON.

## Test

```bash
cd backend && ./mvnw verify          # cần Docker đang chạy (Testcontainers dùng pgvector/pgvector:pg18)
cd ai-service && uv run pytest
cd frontend && npm run lint && npm test && npm run build
```

Bộ test bắt buộc của backend ở GĐ0: ranh giới module (Modulith), chỉ module chủ được ghi bảng, không gọi giờ hệ thống trực tiếp, RLS giữa 2 Cửa hàng, phân quyền mặc định cấm, đăng nhập và cookie, chống tạo trùng, mã Đơn hàng, Nhật ký thao tác, hợp đồng JWT Java–Python (`docs/contracts/jwt-fixture.json`).

## Quy ước

Xem [docs/api/contracts.md](docs/api/contracts.md) (API giữa các thành viên), [docs/errors.md](docs/errors.md), [docs/database.md](docs/database.md).
