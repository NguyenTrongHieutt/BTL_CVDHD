# Quy ước làm việc nhóm (CB-07)

## Nhánh Git

- `main` luôn build được và chạy được. Không push thẳng lên `main` sau GĐ0.
- Mỗi chức năng một nhánh: `feature/<mã>-<mô-tả-ngắn>`, ví dụ `feature/DH-01-man-hinh-ban-hang`, `feature/KHO-01-cap-nhat-ton-kho`.
- Sửa lỗi: `fix/<mã>-<mô-tả>`; tài liệu, cấu hình: `chore/<mô-tả>`.
- Mở Pull Request vào `main`. **Một thành viên khác xem lại code** rồi mới merge (kế hoạch phân công mục 7). File chạm vào thư mục của người khác cần người đó duyệt (xem `CODEOWNERS`).

## Commit

- Commit bằng **tài khoản Git của chính bạn** (`git config user.name` / `user.email`). Không dùng tài khoản bot hay AI, không thêm dòng `Co-Authored-By` của AI.
- Tin nhắn commit ngắn, có mã chức năng: `feat(DH-01): thêm giỏ hàng`, `fix(KHO-03): làm tròn giá vốn`, `chore: cập nhật README`.

## Trước khi mở PR

```bash
cd backend && ./mvnw verify          # cần Docker cho Testcontainers
cd ai-service && uv run pytest
cd frontend && npm run lint && npm test && npm run build
```

## Quy tắc quan trọng

- Không ghi vào bảng của module người khác; gọi API công khai của họ (`docs/api/contracts.md`).
- Đổi cấu trúc cơ sở dữ liệu: thêm file migration mới, báo cả nhóm, Hiếu duyệt.
- Chức năng ★ bị trễ: người cần dùng làm tiếp với API giả; người cung cấp báo ngày bàn giao mới.
- Họp ngắn 2 lần mỗi tuần, cập nhật theo bảng thông số thời gian trong Kế hoạch phân công.
