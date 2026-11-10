/**
 * Đăng ký Cửa hàng (FR-43, TK-00). Người giữ: Hiếu — features/platform (AD-20).
 *
 * Route công khai, không cần đăng nhập. Gọi `POST /api/v1/platform/stores` kèm `Idempotency-Key` (AD-7);
 * backend tạo Cửa hàng cùng tài khoản Chủ cửa hàng, đặt cookie phiên và trả `Me`, nên đăng ký xong vào
 * thẳng ứng dụng (`/` → trang chính theo vai trò). Lỗi theo từng ô (`errors[]` của
 * `400 /errors/common/validation`) hiện ngay dưới ô đó. Số điện thoại, email đã dùng hoặc Cửa hàng có thể
 * đã tạo ở lần gửi trước thì gợi ý đăng nhập (PRD UJ-1); lỗi khác hiện ở khung báo lỗi phía trên.
 */
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { Link, useNavigate } from 'react-router';
import { isApiError } from '../../../shared/api/client';
import type { FieldError } from '../../../shared/api/client';
import { useRegisterStore } from '../../../shared/api/session';
import { ErrorAlert, useDocumentTitle } from '../../../shared/components';
import type { RegisterStoreRequest } from '../../../shared/types/platform';

/** Giống `LoginKeys.OWNER_LOGIN_KEY` ở backend: số điện thoại (`0` hoặc `+84`, rồi 9 chữ số) hoặc email ASCII. */
const OWNER_LOGIN_KEY = /^(?:(?:0|\+84)[0-9]{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})$/;
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;

interface RegisterFormValues extends RegisterStoreRequest {
  confirmPassword: string;
}

type RequestField = keyof RegisterStoreRequest;
const REQUEST_FIELDS: readonly RequestField[] = ['storeName', 'loginKey', 'password', 'displayName'];

function isRequestField(field: string): field is RequestField {
  return (REQUEST_FIELDS as readonly string[]).includes(field);
}

const LOGIN_TAKEN = '/errors/platform/login-taken';
const LOGIN_TAKEN_FIELD_MESSAGE = 'Số điện thoại hoặc email này đã được dùng';

/** Lỗi mà màn này gợi ý đăng nhập thay cho câu báo lỗi chung. */
const LOGIN_SUGGESTIONS: Readonly<Record<string, string>> = {
  [LOGIN_TAKEN]: 'Số điện thoại hoặc email này đã có tài khoản. Nếu là của bạn, hãy đăng nhập.',
  '/errors/common/idempotency-key-reuse': 'Cửa hàng có thể đã được tạo ở lần gửi trước. Hãy thử đăng nhập.',
};

/** Lỗi của backend có thể hiện hết dưới các ô của form (không cần khung báo lỗi chung). */
function isFieldOnlyError(error: unknown): boolean {
  return isApiError(error) && error.errors.length > 0 && error.errors.every((e) => isRequestField(e.field));
}

/** Gom `errors[]` theo ô để đặt vào form. */
function toFormErrors(errors: readonly FieldError[]): { name: RequestField; errors: string[] }[] {
  const byField = new Map<RequestField, string[]>();
  for (const { field, message } of errors) {
    if (isRequestField(field)) byField.set(field, [...(byField.get(field) ?? []), message]);
  }
  return [...byField].map(([name, messages]) => ({ name, errors: messages }));
}

function RegisterError({ error }: { error: unknown }) {
  const suggestion = isApiError(error) ? LOGIN_SUGGESTIONS[error.type] : undefined;
  if (suggestion) {
    return (
      <Alert
        type="warning"
        showIcon
        title={suggestion}
        action={<Link to="/login">Đăng nhập</Link>}
        style={{ marginBottom: 16 }}
      />
    );
  }
  if (isFieldOnlyError(error)) return null;
  return <ErrorAlert error={error} />;
}

export function RegisterPage() {
  useDocumentTitle('Đăng ký Cửa hàng');
  const [form] = Form.useForm<RegisterFormValues>();
  const registerStore = useRegisterStore();
  const navigate = useNavigate();

  const onFinish = (values: RegisterFormValues) => {
    const request: RegisterStoreRequest = {
      storeName: values.storeName,
      loginKey: values.loginKey,
      password: values.password,
      displayName: values.displayName,
    };
    registerStore.mutate(request, {
      onSuccess: () => {
        void navigate('/', { replace: true });
      },
      onError: (error) => {
        if (!isApiError(error)) return;
        if (error.type === LOGIN_TAKEN) {
          form.setFields([{ name: 'loginKey', errors: [LOGIN_TAKEN_FIELD_MESSAGE] }]);
        } else {
          form.setFields(toFormErrors(error.errors));
        }
      },
    });
  };

  return (
    <div className="login-page">
      <Card style={{ width: '100%', maxWidth: 440 }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          Đăng ký Cửa hàng
        </Typography.Title>
        <Typography.Paragraph type="secondary">
          Người đăng ký trở thành Chủ cửa hàng. Đăng nhập lần sau bằng số điện thoại hoặc email này.
        </Typography.Paragraph>
        {registerStore.isError ? <RegisterError error={registerStore.error} /> : null}
        <Form<RegisterFormValues>
          form={form}
          layout="vertical"
          requiredMark={false}
          onFinish={onFinish}
          disabled={registerStore.isPending}
        >
          <Form.Item
            name="storeName"
            label="Tên Cửa hàng"
            rules={[
              { required: true, whitespace: true, message: 'Nhập tên Cửa hàng' },
              { max: 200, message: 'Tên Cửa hàng tối đa 200 ký tự' },
            ]}
          >
            <Input autoComplete="organization" autoFocus />
          </Form.Item>
          <Form.Item
            name="displayName"
            label="Tên của bạn"
            rules={[
              { required: true, whitespace: true, message: 'Nhập tên của bạn' },
              { max: 100, message: 'Tên tối đa 100 ký tự' },
            ]}
          >
            <Input autoComplete="name" />
          </Form.Item>
          <Form.Item
            name="loginKey"
            label="Số điện thoại hoặc email"
            rules={[
              { required: true, whitespace: true, message: 'Nhập số điện thoại hoặc email' },
              {
                transform: (value?: string) => value?.trim(),
                pattern: OWNER_LOGIN_KEY,
                message: 'Nhập số điện thoại (0xxxxxxxxx hoặc +84xxxxxxxxx) hoặc email hợp lệ',
              },
            ]}
          >
            <Input autoComplete="username" />
          </Form.Item>
          <Form.Item
            name="password"
            label="Mật khẩu"
            rules={[
              { required: true, message: 'Nhập mật khẩu' },
              {
                min: PASSWORD_MIN,
                max: PASSWORD_MAX,
                message: `Mật khẩu từ ${PASSWORD_MIN} đến ${PASSWORD_MAX} ký tự`,
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirmPassword"
            label="Nhập lại mật khẩu"
            dependencies={['password']}
            rules={[
              { required: true, message: 'Nhập lại mật khẩu để xác nhận' },
              ({ getFieldValue }) => ({
                validator: (_, value?: string) =>
                  !value || getFieldValue('password') === value
                    ? Promise.resolve()
                    : Promise.reject(new Error('Mật khẩu nhập lại không khớp')),
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={registerStore.isPending}>
            Đăng ký
          </Button>
        </Form>
        <Typography.Paragraph style={{ marginTop: 16, marginBottom: 0, textAlign: 'center' }}>
          Đã có tài khoản? <Link to="/login">Đăng nhập</Link>
        </Typography.Paragraph>
      </Card>
    </div>
  );
}
