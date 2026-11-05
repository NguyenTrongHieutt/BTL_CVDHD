// Đăng ký Cửa hàng (FR-43). Người giữ: Hiếu — features/platform (AD-20).
// Route công khai, không cần đăng nhập. POST đăng ký mang Idempotency-Key (AD-7).
import { Card } from 'antd';
import { Link } from 'react-router';
import { PagePlaceholder } from '../../../shared/components';

export function RegisterPage() {
  return (
    <div className="login-page">
      <Card style={{ width: '100%', maxWidth: 560 }}>
        <PagePlaceholder
          title="Đăng ký Cửa hàng"
          frs={['FR-43 (TK-00)']}
          phase="GĐ1"
          owner="Hiếu"
          description="Tên Cửa hàng, số điện thoại hoặc email, mật khẩu. Người đăng ký trở thành Chủ cửa hàng."
        >
          <div style={{ textAlign: 'center' }}>
            <Link to="/login">Quay lại đăng nhập</Link>
          </div>
        </PagePlaceholder>
      </Card>
    </div>
  );
}
