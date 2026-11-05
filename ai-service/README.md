# ai-service — Dịch vụ AI

Tìm kiếm ngữ nghĩa Sản phẩm (FR-35, FR-36) và Hỏi đáp dữ liệu bằng tiếng Việt (FR-37 – FR-42).
FastAPI, Python 3.14, model nhúng `intfloat/multilingual-e5-small`, Gemini qua `google-genai`.

**Chủ:** Lân (C – Phân tích và AI). Luật bắt buộc: `ARCHITECTURE-SPINE.md`, nhất là AD-1, AD-2, AD-5, AD-9 – AD-13, AD-19.

Trạng thái: **GĐ0 (CB-08)**, mới có khung. `/ai/search` và `/ai/ask` trả `501` Problem Details
cho đến AI-01 và AI-03. Kết quả hai spike kỹ thuật ở [`SPIKES.md`](SPIKES.md).

## Nguyên tắc

- Không đụng bảng nghiệp vụ. Mọi con số lấy từ `GET /api/v1/reporting/q/Q1…Q9`, gọi bằng JWT của người dùng (AD-10).
- Dữ liệu riêng nằm ở schema `ai`; DDL do Flyway ở backend tạo, ở đây không chạy DDL (AD-4).
- Mọi truy cập DB nằm trong transaction mở bằng `db.store_transaction(store_id)`, câu đầu tiên là
  `select set_config('app.store_id', %s, true)` (AD-5). Role DB: `ai_service`.
- `app/embedding.py` là nơi duy nhất gọi model (AD-12).
- LLM không tự tính ngày: nó chọn `period.kind`, code quy ra `fromDate`/`toDate` theo giờ Việt Nam (AD-19).
- Lỗi luôn ở dạng RFC 9457 Problem Details, có `requestId` (AD-19).

## Cấu trúc

```text
ai-service/
  app/
    main.py            # create_app(), route dưới /ai, lifespan bật/tắt job đồng bộ
    settings.py        # pydantic-settings, đọc biến môi trường
    problems.py        # Problem Details cho RequestValidationError, HTTPException, Exception; X-Request-Id
    auth.py            # kiểm JWT HS256, current_user, require_owner
    db.py              # psycopg 3 + pgvector, store_transaction / global_transaction
    embedding.py       # model nhúng: embed_passage ("passage: "), embed_query ("query: "), nạp lười
    health.py          # GET /ai/health
    log.py             # log JSON ra stdout, kèm requestId
    search/router.py   # GET /ai/search (501 đến AI-01; TODO mô tả truy vấn AD-12)
    qa/
      tools.py         # 9 công cụ Q1–Q9: JSON schema + Pydantic + quy ra tham số reporting (HỢP ĐỒNG)
      periods.py       # quy cụm thời gian ra khoảng ngày (AD-19)
      prompts.py       # prompt lần gọi 1 (function calling và dự phòng structured output)
      schemas.py       # AskRequest / AskResponse (AD-11 mục 3)
      reporting.py     # client gọi /api/v1/reporting/q/<Qn>
      router.py        # POST /ai/ask (501 đến AI-03; đường ống AD-11 ghi trong comment)
    quota/repository.py  # SQL giữ chỗ / hoàn lượt Hạn mức AI (AD-13)
    sync/
      feed.py          # client change-feed /internal/catalog/product-changes
      loop.py          # vòng đồng bộ, tắt trừ khi SYNC_ENABLED=true
  spikes/              # embedding_spike.py, gemini_tools_spike.py
  tests/               # pytest, không cần mạng, model hay database
  Dockerfile           # python:3.14-slim + uv 0.13.7, model đóng gói sẵn ở /models
  SPIKES.md            # kết quả spike GĐ0
```

## Biến môi trường

| Biến | Mặc định | Ghi chú |
|---|---|---|
| `JWT_SECRET` | *(bắt buộc)* | Giống hệt backend; chuỗi UTF-8 ≥ 32 byte (AD-9). Thiếu hoặc ngắn thì dịch vụ không khởi động |
| `DATABASE_URL` | `postgresql://ai_service@localhost:5432/banhang` | Role `ai_service`. Mật khẩu (`DB_AI_PASSWORD`) đặt trong URL lúc chạy, không để trong code |
| `BACKEND_URL` | `http://localhost:8080` | Gốc để gọi `reporting` và change-feed |
| `INTERNAL_SERVICE_KEY` | — | Header `X-Service-Key` cho change-feed. Bắt buộc khi `SYNC_ENABLED=true` |
| `GEMINI_API_KEY` | — | SDK google-genai tự đọc; code không cầm giá trị này |
| `GEMINI_MODEL` | `gemini-3.5-flash-lite` | Đổi model chỉ bằng biến này (AD-11 mục 5) |
| `EMBEDDING_MODEL` | `intfloat/multilingual-e5-small` | Ghi vào `model_version`; đổi thì phải đánh chỉ mục lại |
| `SYNC_ENABLED` | `false` | GĐ0 để tắt |
| `SYNC_INTERVAL_SECONDS` | `60` | 5 – 3600 |
| `LOG_LEVEL` | `INFO` | |

