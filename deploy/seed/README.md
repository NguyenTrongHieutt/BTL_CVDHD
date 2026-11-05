# Dữ liệu mẫu (`deploy/seed/`)

Thư mục này chứa dữ liệu đầu vào cho 2 Cửa hàng mẫu (PRD mục 6.1, AD-18). Chương trình nạp `devtools.seed` (Java, profile `seed`) đọc các file ở đây rồi gọi API công khai của từng module. Chương trình nạp **không INSERT thẳng vào bảng**.

README này là **hợp đồng** giữa file dữ liệu và chương trình nạp. Nếu đổi định dạng thì phải sửa cả ba nơi: file này, `schema/store-seed.schema.json` và `eval/seed_gen/generate.py`.

| File | Nội dung |
|---|---|
| `store-a.json` | Cửa hàng A: **Tạp hóa Lan** (tạp hóa, tiện lợi), có Lô hàng và hạn sử dụng |
| `store-b.json` | Cửa hàng B: **Thời trang Mai** (thời trang), có Biến thể màu và size |
| `schema/store-seed.schema.json` | JSON Schema draft 2020-12 cho cả hai file |

> **Không sửa tay các file JSON.** Hai file này do `eval/seed_gen/generate.py` sinh ra và có tính tất định: cùng mã nguồn luôn ra cùng từng byte. Muốn đổi dữ liệu thì sửa generator, chạy lại, rồi chạy lại `eval/answers/compute_answers.py` để cập nhật đáp án (xem mục 8).

## 1. Hai Cửa hàng mẫu

| | Tạp hóa Lan (`store-a`) | Thời trang Mai (`store-b`) |
|---|---|---|
| Loại (`store.kind`) | `GROCERY` | `FASHION` |
| Địa chỉ | TP. Hồ Chí Minh | Hà Nội (mùa đông bán chạy áo ấm) |
| Sản phẩm / Biến thể | 150 / 154 (3 Sản phẩm có Biến thể theo vị hoặc loại) | 150 / 652 (màu × size) |
| Lô hàng, hạn sử dụng | 31 Biến thể theo Lô hàng (sữa, trứng, bánh mì, đồ đông lạnh…) | Không có |
| Danh mục / Nhà cung cấp / Khách hàng | 10 / 5 / 50 | 10 / 5 / 50 |
| Đơn hàng (60 ngày) | 500 | 500 |
| Phiếu nhập / Phiếu kiểm kho | 65 / 2 | 35 / 2 |
| Phiếu trả hàng / Đơn bị hủy | 16 / 5 | 16 / 9 |
| Ngừng kinh doanh | 2 Sản phẩm | 1 Sản phẩm + 1 Biến thể |
| Tích điểm | 1 điểm / 10.000đ, 1 điểm = 200đ | 1 điểm / 10.000đ, 1 điểm = 300đ |
| Cảnh báo hết hạn | 15 ngày | 30 ngày |

Những điểm dữ liệu được dựng sẵn để có cái mà hỏi:
- **Bán chạy:** độ phổ biến lệch mạnh giữa các Sản phẩm. Ở Cửa hàng B, hàng mùa đông bán chạy dần lên, hàng mùa hè chậm dần.
- **Bán chậm, tồn nhiều:** có các món tồn đầu kỳ lớn mà gần như không bán. Có hàng xả lỗ với giá bán thấp hơn Giá vốn (`Bánh trung thu thập cẩm`, `Đồ bơi nữ một mảnh`), để Q9 phải loại ra.
- **Dưới Ngưỡng tồn tối thiểu / sắp hết:** vài món bán chạy bị Nhà cung cấp ngừng giao 21 ngày cuối (ví dụ `Mì Hảo Hảo`, `Trứng gà`, `Áo khoác phao nhẹ nữ`, `Mũ len`).
- **Sắp hết hạn:** có các Lô hàng hết hạn trong 15 ngày sau ngày neo.
- **Xu hướng doanh thu:** doanh thu tăng dần theo tuần. Cửa hàng A có một tuần vắng khách (02–08/11), cuối tuần đông hơn ngày thường.
- **Ngày đột biến:**
  - Cửa hàng A: 20/10, 20/11, 05/12.
  - Cửa hàng B: 20/10, 11/11, 20/11, Black Friday 27–28/11, 12/12. Vào 11/11, 27–28/11 và 12/12, Cửa hàng B giảm toàn đơn lần lượt 11%, 20% và 12%.
