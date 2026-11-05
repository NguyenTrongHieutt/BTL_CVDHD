# Frontend — Hệ thống quản lý bán hàng tích hợp AI

SPA React cho quầy bán hàng và Chủ cửa hàng. Giao diện tiếng Việt, code tiếng Anh.
Kiến trúc bắt buộc: `../../_bmad-output/planning-artifacts/architecture/.../ARCHITECTURE-SPINE.md`, nhất là
AD-8 (frontend không tính tiền), AD-9 (phiên đăng nhập), AD-12 (tìm kiếm dự phòng), AD-19 (phân trang, lỗi),
AD-20 (cấu trúc frontend).

| Thành phần | Phiên bản |
|---|---|
| Node.js | ≥ 24 khi phát triển; image Docker dùng Node 26 |
| React / React DOM | 19.3.1 |
| TypeScript | 7.0.2 |
| Vite | 8.3.6 |
| React Router | 8.5.1 |
| TanStack Query | 5.106.1 |
| Ant Design | 6.7.0 (locale `vi_VN`) |
| Recharts | 3.10.1 |
| Vitest / jsdom | 5.1.0 / 31.0.0 |
| Playwright | 1.64.1 |
| ESLint / eslint-plugin-boundaries | 10.14.0 / 7.4.2 |
| oxlint | 1.91.0 |

Mọi phiên bản đều ghim chính xác trong `package.json` (không dùng `^`).

## Lệnh

```bash
npm ci              # cài đúng theo package-lock.json
npm run dev         # http://localhost:5173, proxy /api/ → :8080, /ai/ → :8000
npm run build       # tsc -b (kiểm kiểu) rồi vite build ra dist/
npm run typecheck   # chỉ kiểm kiểu bằng TypeScript 7
npm run lint        # oxlint (chất lượng code) rồi ESLint (ranh giới kiến trúc)
npm test            # Vitest, một lần
npm run test:watch  # Vitest, chạy lại khi sửa file
npm run e2e         # Playwright (chưa có test ở GĐ0)
```

Trước khi merge: `npm run lint && npm test` (và `npm run build`).

- Cổng 8080/8000 đang bận thì đổi đích proxy: `BACKEND_URL=http://localhost:18080 AI_SERVICE_URL=http://localhost:18000 npm run dev`.
  Cổng 5173 bận thì Vite tự lấy cổng kế tiếp.
- ai-service tắt thì Vite proxy trả `503 /errors/ai/unavailable` giống nginx; màn bán hàng tự về Tìm kiếm thường.
- Playwright: lần đầu chạy `npx playwright install chromium`. Mặc định chạy với `npm run dev`;
  chạy với bản Docker: `E2E_BASE_URL=http://localhost npm run e2e`. Test đặt trong `e2e/`.

## Cấu trúc thư mục

```text
src/
  main.tsx                  # QueryClientProvider + ConfigProvider(vi_VN) + App của antd + RouterProvider
  app/                      # khung ứng dụng (Anh, CB-05): router, AppLayout, RequireAuth/RequireRole,
                            # menu theo vai trò, trang chính theo vai trò, xử lý 401 chung, CSS in hóa đơn 80mm
  features/
    platform/   (Hiếu)      # /login, /platform/…: đăng nhập, đăng ký, Cửa hàng, Nhân viên, Nhật ký thao tác
    catalog/    (Hiếu)      # /catalog/…: Sản phẩm, Danh mục, Khách hàng, Nhà cung cấp, Lịch sử xuất nhập kho
    sales/      (Anh)       # /sales/…: bán hàng, Đơn hàng, trả hàng; cart/cartStorage.ts (giỏ ở localStorage)
    inventory/  (Anh)       # /inventory/…: Tồn kho, Phiếu nhập, kiểm kho
    reporting/  (Lân)       # /reporting/…: trang tổng quan, báo cáo
    assistant/  (Lân)       # /assistant: Trợ lý AI (KHÔNG dùng /ai/…, tiền tố đó thuộc ai-service)
  shared/       (Anh)
    api/client.ts           # client API duy nhất
    api/session.ts          # GET /me, đăng nhập, đăng xuất, useCurrentUser()
    api/pagination.ts       # PageResponse<T>, PageRequest (page từ 0)
    api/aiSearch.ts (Lân)   # searchProducts(): /ai/search, chờ 1,5 s rồi về Tìm kiếm thường
    components/             # MoneyText, DataTable, SearchInput, useConfirm, DateRangePicker,
                            # ProductPicker, CustomerPicker (khung), PagePlaceholder, ErrorAlert
    types/<module>.ts       # kiểu dùng chung; chủ module viết file của mình
    messages.vi.ts          # mã lỗi `type` → câu tiếng Việt + hướng xử lý
    routing.ts              # hợp đồng FeatureDefinition giữa feature và app
  test/setup.ts             # chuẩn bị jsdom cho Vitest
e2e/                        # test Playwright (GĐ4, Kịch bản demo)
```

## Ai giữ phần nào

| Thư mục | Người giữ |
|---|---|
| `src/app`, `src/main.tsx`, `src/shared` (trừ `shared/api/aiSearch.ts`) | Anh |
| `features/sales`, `features/inventory`, `shared/types/sales.ts`, `shared/types/inventory.ts` | Anh |
| `features/platform`, `features/catalog`, `shared/types/platform.ts`, `shared/types/catalog.ts` | Hiếu |
| `features/reporting`, `features/assistant`, `shared/api/aiSearch.ts`, `shared/types/reporting.ts`, `shared/types/assistant.ts` | Lân |

