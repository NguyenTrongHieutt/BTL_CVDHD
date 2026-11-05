# `eval/`: dữ liệu mẫu, đáp án độc lập và bộ đánh giá Trợ lý AI

**Người giữ: Lân** (C – Phân tích và AI). Mọi thay đổi trong `eval/` và trong `deploy/seed/*.json` đều cần Lân duyệt, vì chúng làm đổi đáp án của SM-2 và SM-3.

Theo AD-18, đáp án kiểm thử phải được một script độc lập tính từ dữ liệu mẫu, không lấy từ chính hệ thống. Script trong thư mục này:
- không import code của `backend/` hay `ai-service/`;
- không đọc database;
- không import generator.

Nó chỉ đọc file JSON, tự phát lại mọi sự kiện rồi tính lại từ đầu.

## 1. Nội dung

| Đường dẫn | Nội dung | Trạng thái |
|---|---|---|
| `seed_gen/generate.py` | Sinh `deploy/seed/store-a.json` và `store-b.json`. Tất định, chỉ dùng thư viện chuẩn | Có (CB-06) |
| `answers/compute_answers.py` | Đáp án độc lập: kiểm schema, phát lại sự kiện, kiểm bất biến, tính Q1–Q9 | Có (CB-06) |
| `answers/expected.json` | Đáp án Q1–Q9 cho 2 Cửa hàng, theo ngày neo chuẩn `2026-12-16`. File sinh ra, không sửa tay | Có (CB-06) |
| `questions/train.*`, `questions/holdout.*` | 30 câu luyện và 15 câu giữ riêng (SM-3). Bộ giữ riêng không dùng khi phát triển | Chưa có |
| `search/queries.*` | 20 truy vấn Tìm kiếm ngữ nghĩa, mỗi truy vấn kèm Sản phẩm đúng (SM-4) | Chưa có |
| `run.py` | Chạy bộ giữ riêng và bộ truy vấn với hệ thống thật, rồi chấm điểm (AD-21) | Chưa có |

Định dạng file dữ liệu mẫu, tài khoản demo và thứ tự nạp: xem `deploy/seed/README.md`.

## 2. Cách chạy

Chạy từ thư mục gốc repo (`product/`). Dùng `py -3.14` hay `uv run python` đều được, không cần cài thêm gói.

```bash
# 1. Sinh lại dữ liệu mẫu (ghi đè deploy/seed/store-*.json)
py -3.14 eval/seed_gen/generate.py
py -3.14 eval/seed_gen/generate.py --check        # chỉ xác nhận file trên đĩa đúng bằng bản sinh lại

# 2. Tính lại đáp án (ghi eval/answers/expected.json)
py -3.14 eval/answers/compute_answers.py
py -3.14 eval/answers/compute_answers.py --check  # không ghi; báo lỗi nếu dữ liệu sai quy tắc hoặc expected.json đã cũ
py -3.14 eval/answers/compute_answers.py --selftest   # kiểm tra quy tắc tiền bằng số tính tay

# Đáp án cho ngày neo khác, ví dụ ngày demo
py -3.14 eval/answers/compute_answers.py --anchor 2027-01-08 --out eval/answers/expected-2027-01-08.json
```

Ngày neo của `compute_answers.py` lấy theo thứ tự ưu tiên: `--anchor`, rồi biến môi trường `SEED_ANCHOR_DATE`, rồi mặc định `2026-12-16`.

`--check` so `expected.json` với ngày neo đã ghi trong chính file đó. Nếu máy đang đặt `SEED_ANCHOR_DATE` khác ngày neo đó, lệnh sẽ báo khác ngày neo; khi đó thêm `--anchor 2026-12-16`.

| Mã thoát | Ý nghĩa |
|---|---|
| 0 | Đạt |
| 1 | Dữ liệu mẫu sai schema, vi phạm quy tắc nghiệp vụ hoặc vi phạm bất biến. Script in danh sách lỗi |
| 2 | `expected.json` thiếu, khác ngày neo, hoặc không khớp dữ liệu hiện tại (`--check`) |
| 3 | `--selftest` thất bại |

**Khi sửa dữ liệu mẫu:**
1. Sửa `generate.py`.
2. Chạy generator.
3. Chạy `compute_answers.py`.
4. Chạy `--check` cho cả hai script.
5. Commit cả 3 file JSON trong cùng một commit.