- **Thử tách dữ liệu (SM-5):** 3 số điện thoại Khách hàng có ở cả 2 Cửa hàng. Sản phẩm `Tất cổ ngắn cotton` dùng chung mã vạch `8935000123454` ở cả 2 Cửa hàng nhưng khác giá.

## 2. Tài khoản demo

Mọi tài khoản dùng chung mật khẩu **`Demo@2026`**, lấy từ trường `defaultPassword` của từng file. Đây là mật khẩu giả, chỉ dùng cho bản demo trên máy cục bộ, không dùng lại ở nơi khác.

| Cửa hàng | Vai trò | Tên đăng nhập (`login`) | Tên hiển thị | Ca |
|---|---|---|---|---|
| Tạp hóa Lan | Chủ cửa hàng | `0901000001` (số điện thoại) | Nguyễn Thị Lan | trưa 12–17h |
| Tạp hóa Lan | Nhân viên | `hoa.lan` | Lê Thị Hoa | sáng, nghỉ Chủ nhật |
| Tạp hóa Lan | Nhân viên | `minh.lan` | Trần Văn Minh | tối, nghỉ Thứ Hai |
| Thời trang Mai | Chủ cửa hàng | `0902000001` (số điện thoại) | Phạm Thị Mai | trưa 12–17h |
| Thời trang Mai | Nhân viên | `linh.mai` | Đỗ Thùy Linh | sáng, nghỉ Chủ nhật |
| Thời trang Mai | Nhân viên | `tuan.mai` | Ngô Anh Tuấn | tối, nghỉ Thứ Hai |

Chủ cửa hàng đăng ký và đăng nhập bằng số điện thoại (FR-43, FR-44). Tên đăng nhập là duy nhất trên toàn hệ thống (FR-45, AD-9). Tài khoản ngân hàng VietQR trong file là **số giả**: trước khi quét thử mã bằng ứng dụng ngân hàng thật (AD-22), hãy thay bằng tài khoản của nhóm, và không chuyển tiền.

## 3. Mốc thời gian

Mọi thời điểm trong file đều **tương đối**, để dữ liệu luôn trông như mới.

- **`SEED_ANCHOR_DATE`** (biến môi trường, dạng `YYYY-MM-DD`) là ngày neo, tức "hôm nay" của dữ liệu. Nếu không đặt, chương trình nạp dùng **`2026-12-16`**, là ngày neo chuẩn. Đáp án `eval/answers/expected.json` và test golden Q1–Q9 (AD-10) đều tính theo ngày neo chuẩn này.
- Mỗi sự kiện có `dayOffset` (số nguyên ≤ 0) và `time` (`HH:mm`). Thời điểm thực là **ngày `SEED_ANCHOR_DATE + dayOffset`, lúc `time:00` theo giờ `Asia/Ho_Chi_Minh`** (UTC+7, không có giờ mùa hè), rồi đổi sang UTC để lưu `timestamptz` (AD-8).
  - Ví dụ: với ngày neo `2026-12-16`, sự kiện `dayOffset: -3, time: "19:05"` diễn ra lúc `2026-12-13T19:05:00+07:00`, tức `2026-12-13T12:05:00Z`.
