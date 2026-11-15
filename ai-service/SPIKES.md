# Spike GĐ0 của ai-service (CB-08)

Ngày chạy: 2026-11-05. Máy: Windows 11 + Docker Desktop 28.3.3 (Linux amd64). Người chạy: Lân (C).
Tương ứng mục **Deferred → Kiểm tra ngay ở GĐ0** của spine: mục 1 (image Python 3.14 + torch CPU)
và mục 4 (Gemini function calling với 9 công cụ). Thêm một spike phụ cho model nhúng.

## Spike 1 — Image `ai-service`: Python 3.14 + torch 2.15.0 CPU trên Linux

**Kết quả: ĐẠT, không cần lùi về Python 3.13.**

- `uv sync --frozen --no-dev` trong `python:3.14-slim` (uv từ `ghcr.io/astral-sh/uv:0.13.7`) cài
  `torch==2.15.0+cpu` từ wheel `cp314-cp314-manylinux_2_28_x86_64` của `download.pytorch.org/whl/cpu`
  (199,8 MiB). Python trong image: 3.14.10.
- Model `intfloat/multilingual-e5-small` tải lúc build vào `/models` (`HF_HOME`), kiểm số chiều 384 ngay khi build.
- Thời gian build lần đầu (không cache): cài 75 gói ~110 s, tải model ~60 s, xuất image ~105 s.
- **Kích thước image: 2,96 GB trên đĩa (`docker images`), 758 MB nội dung nén.**
  - `/app/.venv` 1,5 GB: torch 817 MB, transformers 126 MB, scipy 110 MB, sympy 77 MB, scikit-learn 49 MB, numpy 43 MB.
  - `/models` 471 MB (chỉ `model.safetensors` và file cấu hình).
- Chạy thử:
  - `docker run -p 127.0.0.1:18000:8000 -e JWT_SECRET=…` → `GET /ai/health` trả `200`
    `{"status":"ok","modelLoaded":false,…}`, có header `X-Request-Id`; `/ai/ask` không token trả `401` Problem Details.
  - `docker run --network none …` (không mạng): health `200`; trong container gọi `embedding.embed_query(...)` →
    vector (384,), độ dài 1, model nạp từ `/models` ở chế độ offline. Lần gọi đầu mất ~13 s (import torch + nạp model).
  - Chạy bằng user `app` (uid 10001); `HEALTHCHECK` báo `healthy`; `zoneinfo("Asia/Ho_Chi_Minh")` có sẵn.
  - Các container thử đã dừng và xóa.

**Lưu ý:** lần nạp model đầu ~13 s, lâu hơn hạn chờ 1,5 s của frontend (AD-12). ai-service nạp model
trong lifespan (`PRELOAD_EMBEDDING_MODEL=true` theo mặc định); `/ai/health` vẫn không tự ép nạp model.

## Spike phụ — Model nhúng e5-small với tên Sản phẩm tiếng Việt

Lệnh: `uv run python spikes/embedding_spike.py` (chạy trên máy, CPU). 10 Sản phẩm, 5 câu tìm kiếm
(3 câu chính + 2 câu gõ không dấu), đi qua đúng `app.embedding` (tiền tố `passage: ` / `query: `).

- **Số chiều 384: ĐẠT.** Vector đã chuẩn hóa (độ dài min = max = 1,000000).
- Nạp model + nhúng 10 Sản phẩm: ~25 s trên máy (lần đầu 174 s vì phải tải model). Nhúng 1 câu tìm kiếm: ~17 ms.
- Hạng của Sản phẩm mong đợi:

| Câu tìm kiếm | Mong đợi | A. `searchText` (tên · Danh mục) | B. `searchText` + bản bỏ dấu |
|---|---|---|---|
| nước giải khát có ga | Coca-Cola | 3 (sau Dầu gội Clear, Red Bull) | 3 (sau Red Bull, Dầu gội Clear) |
| dầu gội cho tóc khô | Dầu gội Sunsilk … tóc khô xơ | **1** | **1** |
| đồ ăn liền | Mì Hảo Hảo | 3 (sau Sữa tươi, Coca-Cola) | 2 |
| dau goi tri gau | Dầu gội Clear … sạch gàu | 3 (Bia Tiger đứng đầu) | **1** |
| sua tuoi khong duong | Sữa tươi Vinamilk không đường | 7 | **1** |
| **Tổng** | | top-1 1/5, top-3 4/5 | top-1 3/5, top-3 5/5 |

Top-3 đầy đủ kèm điểm: chạy lại script (kết quả ổn định, không có ngẫu nhiên).

**Nhận xét:**
- Điểm cosine của e5-small dồn trong khoảng 0,80 – 0,89 cho mọi cặp, nên **không dùng được ngưỡng điểm cố định**
  để cắt kết quả; chỉ dùng thứ hạng.
