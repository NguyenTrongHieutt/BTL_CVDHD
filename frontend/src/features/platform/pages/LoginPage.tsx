/**
 * Đăng nhập (FR-44). Người giữ: Hiếu — features/platform (AD-20).
 *
 * Khung đã chạy được theo AD-9: gọi `POST /api/v1/platform/auth/login`, backend đặt cookie HttpOnly
 * và trả `Me`; xong thì về trang đã định mở (hoặc trang chính theo vai trò).
 * Sai thông tin hoặc tài khoản bị khóa thì báo lỗi chung, không nói tài khoản có tồn tại hay không (FR-44).
 * Chủ cửa hàng dùng số điện thoại hoặc email, Nhân viên dùng tên đăng nhập. `useLogin` cắt khoảng trắng; backend
 * cắt khoảng trắng, đổi chữ thường và `+84` → `0`.
 */
import { ShopOutlined } from '@ant-design/icons';
import { Button, Card, Form, Input, Typography } from 'antd';
import { Link, useLocation, useNavigate } from 'react-router';
import { useLogin } from '../../../shared/api/session';
import { ErrorAlert, useDocumentTitle } from '../../../shared/components';
import type { LoginRequest } from '../../../shared/types/platform';
import '../auth.css';

function redirectTarget(state: unknown): string {
  if (typeof state === 'object' && state !== null && 'from' in state && typeof state.from === 'string') {
    const from = state.from;
    // Chỉ đường dẫn nội bộ: "//host" và "/\host" đều bị trình duyệt hiểu là địa chỉ ngoài
    if (from.startsWith('/') && !from.startsWith('//') && !from.startsWith('/\\') && from !== '/login') return from;
  }
  return '/';
}

export function LoginPage() {
  useDocumentTitle('Đăng nhập');
  const login = useLogin();
  const navigate = useNavigate();
  const location = useLocation();

  const onFinish = (values: LoginRequest) => {
    login.mutate(values, {
      onSuccess: () => {
        void navigate(redirectTarget(location.state), { replace: true });
      },
    });
  };

  return (
    <main className="login-page" aria-labelledby="login-title">
      <Card style={{ width: '100%', maxWidth: 440 }}>
        <div className="login-mark"><ShopOutlined aria-hidden="true" /> Quản lý bán hàng</div>
        <Typography.Title level={1} id="login-title" className="login-title">
          Đăng nhập
        </Typography.Title>
        <p className="login-subtitle">Đăng nhập để tiếp tục công việc tại cửa hàng.</p>
        {login.isError ? <ErrorAlert error={login.error} /> : null}
        <Form<LoginRequest>
          name="login"
          layout="vertical"
          labelWrap
          requiredMark={false}
          scrollToFirstError={{ focus: true }}
          onFinish={onFinish}
          disabled={login.isPending}
        >
          <Form.Item
            name="loginKey"
            label="Số điện thoại, email hoặc tên đăng nhập"
            rules={[{ required: true, whitespace: true, message: 'Nhập số điện thoại, email hoặc tên đăng nhập' }]}
          >
            <Input autoComplete="username" autoCapitalize="none" spellCheck={false} />
          </Form.Item>
          <Form.Item name="password" label="Mật khẩu" rules={[{ required: true, message: 'Nhập mật khẩu' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={login.isPending}>
            Đăng nhập
          </Button>
        </Form>
        <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0, textAlign: 'center' }}>
          Chưa có tài khoản? <Link to="/platform/register">Đăng ký Cửa hàng</Link>
        </Typography.Paragraph>
      </Card>
    </main>
  );
}
