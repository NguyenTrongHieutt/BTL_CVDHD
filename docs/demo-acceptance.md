# DM-01 · Chạy thử trước buổi demo

## Giao dịch bán hàng qua nginx

Chạy từ gốc repo trên môi trường demo cục bộ, bằng Python 3.12 trở lên. Mỗi lượt tạo hai cửa hàng mới, tài khoản nhân viên, sản phẩm cùng SKU ở cả hai cửa hàng, một khách hàng và một đơn hàng. Không dùng database chứa dữ liệu cần giữ.

```sh
python scripts/acceptance/demo_sale.py http://127.0.0.1:8090
```

Thay 8090 bằng cổng nginx của bản demo. Bài chạy dùng API thật qua cookie. Chủ cửa hàng thiết lập tồn đầu kỳ 10, nhân viên bán 2 sản phẩm giá 15.000 đ và nhận 50.000 đ tiền mặt. Bài chạy kiểm tổng 30.000 đ, trả lại 20.000 đ, gửi lại cùng khóa vẫn nhận đúng đơn cũ, đúng một dòng xuất kho và tồn cuối 8. Cửa hàng thứ hai dùng cùng SKU với giá 19.000 đ và tồn vẫn bằng 10. Nhân viên phải bị chặn ở nhật ký, chủ cửa hàng phải xem được thao tác tạo nhân viên.

Khi đường dẫn dịch vụ AI đang không khả dụng, chạy lại với `--ai-off`. Cổng web phải trả 503 cho health AI và các bước giao dịch vẫn phải đạt. Bài thử dùng tìm kiếm thường. Để xác nhận trải nghiệm của nhân viên khi đang dùng màn hình, thực hiện thêm bước tắt AI giữa giao dịch trong buổi chạy chung.

Khi Anh bàn giao DH-06, DH-09 và lọc sổ kho theo người thực hiện, thêm `--integrated`. Chế độ này yêu cầu đơn vừa tạo gắn với đúng khách, cả chủ và nhân viên đều đọc được lịch sử cùng tổng chi tiêu qua API thật, và lọc sổ kho theo chủ cửa hàng phải loại giao dịch của nhân viên khỏi cả tổng số dòng. Có thể dùng cùng `--ai-off`.

## Lượt cả nhóm

1. Lân nạp hai cửa hàng mẫu với ngày neo 16/12/2026 vào môi trường riêng. Trước khi tạo giao dịch, chủ A và B lần lượt xem doanh thu ngày neo. Đáp án là 330.000 đ và 1.577.000 đ. Chạy bộ đối chiếu KT-B và lưu kết quả cùng mã commit.
2. Anh đăng nhập nhân viên, tìm sản phẩm, gắn khách và thanh toán. Hiếu đối chiếu tiền, tồn kho, lịch sử khách và người thực hiện. Ghi mã đơn và mã biến thể để có thể đối chiếu sau khi trả hàng.
3. Anh trả một phần đơn. Hiếu đối chiếu số lượng nhập lại, phiếu trả, nhật ký và dữ liệu cửa hàng bên kia. Lân kiểm số liệu sau trả theo dữ liệu của lượt chạy.
4. Ghi điều kiện ban đầu, mã đơn, kết quả và lỗi cho từng lượt. Theo SM-7 cần ba lượt trọn luồng liên tiếp, gồm một lượt ngắt AI giữa chừng. Các bài API ở trên chỉ chấm các bước mà chúng thực sự chạy.