Test golden Q1–Q9 của `reporting` (AD-10) đọc `answers/expected.json`.

## 3. Script kiểm tra gì

`compute_answers.py` làm các việc sau với mỗi Cửa hàng, theo thứ tự thời gian:
- Kiểm file theo `deploy/seed/schema/store-seed.schema.json`. Validator draft 2020-12 nằm ngay trong script; gặp từ khóa schema chưa hỗ trợ thì script báo lỗi chứ không bỏ qua.
- Kiểm khóa unique (mã, SKU, mã vạch, số điện thoại, tên đăng nhập toàn hệ thống) và kiểm mọi tham chiếu.
- Phát lại tồn đầu kỳ, Phiếu nhập (Giá vốn theo AD-15), Đơn hàng (tiền theo AD-8, `unit_cost` lúc bán, Lô hàng FEFO), hủy đơn, Phiếu trả hàng, ngừng kinh doanh, kiểm kho và Điểm tích lũy.
- Báo vi phạm khi:
  - bán vượt Tồn kho, hoặc bán, nhập Biến thể đã ngừng kinh doanh;
  - trả vượt số đã bán trừ số đã trả;
  - hủy đơn đã có Phiếu trả hàng, hoặc Nhân viên hủy đơn của người khác hay đơn ngoài ngày;
  - số dư điểm âm;
  - `quotedTotal`, `expectedRefundTotal`, `expectedSystemQuantity` hoặc `expectedOrderCode` không khớp số tính lại;
  - có hai sự kiện trùng thời điểm;
  - `status` không khớp `deactivations`.
- Bất biến cuối kỳ:
  - Tồn kho không âm và bằng Σ lịch sử xuất nhập;
  - với Biến thể theo Lô hàng, Tồn kho bằng Σ số còn lại của các lô;
  - số dư điểm bằng Σ lịch sử điểm.

`--selftest` kiểm các phép tính tiền bằng số tính tay: làm tròn half-up, chia giảm giá toàn đơn, Giá vốn bình quân, chia tiền hoàn, điểm khi trả hàng. Sau đó nó phát lại một Cửa hàng thử 1 Sản phẩm với doanh thu, lợi nhuận, Giá vốn và Q7, Q8, Q9 đã tính tay, và xác nhận script bắt được dữ liệu sai: bán vượt tồn, trả vượt, hủy sau khi trả, `quotedTotal` sai.

## 4. Định nghĩa dùng để tính đáp án

Đây là hợp đồng với lớp hằng số và định nghĩa duy nhất trong `reporting` (AD-10). Nếu `reporting` cài khác, sửa cả hai nơi trong cùng một PR.

**Thời gian.**
- "Ngày" cắt theo `Asia/Ho_Chi_Minh`. Khoảng ngày bao gồm cả hai đầu (AD-19). Tuần tính từ Thứ Hai đến Chủ nhật.
- "N ngày gần nhất" gồm cả ngày neo: `[neo − (N−1), neo]`.
- Các bộ tham số chuẩn (khóa trong `expected.json`):
  - `today`, `yesterday`
  - `this_week`, `last_week`
  - `this_month`, `last_month`
  - `month_to_date` (1 → ngày neo), `last_month_to_date` (cùng số ngày ở tháng trước)
  - `last_7_days`, `previous_7_days`
  - `last_30_days`, `previous_30_days`
  - `all_data`

**Q1 – Doanh thu.** `Σ total` của Đơn hàng không bị hủy, theo ngày tạo đơn, trừ `Σ tiền hoàn`, theo ngày lập Phiếu trả hàng.
- Giảm giá đã nằm trong `total`, kể cả phần giảm bằng điểm.
- Đơn sau đó bị trả toàn bộ vẫn tính vào ngày tạo; phần trả bị trừ vào ngày trả (FR-30).

**Q2 – Số Đơn hàng.** Số Đơn hàng không bị hủy, theo ngày tạo, gồm cả đơn sau đó bị trả toàn bộ. Kèm `cancelledCount`, `fullyReturnedCount` và `returnCount` để đối chiếu.

**Q3 – Lợi nhuận gộp.** Doanh thu trừ giá vốn hàng bán.
- Giá vốn hàng bán = `Σ số lượng bán × unit_cost` lưu ở dòng đơn, trừ `Σ số lượng trả × unit_cost của dòng gốc` (AD-15).