- Trước mỗi lời gọi API, chương trình nạp đặt `Clock` cố định đúng thời điểm của sự kiện. Việc này đáp ứng yêu cầu "Clock neo vào `SEED_ANCHOR_DATE`" của AD-18.
- **Khởi tạo** diễn ra tại `store.setupDayOffset` / `store.setupTime` (`-61`, `08:00`). Mọi việc ở mục 4, bước 1–5 dùng thời điểm này.
- Phiếu nhập bắt đầu từ ngày `-60`. Đơn hàng trải từ ngày `-59` đến ngày `0`, tức đúng 60 ngày.
- **Ngày neo (`dayOffset: 0`) chỉ có sự kiện trước 11:00.** Nếu nạp đúng vào ngày demo và trước 11:00, vài Đơn hàng sẽ nằm "trong tương lai" vài giờ. Các báo cáo cắt theo ngày nên vẫn đúng; muốn gọn thì chạy `reset-demo` sau 11:00. Dữ liệu không có Phiếu trả hàng nào vào ngày neo.
- `expiryDayOffset` của Lô hàng cũng tương đối: hạn sử dụng = `SEED_ANCHOR_DATE + expiryDayOffset`. Giá trị này có thể dương.
- Lịch trong dữ liệu (thứ trong tuần, ngày lễ, kiểm kho cuối tháng ở `-46` = 31/10 và `-16` = 30/11) khớp với ngày neo chuẩn. Đổi ngày neo thì các con số vẫn hợp lệ theo quy tắc nghiệp vụ, chỉ lịch thứ và ngày lễ bị lệch. Khi đó phải tính lại đáp án bằng `compute_answers.py --anchor <ngày>`.
- Trong một Cửa hàng, **không có hai sự kiện trùng `(dayOffset, time)`**. Generator bảo đảm điều này, `compute_answers.py --check` kiểm tra lại.

## 4. Thứ tự nạp

Chương trình nạp đọc mọi file `store-*.json` trong `SEED_DIR` (compose gắn thư mục này vào `/seed`) theo thứ tự tên file, và bỏ qua `schema/` cùng `README.md`.

Nạp từng Cửa hàng riêng. Mọi bước chạy trong ngữ cảnh của Cửa hàng đó (`TenantScope.runAs`), với danh tính của người dùng ghi trong sự kiện.

1. **Đăng ký Cửa hàng** (FR-43) bằng `store.name`, `login` của user có `role: OWNER` và `defaultPassword`. Bước này tạo luôn `order_counter`.
2. **Cài đặt Cửa hàng** (FR-48, `platform.StoreSettings`): `address`, `phone`, `invoiceFooter`, `bankAccount`, `loyalty`, `expiryWarningDays`.
3. **Tài khoản Nhân viên** (FR-45): các user có `role: STAFF`, mật khẩu `defaultPassword`.
4. **Danh mục**, rồi **Nhà cung cấp**, rồi **Khách hàng**.
5. **Sản phẩm và Biến thể** (FR-1, FR-7), lần lượt theo thứ tự trong file:
   - Mỗi Biến thể gửi `openingQuantity` và `openingUnitCost`. `catalog` phát `VariantCreated`, `inventory` ghi chứng từ `OPENING` và đặt `avg_unit_cost = openingUnitCost` (AD-14, AD-15).
   - Biến thể có `openingLot` thì tồn đầu kỳ vào đúng Lô hàng đó.
   - Sau đó đặt `minQuantity` (Ngưỡng tồn tối thiểu, FR-26) qua API của `inventory`.
   - Mọi Sản phẩm và Biến thể được tạo ở trạng thái đang kinh doanh. Việc ngừng kinh doanh diễn ra ở bước 6.
6. **Dòng thời gian.** Gộp 6 mảng `purchaseReceipts`, `orders`, `cancellations`, `returns`, `deactivations`, `stocktakes`, rồi sắp theo `(dayOffset, time, loại, khóa)`.
   - Thứ tự loại: `RECEIPT` < `ORDER` < `CANCELLATION` < `RETURN` < `DEACTIVATION` < `STOCKTAKE`.
   - Khóa: `ref`, hoặc `orderRef` với hủy đơn, `sku`/`productCode` với ngừng kinh doanh.
   - Vì không có hai sự kiện trùng thời điểm, thứ tự loại chỉ là quy tắc dự phòng.

   | Mảng | Gọi API | Người thực hiện |
   |---|---|---|
   | `purchaseReceipts` | Phiếu nhập (FR-24) | `createdBy` (Chủ cửa hàng) |
   | `orders` | `quote` rồi tạo Đơn hàng (FR-9 – FR-14, FR-21), gửi `quotedTotal` | `sellerLogin` |
   | `cancellations` | Hủy Đơn hàng (FR-15) | `byLogin` |
   | `returns` | Phiếu trả hàng (FR-16) | `byLogin` |
   | `deactivations` | Ngừng kinh doanh Sản phẩm hoặc Biến thể (FR-3, AD-14) | `byLogin` (Chủ cửa hàng) |
   | `stocktakes` | Phiếu kiểm kho (FR-25) | `createdBy` (Chủ cửa hàng) |