Trong image còn đặt sẵn `HF_HOME=/models`, `HF_HUB_OFFLINE=1`, `TRANSFORMERS_OFFLINE=1`.

## Chạy trên máy

Cần `uv` (spine chốt 0.13.7; uv 0.11.3 trên máy Lân cũng chạy được với `uv.lock` hiện tại).

```powershell
cd ai-service
uv sync                                   # torch lấy từ index CPU download.pytorch.org/whl/cpu
$env:JWT_SECRET = "<cùng giá trị với backend>"
uv run uvicorn app.main:app --reload --port 8000
# http://localhost:8000/ai/health · tài liệu OpenAPI: http://localhost:8000/ai/docs
```

Test (không cần mạng, model hay database):

```powershell
uv run pytest
```

Spike:

```powershell
uv run python spikes/embedding_spike.py           # lần đầu tải model ~470 MB vào cache Hugging Face
uv run python spikes/gemini_tools_spike.py        # cần GEMINI_API_KEY; thiếu thì báo và thoát 0
uv run python spikes/gemini_tools_spike.py --mode structured
```

Trên Windows, Hugging Face có thể cảnh báo không tạo được symlink trong cache; vô hại
(đặt `HF_HUB_DISABLE_SYMLINKS_WARNING=1` để tắt).

## Chạy bằng Docker

```powershell
cd ai-service
docker build -t banhang/ai-service:gd0 .
docker run --rm -p 8000:8000 -e JWT_SECRET="<cùng giá trị với backend>" banhang/ai-service:gd0
curl http://localhost:8000/ai/health
```

- Image ~2,96 GB trên đĩa (758 MB nén); torch CPU chiếm phần lớn. Model nằm sẵn trong `/models`,
  chạy được không cần mạng (đã thử với `--network none`).
- Chạy bằng user không phải root (`app`, uid 10001). Có `HEALTHCHECK` gọi `/ai/health`.
- Trong compose (`deploy/`, do Hiếu giữ), container tên `ai-service`, cổng 8000, nginx chuyển nguyên `/ai/…`.

## Hợp đồng

### Route

| Route | Ai gọi được | GĐ0 |
|---|---|---|
| `GET /ai/health` | ai cũng được | `{status, version, modelLoaded, embeddingModel, syncEnabled}`; không ép nạp model |
| `GET /ai/search?q=&limit=` | OWNER, STAFF | `501`. Sau AI-01: `{items: [{productId, score}]}` |
| `POST /ai/ask` `{question}` | OWNER | `501`. Sau AI-03: `{answer, interpretation, table, chart, reportLink, disclaimer, quota, refused}` |

### JWT (AD-9)

Token đọc từ cookie `access_token`, không có thì từ `Authorization: Bearer`. HS256 với `JWT_SECRET`.
Claim bắt buộc `sub` (uuid), `store_id` (uuid), `role` (`OWNER` | `STAFF`), `iat`, `exp`, `jti`.
Sai chữ ký, hết hạn, thiếu claim, thuật toán khác HS256 → `401`. Sai vai trò → `403`.
`tests/test_auth.py` có token ký tay theo kiểu jjwt (header chỉ có `alg`) để đối chiếu với phía Java.

### Mã lỗi (`type`, cần đăng ký trong `docs/errors.md`)

| `type` | Mã | Khi nào |
|---|---|---|
| `/errors/common/validation` | 400 | Dữ liệu nhập sai, kèm `errors[]` dạng `{field, code, message}` |
| `/errors/common/unauthenticated` | 401 | Thiếu hoặc sai token |
| `/errors/common/forbidden` | 403 | Nhân viên gọi `/ai/ask` |
| `/errors/common/not-found` | 404 | Route không có |
| `/errors/common/method-not-allowed` | 405 | |
| `/errors/common/internal` | 500 | Lỗi không lường trước; chi tiết chỉ ở log, tra bằng `requestId` |
| `/errors/ai/not-implemented` | 501 | Khung GĐ0 |
| `/errors/ai/quota-exceeded` | 429 | Hết Hạn mức AI (AD-13) |
| `/errors/ai/unavailable` | 503 | Dịch vụ AI không sẵn sàng |