Mỗi màn hình hiện đang là `PagePlaceholder` ghi giai đoạn và người làm ("Sẽ làm ở GĐ1 — Anh").

## Thêm một màn hình

1. Tạo `features/<module>/pages/XxxPage.tsx`.
2. Khai báo route trong `features/<module>/index.ts`:

   ```ts
   { path: '/sales/orders', title: 'Đơn hàng', roles: ALL_ROLES, Component: OrdersPage,
     menu: { group: 'sales', order: 20 } }
   ```

   - `path` có tiền tố `/<module>/` (Trợ lý AI là `/assistant`). Test `src/app/features.test.ts` kiểm điều này.
   - `roles` theo bảng quyền PRD 4.7 (`ALL_ROLES` hoặc `OWNER_ONLY`). Chỉ để ẩn menu và route; backend vẫn kiểm quyền (AD-16).
   - `menu.group` là một trong: `sales`, `products`, `customers`, `inventory`, `reports`, `assistant`, `settings`.
3. Không sửa `src/app` để thêm màn hình; app tự ghép route và menu từ `FeatureDefinition`.

## Quy tắc bắt buộc

**Ranh giới (AD-20) — `npm run lint` chặn:**
- `features/<m>` không import `features/<khác>`, kể cả `import type` và `import()`. Kiểu dùng chung đặt ở
  `shared/types/<module>.ts`, gọi API qua `shared/api`.
- `shared/` không import `features/` hay `src/app`.
- `src/app` chỉ import feature qua `features/<m>/index.ts`.
- Chỉ `shared/api/client.ts` được gọi `fetch`.
- File mới trong `src/` phải nằm trong `app/`, `features/<m>/`, `shared/` hoặc `test/`.

**Gọi API:**
- Dùng `api.get/post/put/patch/delete` hoặc `apiRequest` trong `shared/api/client.ts`. Client tự gửi cookie,
  `X-Request-Id` mới cho mỗi request, đọc Problem Details thành `ApiError` (`type, title, status, detail, errors[], requestId`).
- Gặp 401 ở bất kỳ request nào: app xóa cache TanStack Query và chuyển về `/login` (nhớ trang đang mở).
- POST tạo hoặc chuyển trạng thái chứng từ phải có `idempotencyKey` (`newIdempotencyKey()`, AD-7). Sinh khóa mới sau
  mỗi lần thành công và khi xóa giỏ; chỉ giữ khóa cũ khi `shouldReuseIdempotencyKey(error)` (lỗi mạng hoặc 5xx).
- Báo lỗi cho người dùng bằng `describeError(error)` / `<ErrorAlert>`; thêm mã lỗi mới của module mình vào `messages.vi.ts`.

**TanStack Query:** key dạng `[<module>, <entity>, params]`, ví dụ `['catalog', 'customers', { q }]`. Phiên đăng nhập ở
`['platform', 'me']`. Mỗi mutation tự liệt kê key cần invalidate (bán hàng xong invalidate `['inventory', 'stock']`).

**Tiền (AD-8):** tiền là số nguyên đồng. Frontend KHÔNG cộng, trừ, nhân, làm tròn tiền; màn bán hàng gọi
`POST /api/v1/sales/orders/quote` (debounce 300 ms) và chỉ hiện số backend trả về bằng `<MoneyText>` ("1.234.000 ₫").

**Phân trang (AD-19):** API trả `{items, total, page, size}`, `page` từ 0, `size` tối đa 100. `<DataTable>` tự đổi sang
trang từ 1 của antd. Khoảng ngày dùng `<DateRangePicker>`: `fromDate`/`toDate` dạng `yyyy-MM-dd`, gồm cả hai đầu, tối đa 366 ngày.

**Giao diện:** Ant Design 6 với `vi_VN`. Màn bán hàng thiết kế từ 1024px; màn của Chủ cửa hàng dùng được từ 360px.
Hộp thoại dùng `useConfirm()` / `App.useApp()` (không dùng `Modal.confirm` tĩnh). In hóa đơn: đặt nội dung trong
`.print-receipt`, khung ứng dụng tự ẩn khi in (`@media print`, khổ 80mm). Máy quét mã vạch gõ như bàn phím + Enter,
`<SearchInput>` tìm ngay khi gặp Enter.

## Công cụ lint và TypeScript 7

- **Kiểm kiểu:** `tsc -b` của TypeScript 7.0.2 (bản viết lại bằng Go).
- **oxlint** (`.oxlintrc.json`): luật chất lượng code cho TS/React (rules-of-hooks, exhaustive-deps, correctness…).
  oxlint tự phân tích TypeScript nên không phụ thuộc gói `typescript`.
- **ESLint 10** (`eslint.config.js`): chỉ dùng cho `eslint-plugin-boundaries` và luật cấm `fetch`.
  TypeScript 7 không còn JS compiler API mà `typescript-eslint` cần, và `typescript-eslint` 8.x chỉ nhận
  `typescript <6.1`, nên ESLint dùng `@babel/eslint-parser` 8.0.7 + `@babel/core` 8.1.0 với plugin cú pháp
  `typescript`/`jsx` của Babel. Babel chỉ đọc cú pháp (không biên dịch, không cần file cấu hình Babel), đủ để
  boundaries thấy mọi `import`, `export … from`, `import()`.
- Vì parser này không có thông tin kiểu, đừng bật luật ESLint cần kiểu hay luật lõi như `no-unused-vars`
  (`tsc` với `noUnusedLocals` đã lo).