7. **Kiểm tra sau khi nạp.** Sai một điều là dừng và báo lỗi.
   - Theo AD-18:
     - `stock.quantity = Σ stock_ledger`
     - `loyalty_account.balance = Σ loyalty_ledger`
     - `order_counter` ≥ mã Đơn hàng lớn nhất
   - Theo dữ liệu mẫu:
     - Mã Đơn hàng nhận được bằng `expectedOrderCode`.
     - Tồn kho ngay trước kiểm kho bằng `expectedSystemQuantity`.
     - Tiền hoàn bằng `expectedRefundTotal`.
     - Trạng thái cuối của Sản phẩm và Biến thể bằng `status`.
     - Với Biến thể có `trackLots`: `stock.quantity = Σ lot.remaining_quantity`.

**`Idempotency-Key`** (AD-7) phải tất định, để chạy lại trên database chưa reset chỉ phát lại chứ không tạo trùng chứng từ:
```java
UUID.nameUUIDFromBytes(("seed:" + storeCode + ":" + docType + ":" + key).getBytes(StandardCharsets.UTF_8))
```
- `docType` ∈ `REGISTER`, `RECEIPT`, `ORDER`, `CANCEL`, `RETURN`, `STOCKTAKE`.
- `key` là `ref`; với hủy đơn là `orderRef`; với đăng ký là `store.code`.

Mã chuyển khoản VietQR (`TT` + 8 ký tự đầu của khóa, AD-22) nhờ đó cũng tất định.

## 5. Quy tắc tiền mà dữ liệu đã dùng

`quotedTotal` và `expectedRefundTotal` trong file được tính theo đúng các quy tắc dưới đây, khớp AD-8 và AD-15. Nếu `sales` tính khác, backend sẽ trả `409 /errors/sales/cart-stale` hoặc lệch tiền hoàn, và chương trình nạp phải dừng. Mọi phép tính dùng số nguyên đồng.

1. Tiền hàng của dòng: `gross = salePrice × quantity`.
2. Giảm giá dòng (`lines[].discount`):
   - `PERCENT`: `HALF_UP(gross × value / 100)`.
   - `AMOUNT`: `value` đồng, trừ trên **cả dòng** (không phải trên mỗi đơn vị), tối đa bằng `gross`.
3. `subtotal = Σ (gross − giảm giá dòng)`.
4. Giảm giá toàn đơn (`orderDiscount`):
   - `PERCENT`: `HALF_UP(subtotal × value / 100)`.
   - `AMOUNT`: `value` đồng.
5. Dùng điểm: `pointsDiscount = pointsRedeemed × loyalty.pointValue`. Đây là một khoản **giảm giá**, nên làm giảm doanh thu.
6. Tổng giảm toàn đơn `D = giảm giá toàn đơn + pointsDiscount` được chia cho các dòng theo tỷ lệ `(gross − giảm giá dòng)`:
   - Mỗi dòng trước dòng cuối nhận `floor(D × a_i / Σa)`.
   - **Dòng cuối** (theo thứ tự trong `lines`) nhận phần còn lại.
   - `net_line_amount = a_i − phần được chia`.
7. `total = subtotal − D = Σ net_line_amount = quotedTotal`. Tiền thừa = `cashReceived − total`.
8. Điểm cộng khi hoàn thành đơn có Khách hàng: `floor(total / earnAmountPerPoint)`.
9. **Tiền hoàn** cho `r` đơn vị của một dòng (`qty`, `net`):
   - Nếu lần trả này làm dòng được trả hết (`đã trả + r = qty`): hoàn `net − số đã hoàn trước đó`.
   - Ngược lại: hoàn `floor(net × r / qty)`.
