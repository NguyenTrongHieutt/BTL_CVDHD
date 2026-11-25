# KT-B · nghiệm thu qua HTTP

Chạy từ gốc worktree bằng Python 3.12+ (thư viện chuẩn).

```powershell
py -3.14 -B -X utf8 eval/answers/compute_answers.py --anchor 2026-12-16 --check
py -3.14 -B -X utf8 scripts/acceptance/test_http.py
py -3.14 -B -X utf8 scripts/acceptance/run.py --live http://localhost:8000
```

Thay URL ví dụ bằng gateway demo phục vụ cả `/api/v1` và `/ai`. Môi trường live cần seed A/B đúng ngày neo 16/12/2026, ngày báo cáo hiện tại khớp ngày neo cho các truy vấn không nhận ngày và chỉ mục tìm kiếm đã đồng bộ. Runner đọc seed và đáp án từ worktree. Mỗi lần đăng nhập dùng cookie jar riêng. Runner chỉ đọc dữ liệu nghiệp vụ. Hai lần hỏi thành công cho mỗi chủ tiêu thụ hai lượt quota và có thể ghi nhật ký theo hợp đồng AI. Để kiểm quota có ý nghĩa, tránh chạy các câu hỏi khác đồng thời trên hai tài khoản demo.

## Các phép đối chiếu live

| Phạm vi | Giá trị kiểm |
|---|---|
| Phiên | Khách chưa đăng nhập nhận 401 ở reporting, search và ask. Chủ và một nhân viên mỗi cửa hàng đăng nhập bằng seed, nhận cookie, đúng tên, vai trò và cùng storeId trong một cửa hàng. A và B phải có storeId khác nhau. Nhân viên bị chặn 403 ở Q1 và ask. |
| Q1–Q3 | Ngày neo, đối chiếu revenue, orderCount, grossProfit trong summary với oracle riêng của từng cửa hàng. |
| Q4 | Top 5 bán chạy theo số lượng ngày neo. So toàn bộ trường oracle trên từng dòng, đúng thứ tự. |
| Q5 | Tổng stockQuantity và stockValue cuối dữ liệu, không lọc. |
| Q6 | Doanh thu cả tháng 12 so cả tháng 11. So hai giá trị, chênh lệch và phần trăm. |
| Q7–Q9 | Không tham số. So toàn bộ danh sách, độ dài, thứ tự và trường của từng dòng với oracle. |
| Search | Chủ và nhân viên tìm tên một sản phẩm đang bán của cửa hàng. Kiểm sản phẩm kỳ vọng trong top 3, mọi ID khác nhau, score hữu hạn, tra lại từng ID bằng cùng phiên và đối chiếu tên/SKU với seed cửa hàng. |
| Ask | Hỏi doanh thu ngày neo. Bảng phải chứa tiền đúng (A 330.000 đ, B 1.577.000 đ), mọi số tiền trong lời dẫn phải khớp. Chấp nhận lời dẫn null khi AI trả bảng dự phòng. Kiểm kỳ trong interpretation, link, cảnh báo, refused và quota. Hai lần hỏi bao quanh search để kiểm search không tiêu lượt. |

Mục 3.5 của hợp đồng mô tả khung chung và ví dụ Q1. Runner dùng các khóa số liệu trong `eval/answers/expected.json` cho các Q còn lại. Quy ước ánh xạ đang dùng là Q5/Q9 trả range ngày chụp tồn, Q7/Q8 trả cửa sổ tính tốc độ bán, Q6 trả range kỳ thứ nhất và kỳ so sánh trong params. Q4 và Q7–Q9 đưa các dòng oracle vào rows. Nếu triển khai dùng cách ánh xạ khác, đối chiếu hợp đồng với Lân trước khi kết luận sai số liệu. Kiểm quota theo mục 3.6, gồm cả remaining.

## Cách đọc kết quả

`--live` in PASS/FAIL theo cửa hàng, vai trò và ca kiểm. Mã thoát 0 khi tất cả ca đạt, 1 khi có lỗi. Đăng nhập lỗi sẽ bỏ qua các ca cần phiên đó và làm lần chạy thất bại.

`test_http.py` là **mô phỏng HTTP trên loopback**. Gateway trả hằng số viết riêng, kiểm đường dẫn, cookie, body và tham số. Gateway không dùng runner để dựng đáp án và không đọc file oracle. Phía chấm dùng oracle thật cho các số tổng hợp và thay bốn danh sách xếp hạng bằng danh sách ngắn chỉ trong bộ nhớ của test. Trong live, runner đọc nguyên danh sách từ đĩa. Các test âm cố ý đổi dữ liệu A/B ở từng Q, từng phần bảng/lời dẫn của ask, và ID trong search.

Hai ca AI theo cửa hàng là smoke test. Để chấm SM-3 cần bộ 15 câu giữ riêng (đạt ít nhất 14), để chấm SM-4 cần 20 truy vấn có nhãn (đạt ít nhất 16). Hai bộ này chưa có ở commit nền `9714480`.