### Công cụ Q1–Q9 → `GET /api/v1/reporting/q/<Qn>` (C chốt cùng `reporting`)

| Qn | Tham số LLM điền | Query string gửi `reporting` |
|---|---|---|
| Q1, Q2, Q3 | `period` | `fromDate`, `toDate` |
| Q4 | `period` (tùy chọn, mặc định 30 ngày gần nhất), `direction` (`BEST_SELLING`/`SLOW_SELLING`), `rankBy` (`QUANTITY`/`REVENUE`), `limit` (1–50, mặc định 10) | `fromDate`, `toDate`, `direction`, `rankBy`, `limit` |
| Q5 | `productName` hoặc `categoryName` (tùy chọn) | `productName` / `categoryName` |
| Q6 | `metric` (`REVENUE`/`ORDER_COUNT`/`GROSS_PROFIT` = Q1/Q2/Q3), `period`, `comparePeriod` | `metric`, `fromDate`, `toDate`, `compareFromDate`, `compareToDate` |
| Q7, Q8, Q9 | — | — |

`period = {kind, days?, month?, year?, fromDate?, toDate?}` với `kind` ∈ `TODAY`, `YESTERDAY`,
`THIS_WEEK`, `LAST_WEEK`, `THIS_MONTH`, `LAST_MONTH`, `THIS_YEAR`, `LAST_YEAR`, `LAST_N_DAYS`, `MONTH`,
`DATE_RANGE`. Quy ước quy ngày: tuần từ Thứ Hai; kỳ "này" tính đến hôm nay; `LAST_N_DAYS` gồm cả hôm nay;
tối đa 366 ngày (xem `app/qa/periods.py`).

### Change-feed (AD-12)

`GET {BACKEND_URL}/internal/catalog/product-changes?after=<cursor>&limit=500`, header `X-Service-Key`.
Phản hồi `{items: [{storeId, productId, searchText, status: ACTIVE|INACTIVE, changedAt}], nextCursor}`;
con trỏ là chuỗi mờ, ai-service chỉ lưu và gửi lại.

### Bảng schema `ai` mà code đang giả định (để viết migration Flyway ở backend)

Bản chính thức: `backend/src/main/resources/db/migration/V202611060840__ai_init.sql` (Flyway của backend là nơi duy nhất chạy DDL, AD-4). Tóm tắt dưới đây; tên cột phải khớp SQL trong `app/quota/repository.py` và `app/sync/loop.py`.

```sql
create table ai.product_embedding (
    store_id          uuid        not null,
    product_id        uuid        not null,
    embedding         vector(384) not null,
    model_version     text        not null,
    source_changed_at timestamptz not null,
    primary key (store_id, product_id)
);
create table ai.ai_quota (
    store_id   uuid    not null,
    quota_date date    not null,          -- ngày theo giờ Việt Nam
    used       integer not null default 0,
    primary key (store_id, quota_date)
);
create table ai.ai_query_log (
    id          uuid        primary key,
    store_id    uuid        not null,
    user_id     uuid        not null,
    question    text        not null,
    query_id    text,                     -- Q1…Q9 hoặc null khi từ chối
    params      jsonb,
    answer      jsonb,
    refused     boolean     not null,
    duration_ms integer     not null,
    error       text,
    created_at  timestamptz not null default now()
);
create table ai.sync_cursor (                -- bảng toàn cục, không có store_id (AD-5 mục 1)
    name       text        primary key,     -- 'catalog.product-changes'
    cursor     text,
    updated_at timestamptz not null default now()
);
-- product_embedding, ai_quota, ai_query_log: ENABLE + FORCE ROW LEVEL SECURITY,
-- policy store_id = current_setting('app.store_id', true)::uuid cho USING và WITH CHECK.
-- Quyền: role ai_service được DML trên schema ai, không có quyền gì trên schema app.
```

## Việc tiếp theo

- **AI-01**: hiện thực `/ai/search` theo TODO trong `app/search/router.py`, bật `SYNC_ENABLED`, chạy với DB thật,
  thêm test chéo 2 Cửa hàng (AD-5 mục 9). Xem khuyến nghị về văn bản passage trong `SPIKES.md`.
- **AI-03 trở đi**: đường ống `/ai/ask` theo comment trong `app/qa/router.py`.
- Chạy `spikes/gemini_tools_spike.py` khi có `GEMINI_API_KEY` và ghi kết quả vào `SPIKES.md`.