10. **Điểm khi trả hàng:** trừ `floor((T − R_trước) / u) − floor((T − R_sau) / u)`. Trong đó `T` là `total` của đơn, `R` là tổng tiền đã hoàn của đơn, `u = earnAmountPerPoint`. Trả hết đơn thì trừ đúng số điểm đã cộng.
11. **Hủy đơn:** cộng lại Tồn kho mọi dòng, trừ điểm đã cộng, hoàn lại điểm đã dùng.
12. **Giá vốn** (AD-15):
    - Nhập hàng: `avg = HALF_UP((Q×C + q×c)/(Q+q))`; nếu `Q = 0` thì `avg = c`.
    - `OPENING` đặt `avg = openingUnitCost`.
    - Kiểm kho, trả hàng và hủy đơn không đổi `avg`.
    - Dòng Đơn hàng lưu `unit_cost = avg` lúc bán. Lợi nhuận của hàng trả được đảo lại theo `unit_cost` của dòng gốc.
13. Lô hàng xuất theo **FEFO** (hạn sớm nhất trước, cùng hạn thì lô nhập trước). Kiểm kho giảm Tồn kho của Biến thể theo Lô hàng cũng trừ theo FEFO.

Dữ liệu được dựng để **tránh các chỗ mà kiến trúc chưa quy định rõ**, nhờ đó đáp án không phụ thuộc vào cách hiểu:
- Không trả hàng và không hủy Đơn hàng có dùng điểm.
- Mọi lần hủy đều diễn ra trong ngày tạo đơn, và không có Phiếu nhập nào cho các Biến thể của đơn đó xen giữa lúc bán và lúc hủy. Vì vậy cộng lại Tồn kho khi hủy, dù có tính lại Giá vốn hay không, vẫn cho cùng kết quả.
- Không trả hàng hoặc hủy dòng của Biến thể theo Lô hàng. Kiểm kho chỉ *giảm* Tồn kho của các Biến thể này.
- Biến thể ngừng kinh doanh được kiểm kho về 0 ngay sau khi ngừng (AD-14 cho phép kiểm kho sau khi ngừng), và không có Phiếu trả hàng nào cho chúng.
- Không có Đơn hàng nào bán vượt Tồn kho tại thời điểm bán. Không bán từ Lô hàng đã hết hạn. Số lượng trả không vượt số đã bán trừ số đã trả. Số dư điểm không bao giờ âm.

## 6. Mô tả từng trường

Tiền đều là số nguyên đồng (VND). Mọi khóa tham chiếu đều là khóa nghiệp vụ: mã, SKU, số điện thoại, tên đăng nhập. File không chứa `id` của database.

### Cấp file

| Trường | Kiểu | Ý nghĩa |
|---|---|---|
| `$schema` | string | Đường dẫn tới schema, để editor kiểm tra file. Chương trình nạp bỏ qua |
| `formatVersion` | 1 | Phiên bản định dạng |
| `generator` | object | `script`, `randomSeed`, `canonicalAnchorDate`: nguồn gốc file. Chương trình nạp bỏ qua |
| `defaultPassword` | string | Mật khẩu demo của mọi tài khoản trong file |
| `store` … `deactivations` | | Xem các bảng dưới |

### `store`

| Trường | Ý nghĩa |
|---|---|
| `code` | Khóa của Cửa hàng trong dữ liệu mẫu (`store-a`, `store-b`). Dùng trong `Idempotency-Key`, không phải `store.id` |
| `name`, `address`, `phone` | Tên, địa chỉ, số điện thoại in trên hóa đơn (FR-48) |
| `kind` | `GROCERY` hoặc `FASHION`. Chỉ để mô tả, hệ thống không có trường này |
| `invoiceFooter` | Dòng cuối hóa đơn |
| `bankAccount.bankBin` / `accountNumber` / `accountName` | Tài khoản nhận VietQR: BIN 6 chữ số, số tài khoản, tên chủ tài khoản viết hoa không dấu. **Số giả** |
| `loyalty.earnAmountPerPoint` | Số đồng của Đơn hàng để được 1 điểm |
| `loyalty.pointValue` | Số đồng giảm cho mỗi điểm khi dùng điểm |
| `expiryWarningDays` | N ngày cảnh báo Lô hàng sắp hết hạn (FR-8) |
| `setupDayOffset`, `setupTime` | Thời điểm đăng ký và nạp dữ liệu khởi tạo |

### `users[]`

| Trường | Ý nghĩa |
|---|---|
| `login` | Chủ cửa hàng: số điện thoại. Nhân viên: tên đăng nhập. Unique toàn hệ thống |
| `displayName` | Tên hiển thị |
| `role` | `OWNER` (đúng 1 người) hoặc `STAFF` |