**Q4 – Bán chạy / bán chậm theo kỳ.** Gộp theo **Sản phẩm**, cộng mọi Biến thể.
- Số lượng = bán trừ trả trong kỳ. Doanh thu = `Σ net_line_amount` trừ tiền hoàn trong kỳ.
- `topByQuantity` sắp theo số lượng giảm dần, rồi doanh thu giảm dần, rồi mã. Chỉ lấy dòng có số lượng > 0.
- `topByRevenue` sắp theo doanh thu, rồi số lượng, rồi mã.
- `bottomByQuantity` chỉ gồm Sản phẩm đang kinh doanh, kể cả Sản phẩm bán được 0; sắp tăng dần.
- Mỗi danh sách có 10 dòng; top 5 là 5 dòng đầu.

**Q5 – Tồn kho và giá trị Tồn kho.** Tính tại cuối dữ liệu, chỉ Biến thể đang kinh doanh. Giá trị = `số lượng × avg_unit_cost`.
- Kết quả có tổng, theo Danh mục và theo Sản phẩm, kèm từng Biến thể. Như vậy mọi giá trị của tham số "Sản phẩm hoặc Danh mục" đều tra được.

**Tốc độ bán N ngày** = `max(0, bán − trả trong N ngày gần nhất) ÷ N`, tính trên **Biến thể**. **Số ngày đủ bán** = `Tồn kho ÷ Tốc độ bán`.

**Q7 – Tồn nhiều, bán chậm.**
- Lấy Biến thể đang kinh doanh có Tồn kho > 0, thỏa một trong hai: không bán được trong 30 ngày, hoặc số ngày đủ bán theo tốc độ 30 ngày lớn hơn 30. Hai điều kiện này tương đương với `Tồn kho > số bán ròng 30 ngày`.
- Sắp xếp: các dòng không bán được đứng trước; sau đó số ngày đủ bán giảm dần; rồi giá trị Tồn kho giảm dần; rồi SKU.

**Q8 – Gợi ý nhập hàng.**
- Lấy Biến thể đang kinh doanh có số ngày đủ bán theo tốc độ 14 ngày nhỏ hơn 7, hoặc có Tồn kho nhỏ hơn Ngưỡng tồn tối thiểu. `reasons` ghi rõ điều kiện nào đúng.
- `formulaQuantity = ⌈tốc độ 14 ngày × 7 − Tồn kho⌉`, đúng công thức của PRD.
- `suggestedQuantity = max(formulaQuantity, Ngưỡng − Tồn kho)`.
- Sắp theo số ngày đủ bán tăng dần (dòng không bán được đứng cuối), rồi mức thiếu so với Ngưỡng giảm dần, rồi SKU.

**Q9 – Nên ưu tiên bán.** Các dòng của Q7 có giá bán lớn hơn Giá vốn, sắp theo giá trị Tồn kho giảm dần, rồi SKU.

**Q6 – So sánh hai kỳ.** Tính cho Q1, Q2 và Q3, với 6 cặp kỳ:
- tháng này / tháng trước;
- từ đầu tháng tới nay / cùng kỳ tháng trước;
- tuần này / tuần trước;
- 7 ngày / 7 ngày trước đó;
- 30 ngày / 30 ngày trước đó;
- hôm nay / hôm qua.

Mỗi cặp có `a`, `b`, `diff` và `changePct = (a − b) ÷ b × 100`. Nếu `b = 0` thì `changePct = null`.

**Làm tròn.** Tiền luôn là số nguyên đồng. `daysOfStock` và `changePct` làm tròn 1 chữ số thập phân, `salesVelocity` 2 chữ số, theo half-up. Nên lấy số nguyên gốc (`stockQuantity`, `unitsSold30`, …) để tự tính lại khi cần.

Biến thể đã ngừng kinh doanh không có trong Q4 `bottomByQuantity`, Q5, Q7, Q8, Q9, cảnh báo tồn thấp và cảnh báo Lô hàng. Chúng vẫn được tính trong doanh thu, lợi nhuận và Q4 `top*` (FR-3: báo cáo vẫn hiển thị).

**Ngoài Q1–Q9,** `expected.json` còn có các phần sau để đối chiếu báo cáo FR-29 đến FR-33 (SM-2):
- `dashboard`: doanh thu và số đơn hôm nay, 5 Sản phẩm bán chạy trong 7 ngày, cảnh báo tồn thấp;
- `lots`: Lô hàng sắp hết hạn trong N ngày và Lô hàng đã hết hạn;
- `daily`, `weekly`, `monthly`: số liệu theo ngày, tuần, tháng;
- `summary`, `invariants`.