- Câu gõ không dấu là điểm yếu rõ nhất khi passage chỉ có chữ có dấu (hạng 7). Thêm bản bỏ dấu của chính `searchText`
  vào passage (B) đưa cả hai câu không dấu lên hạng 1 mà không làm hỏng câu có dấu.
- Từ đồng nghĩa/khái niệm ("nước giải khát có ga", "đồ ăn liền") còn yếu: đúng Sản phẩm chỉ ở hạng 2–3 trên 10.

Kết quả trên chỉ đánh giá E5-small; model này không còn là model mặc định của dịch vụ.

## Quyết định AI-01 — MiniLM và Hybrid Search

- Model mặc định: `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`. Vector 384 chiều nên
  tương thích với `vector(384)` hiện có; model được nạp trước khi nhận request.
- MiniLM nhận câu nguyên bản, không dùng tiền tố `query:` / `passage:` của E5.
- Hybrid Search lấy danh sách semantic từ `ai.product_embedding` và danh sách lexical từ
  `GET /api/v1/catalog/products?q=&status=ACTIVE&page=0&size=100&sort=name,asc`, chuyển tiếp JWT người dùng.
- Hai thứ hạng được hợp nhất bằng Reciprocal Rank Fusion (RRF, k=60), không so sánh trực tiếp điểm cosine
  với thứ hạng lexical. Request lexical có timeout 0,8 giây.
- Vì đổi model, embedding E5 cũ không được dùng chung; cần chạy đồng bộ catalog để tạo lại embedding với
  `model_version` mới. Cần đo chất lượng RRF trên bộ truy vấn SM-4 trước khi chốt production.
- Khi triển khai, bật `SYNC_ENABLED=true` (Compose nhận qua `AI_SYNC_ENABLED=true`) để có dữ liệu cho nhánh
  semantic; nếu tắt, nhánh lexical vẫn hoạt động nhưng index vector cũ không được dùng.

## Spike 4 — Gemini function calling với 9 công cụ Q1–Q9

**Kết quả: CHƯA CHẠY — máy chưa có `GEMINI_API_KEY`.** Mục 4 của Deferred vẫn mở.

`uv run python spikes/gemini_tools_spike.py` hiện in `chưa có GEMINI_API_KEY — spike chưa chạy` và thoát 0.

Đã sẵn sàng để chạy:
- 9 FunctionDeclaration từ `app/qa/tools.py` (`parameters_json_schema` chặt: `additionalProperties: false`, enum,
  giới hạn số; Q7–Q9 không có `parameters`), `FunctionCallingConfig(mode=AUTO)`, `temperature=0`, timeout 10 s.
- Prompt lần gọi 1 ở `app/qa/prompts.py`: chỉ đưa năm hiện tại, không đưa ngày hôm nay, nên mọi cụm thời gian
  tương đối phải đi qua `period.kind` (AD-19).
- 25 câu hỏi phát triển (gồm 5 câu gợi ý nhanh của FR-41), mỗi câu có Qn và tham số mong đợi, phủ đủ Q1–Q9 và các
  kiểu kỳ: hôm nay, hôm qua, tuần này/trước, tháng này/trước, năm nay/ngoái, N ngày qua, tháng cụ thể, khoảng ngày nêu rõ,
  một câu gõ không dấu. Thêm 4 câu ngoài phạm vi (Khách hàng cụ thể, giá vàng, dự báo, chèn lệnh) để đo tỉ lệ từ chối;
  4 câu này không tính vào tiêu chí.
- Chấm điểm:
  - **Tiêu chí AD-11 mục 10:** đúng Qn và tham số qua được Pydantic (`tools.parse_params`) ≥ 90%.
  - Thêm: khớp hẳn tham số mong đợi, so trên query string đã quy ra ngày (`tools.to_reporting_query`).
  - Thêm: độ trễ trung vị / p95 / max của lần gọi 1.
- Chế độ dự phòng `--mode structured` (AD-11 mục 10): không gửi công cụ, `response_json_schema`
  `{queryId: Q1…Q9 | null, params}`, kiểm bằng `RouterDecision` + `parse_params`.
- Logic chấm điểm đã chạy thử không cần mạng với một router giả (cố ý cho sai 1 câu chọn Qn và 1 câu sai tham số):
  script nhận đúng cả hai lỗi và ra 23/25 = 92%.
- Khóa API: script chỉ kiểm có hay không; không in, không ghi log, không ghi file giá trị khóa; thông báo lỗi từ API
  được thay khóa bằng `***` trước khi in.

**Khi có khóa:**

```powershell
$env:GEMINI_API_KEY = "<khóa>"      # không commit, không dán vào file
uv run python spikes/gemini_tools_spike.py
uv run python spikes/gemini_tools_spike.py --mode structured   # nếu lần trên KHÔNG ĐẠT
```

Ghi kết quả (tỉ lệ, các câu sai, độ trễ, kết luận giữ function calling hay chuyển structured output) vào mục này.