### `categories[]`, `suppliers[]`, `customers[]`

| Trường | Ý nghĩa |
|---|---|
| `categories[].code`, `.name` | Mã (khóa trong file) và tên Danh mục |
| `suppliers[].code`, `.name`, `.phone`, `.address` | Nhà cung cấp (FR-23). `code` được Phiếu nhập tham chiếu |
| `customers[].phone`, `.name`, `.note?` | Khách hàng (FR-18). `phone` unique trong Cửa hàng và là khóa tham chiếu. `note` có thể vắng |

### `products[]` và `products[].variants[]`

| Trường | Ý nghĩa |
|---|---|
| `code` | Mã Sản phẩm (`TH0001…`, `TT0001…`), cũng là khóa của `deactivations` |
| `name`, `unit`, `description` | Tên, đơn vị tính, mô tả ngắn. Mô tả dùng cho Tìm kiếm ngữ nghĩa (SM-4) |
| `categoryCode` | Danh mục |
| `status` | Trạng thái **sau khi nạp xong**: `ACTIVE`, hoặc `INACTIVE` nếu có `deactivations` loại `PRODUCT` |
| `variants[].sku` | Mã Biến thể, unique trong Cửa hàng. Sản phẩm không có biến thể thì SKU trùng mã Sản phẩm |
| `variants[].barcode` | Mã vạch EAN-13, unique trong Cửa hàng |
| `variants[].isDefault` | `true` khi là Biến thể mặc định duy nhất (AD-6). Khi đó `attributes = {}` |
| `variants[].attributes` | Thuộc tính, ví dụ `{"Màu": "Đen", "Size": "M"}` hoặc `{"Hương vị": "Dâu đỏ"}` |
| `variants[].salePrice` | Giá bán |
| `variants[].openingQuantity`, `openingUnitCost` | Tồn đầu kỳ và Giá vốn ban đầu (`VariantCreated`) |
| `variants[].minQuantity` | Ngưỡng tồn tối thiểu |
| `variants[].trackLots` | Biến thể quản lý theo Lô hàng. Nếu `true`, mọi dòng Phiếu nhập của nó đều có `lotCode` và `expiryDayOffset` |
| `variants[].openingLot?` | `{lotCode, expiryDayOffset}` của tồn đầu kỳ. Có khi và chỉ khi `trackLots` và `openingQuantity > 0` |
| `variants[].status` | Trạng thái hiệu lực sau khi nạp: `INACTIVE` nếu Sản phẩm hoặc chính Biến thể bị ngừng |

### `purchaseReceipts[]`: Phiếu nhập

| Trường | Ý nghĩa |
|---|---|
| `ref` | Khóa trong file (`A-REC-001`) |
| `supplierCode`, `dayOffset`, `time`, `createdBy`, `note?` | Nhà cung cấp, thời điểm, người lập |
| `lines[].sku`, `.quantity`, `.unitCost` | Biến thể, số lượng, giá nhập |
| `lines[].lotCode?`, `.expiryDayOffset?` | Lô hàng và hạn sử dụng. Bắt buộc khi Biến thể có `trackLots` |

### `stocktakes[]`: Phiếu kiểm kho

| Trường | Ý nghĩa |
|---|---|
| `ref`, `dayOffset`, `time`, `createdBy`, `note?` | |
| `lines[].sku`, `.countedQuantity` | Số đếm thực tế. Hệ thống đặt Tồn kho bằng số này |
| `lines[].expectedSystemQuantity` | Tồn kho hệ thống ngay trước khi kiểm. Chỉ để chương trình nạp đối chiếu, không gửi lên API |
| `lines[].reason?` | Lý do chênh lệch. Dòng không chênh lệch thì không có |

### `orders[]`: Đơn hàng