## 5. Các điểm tài liệu chưa rõ và cách đã chọn

| # | Điểm chưa rõ | Đã chọn |
|---|---|---|
| 1 | Giá trị mặc định của `SEED_ANCHOR_DATE` | `2026-12-16` (Thứ Tư). Chương trình nạp dùng giá trị này khi biến môi trường trống |
| 2 | "7 ngày gần nhất" có gồm hôm nay không | Có: `[neo−6, neo]` |
| 3 | Đơn bị trả toàn bộ (trạng thái *Đã trả hàng*) có còn tính doanh thu ngày bán không | Có. Tiền hoàn bị trừ vào ngày trả. Nếu loại đơn khỏi ngày bán thì sẽ trừ hai lần |
| 4 | Đơn bị hủy: loại hẳn, hay trừ vào ngày hủy | Loại hẳn. Mọi lần hủy trong dữ liệu đều cùng ngày với đơn, nên cả hai cách cho cùng một con số |
| 5 | Dùng điểm là giảm giá hay là hình thức thanh toán | Là giảm giá (FR-21: "dùng điểm … để giảm giá"). Gộp với giảm giá toàn đơn rồi chia theo dòng |
| 6 | Làm tròn khi chia tỷ lệ (giảm giá toàn đơn, tiền hoàn) | Các dòng trước lấy phần nguyên (floor). Dòng cuối, hoặc lần trả cuối của dòng, nhận phần dư (AD-8) |
| 7 | Trừ điểm khi trả một phần | Điểm giữ lại = `floor((total − tổng đã hoàn) / đơn vị tích điểm)`. Trừ phần chênh lệch. Trả hết thì trừ đúng số đã cộng |
| 8 | Q7–Q9 tính theo Sản phẩm hay Biến thể | Theo **Biến thể**, vì Tồn kho, Ngưỡng tồn tối thiểu, giá bán và Giá vốn đều ở mức Biến thể (AD-6). Q4 gộp theo Sản phẩm |
| 9 | Tốc độ bán dùng số bán gộp hay số bán ròng | Số bán ròng (bán − trả trong cửa sổ, tối thiểu 0). Biến thể Tồn kho = 0 không bị tính là bán chậm |
| 10 | Công thức số lượng gợi ý của PRD có thể ra số âm khi món chỉ dưới Ngưỡng tồn | Trả cả hai số: `formulaQuantity` (đúng PRD) và `suggestedQuantity` (tối thiểu đủ lên Ngưỡng) |
| 11 | "Bán chậm" trong Q4 theo kỳ | Q4 trả `bottomByQuantity` của kỳ. Danh sách bán chậm theo định nghĩa 30 ngày là Q7 |
| 12 | Thứ tự sắp và cách phá hòa | Như mục 4 |
| 13 | Hủy đơn có tính lại Giá vốn không | Dữ liệu tránh trường hợp này (không có Phiếu nhập xen giữa lúc bán và lúc hủy). Script không tính lại |
| 14 | Trả hàng hoặc hủy dòng theo Lô hàng thì cộng vào lô nào; kiểm kho tăng thì vào lô nào | Dữ liệu không có các trường hợp này. Script trả về đúng lô đã xuất; kiểm kho tăng thì cộng vào lô hạn xa nhất |
| 15 | Có bán từ Lô hàng đã hết hạn không | Dữ liệu không bán từ lô hết hạn. Kiểm kho cuối tháng hủy lô hết hạn nếu có |
| 16 | PRD 6.1 ghi "Sản phẩm: 150" | 150 Sản phẩm mỗi Cửa hàng. Cửa hàng B có 652 Biến thể |

**Lưu ý khi demo:** với định nghĩa "bán chậm" của PRD, Q7 của Thời trang Mai chứa khoảng 90% Biến thể, vì mỗi màu/size bán chưa tới 1 cái trong 30 ngày. Định nghĩa không sai, nhưng câu trả lời "5 món bán chậm" nên lấy 5 dòng đầu, tức các món không bán được có giá trị Tồn kho lớn nhất. Muốn đổi sang gộp theo Sản phẩm thì sửa đồng thời `reporting` và script này.