| Trường | Ý nghĩa |
|---|---|
| `ref` | Khóa trong file (`A-ORD-0001`), được hủy đơn và trả hàng tham chiếu |
| `expectedOrderCode` | Mã hệ thống phải cấp (`DH-000001`…) khi nạp theo đúng thứ tự thời gian |
| `dayOffset`, `time` | Thời điểm người bán bấm xác nhận |
| `sellerLogin` | Người bán (Chủ cửa hàng hoặc Nhân viên) |
| `customerPhone?` | Khách hàng gắn vào đơn (FR-14). Không có nghĩa là khách lẻ |
| `lines[].sku`, `.quantity` | Một SKU chỉ xuất hiện một lần trong đơn. Thứ tự dòng là `lineNo` 1, 2, … |
| `lines[].discount?` | `{type: PERCENT, value: 1..100}` hoặc `{type: AMOUNT, value: đồng}` |
| `orderDiscount?` | Giảm giá toàn đơn, cùng dạng trên |
| `pointsRedeemed?` | Số điểm dùng để giảm giá. Chỉ có khi có `customerPhone`, và không vượt số dư lúc bán |
| `payment` | `{method: "CASH", cashReceived}` hoặc `{method: "BANK_TRANSFER"}` (VietQR, người bán tự xác nhận) |
| `quotedTotal` | Tổng tiền khách trả, tính theo mục 5. Gửi kèm khi tạo đơn (AD-8) |

### `cancellations[]`: Hủy Đơn hàng

| Trường | Ý nghĩa |
|---|---|
| `orderRef` | Đơn bị hủy. Đơn này chưa có Phiếu trả hàng nào (AD-17) |
| `dayOffset`, `time` | Luôn cùng ngày với đơn và sau giờ tạo đơn |
| `byLogin` | Nhân viên chỉ hủy đơn của chính mình trong ngày. Chủ cửa hàng hủy được mọi đơn (FR-15) |
| `reason` | Lý do, ghi vào Nhật ký thao tác |

### `returns[]`: Phiếu trả hàng

| Trường | Ý nghĩa |
|---|---|
| `ref`, `orderRef`, `dayOffset`, `time`, `byLogin` | Phiếu, đơn gốc, thời điểm (sau thời điểm bán), người lập |
| `refundMethod` | `CASH` hoặc `BANK_TRANSFER` |
| `reason?` | Lý do trả |
| `lines[].lineNo` | Số thứ tự dòng của đơn gốc, bắt đầu từ 1 |
| `lines[].sku` | SKU của dòng đó, để đối chiếu |
| `lines[].quantity` | Số lượng trả. Không vượt số đã bán trừ số đã trả |
| `expectedRefundTotal` | Tổng tiền hoàn tính theo mục 5, để đối chiếu |

Một Đơn hàng có thể có nhiều Phiếu trả. Khi mọi dòng đã được trả hết, đơn chuyển sang *Đã trả hàng* (FR-15, FR-16).

### `deactivations[]`: Ngừng kinh doanh

| Trường | Ý nghĩa |
|---|---|
| `dayOffset`, `time`, `byLogin` | Thời điểm và Chủ cửa hàng thực hiện |
| `target` | `PRODUCT`: ngừng cả Sản phẩm, phát `VariantDeactivated` cho mọi Biến thể. `VARIANT`: chỉ ngừng một Biến thể |
| `productCode`, `sku?` | Sản phẩm. `sku` bắt buộc khi `target = VARIANT` |

Sau khi ngừng, dữ liệu không còn bán hay nhập Biến thể đó. Phiếu kiểm kho ngay sau đó đưa Tồn kho của nó về 0.

## 7. Kiểm tra file

```bash
py -3.14 eval/answers/compute_answers.py --check
```

Lệnh này kiểm tra schema, phát lại toàn bộ dòng thời gian và kiểm mọi quy tắc ở mục 5. Nó cũng xác nhận `eval/answers/expected.json` khớp với dữ liệu hiện tại. Mã thoát khác 0 nghĩa là có lỗi.

## 8. Tái tạo dữ liệu

Chạy từ thư mục gốc repo, bằng `py -3.14` hoặc `uv run python`:

```bash
py -3.14 eval/seed_gen/generate.py            # ghi lại deploy/seed/store-a.json, store-b.json
py -3.14 eval/seed_gen/generate.py --check    # chỉ xác nhận file trên đĩa khớp bản sinh lại
py -3.14 eval/answers/compute_answers.py      # cập nhật eval/answers/expected.json
```

Đổi dữ liệu mẫu nghĩa là đổi đáp án SM-2 và SM-3. Mọi thay đổi ở đây cần Lân duyệt (xem `eval/README.md`).
