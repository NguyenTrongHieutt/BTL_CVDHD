/**
 * Thông tin cá nhân, đổi mật khẩu (FR-47, TK-04). Người giữ: Hiếu — features/platform (AD-20). Cả Chủ cửa hàng lẫn
 * Nhân viên, luôn là tài khoản đang đăng nhập.
 *
 * Hai phần:
 * - **Hồ sơ:** tên đăng nhập, vai trò, email chỉ để xem (không đổi được); sửa tên hiển thị và số điện thoại liên hệ
 *   (`PUT /api/v1/platform/me`). Lưu xong invalidate `['platform', 'me']`: tên ở thanh trên cùng đổi theo ngay, không
 *   cần đăng nhập lại. Dữ liệu mới về khi form chưa sửa thì form hiện dữ liệu mới.
 * - **Đổi mật khẩu:** mật khẩu hiện tại, mật khẩu mới (nhập hai lần). Mật khẩu hiện tại sai (`422
 *   wrong-current-password`, không phải hết phiên) báo ngay dưới ô mật khẩu hiện tại, kèm hướng xử lý. Đổi xong phiên
 *   đang dùng (và phiên trên thiết bị khác) vẫn đăng nhập; form được xóa trắng. Ô tên đăng nhập ẩn
 *   (`autoComplete="username"`) để trình quản lý mật khẩu cập nhật đúng tài khoản. Chủ cửa hàng không có cách tự lấy
 *   lại mật khẩu đã quên, nên phần này nói rõ phải liên hệ ai.
 *
 * Kiểm phía client giống backend; lỗi `errors[]` của backend hiện dưới ô tương ứng, lỗi khác ở khung phía trên phần
 * đó. Không có đổi tên đăng nhập, quên mật khẩu qua email/SMS hay đăng xuất các phiên khác.
 */
import { App, Button, Card, Descriptions, Form, Input, Skeleton, Typography } from 'antd';
import type { DescriptionsProps, FormInstance } from 'antd';
import { useEffect } from 'react';
import type { CSSProperties } from 'react';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert, useDocumentTitle } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import { ROLE_LABELS } from '../../../shared/types/platform';
import type { Profile } from '../../../shared/types/platform';
import {
  WRONG_CURRENT_PASSWORD,
  profilePhoneError,
  useChangePassword,
  useProfile,
  useUpdateProfile,
} from '../api/profile';
import { displayNameError, isBlankPassword, passwordError } from '../api/users';
import { checkWith, fieldErrorsOf, sameAs } from '../components/formRules';

const DISPLAY_NAME_LABEL = 'Tên hiển thị';
const NEW_PASSWORD_LABEL = 'Mật khẩu mới';
const PHONE_EXAMPLE = 'Ví dụ 0912 345 678 hoặc +84 912 345 678.';
/** Tên đăng nhập là số điện thoại (Chủ cửa hàng đăng ký bằng số): backend lưu dạng `0` + 9 chữ số. */
const PHONE_LOGIN_KEY = /^0[0-9]{9}$/;

/** Có trên trang (trình quản lý mật khẩu đọc được) nhưng không hiện ra. */
const VISUALLY_HIDDEN: CSSProperties = {
  position: 'absolute',
  width: 1,
  height: 1,
  margin: -1,
  padding: 0,
  border: 0,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
};

interface ProfileFormValues {
  displayName: string;
  phone?: string;
}

const PROFILE_FIELDS: readonly (keyof ProfileFormValues)[] = ['displayName', 'phone'];

function toFormValues(profile: Profile): ProfileFormValues {
  return { displayName: profile.displayName, phone: profile.phone ?? '' };
}

/** Đặt giá trị của form theo `profile`, coi như chưa sửa gì (`touched = false`) và xóa lỗi cũ. */
function showProfile(form: FormInstance<ProfileFormValues>, profile: Profile) {
  const values = toFormValues(profile);
  form.setFields(PROFILE_FIELDS.map((name) => ({ name, value: values[name], touched: false, errors: [] })));
}

function ProfileForm({ profile }: { profile: Profile }) {
  const [form] = Form.useForm<ProfileFormValues>();
  const { message } = App.useApp();
  const update = useUpdateProfile();
  const pending = update.isPending;

  // `initialValues` chỉ áp dụng lúc mở màn; dữ liệu mới về sau đó (tải lại nền) thì form chưa sửa hiện dữ liệu mới.
  useEffect(() => {
    if (!form.isFieldsTouched()) showProfile(form, profile);
  }, [form, profile]);

  const onFinish = (values: ProfileFormValues) => {
    update.mutate(
      { displayName: values.displayName, phone: values.phone ?? null },
      {
        onSuccess: (saved) => {
          showProfile(form, saved);
          void message.success('Đã lưu thông tin cá nhân');
        },
        onError: (error) => {
          const errors = fieldErrorsOf(error, PROFILE_FIELDS);
          if (errors) form.setFields(errors);
        },
      },
    );
  };

  const info: DescriptionsProps['items'] = [
    { key: 'loginKey', label: 'Tên đăng nhập', children: profile.loginKey },
    { key: 'role', label: 'Vai trò', children: ROLE_LABELS[profile.role] },
    ...(profile.email ? [{ key: 'email', label: 'Email', children: profile.email }] : []),
  ];

  return (
    <Card title="Hồ sơ" style={{ marginBottom: 16 }}>
      <Descriptions items={info} column={1} size="small" style={{ marginBottom: 16 }} />
      {update.isError && !fieldErrorsOf(update.error, PROFILE_FIELDS) ? <ErrorAlert error={update.error} /> : null}
      <Form<ProfileFormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending}
        initialValues={toFormValues(profile)}
      >
        <Form.Item
          name="displayName"
          label={DISPLAY_NAME_LABEL}
          extra="Tên hiện ở thanh trên cùng."
          rules={[checkWith((value) => displayNameError(value, DISPLAY_NAME_LABEL))]}
        >
          <Input autoComplete="name" />
        </Form.Item>
        <Form.Item
          name="phone"
          label="Số điện thoại liên hệ (không bắt buộc)"
          extra={
            profile.role === 'OWNER' && PHONE_LOGIN_KEY.test(profile.loginKey) ? (
              <>
                <div>{PHONE_EXAMPLE}</div>
                <div>{`Số này chỉ để liên hệ; đổi số ở đây không đổi số điện thoại đăng nhập (${profile.loginKey}).`}</div>
              </>
            ) : (
              PHONE_EXAMPLE
            )
          }
          rules={[checkWith((value) => profilePhoneError(value, profile.phone))]}
        >
          <Input inputMode="tel" autoComplete="tel" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={pending}>
          Lưu thông tin
        </Button>
      </Form>
    </Card>
  );
}

interface PasswordFormValues {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

const PASSWORD_FIELDS: readonly ('currentPassword' | 'newPassword')[] = ['currentPassword', 'newPassword'];

/**
 * Lỗi đặt được dưới ô: mật khẩu hiện tại sai (câu báo và hướng xử lý), hoặc `errors[]` theo trường; `null` thì hiện
 * khung báo lỗi chung.
 */
function passwordFormErrorsOf(error: unknown) {
  if (isApiError(error) && error.type === WRONG_CURRENT_PASSWORD) {
    const { message, hint } = PROBLEM_MESSAGES[WRONG_CURRENT_PASSWORD];
    return [{ name: 'currentPassword' as const, errors: hint ? [message, hint] : [message] }];
  }
  return fieldErrorsOf(error, PASSWORD_FIELDS);
}

/** `profile` chưa tải xong thì chưa có ô tên đăng nhập ẩn và câu riêng cho Chủ cửa hàng; form vẫn dùng được. */
function ChangePasswordForm({ profile }: { profile: Profile | undefined }) {
  const [form] = Form.useForm<PasswordFormValues>();
  const { message } = App.useApp();
  const change = useChangePassword();
  const pending = change.isPending;

  const onFinish = ({ currentPassword, newPassword }: PasswordFormValues) => {
    change.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          form.resetFields();
          void message.success('Đã đổi mật khẩu. Lần đăng nhập sau dùng mật khẩu mới.');
        },
        onError: (error) => {
          const errors = passwordFormErrorsOf(error);
          if (errors) form.setFields(errors);
        },
      },
    );
  };

  return (
    <Card title="Đổi mật khẩu">
      <Typography.Paragraph type="secondary">
        Lần đăng nhập sau dùng mật khẩu mới. Sau khi đổi, bạn vẫn đang đăng nhập ở đây, và các phiên trên thiết bị khác
        cũng vẫn đăng nhập cho tới khi hết hạn.
      </Typography.Paragraph>
      {profile?.role === 'OWNER' ? (
        <Typography.Paragraph type="secondary">
          Chủ cửa hàng quên mật khẩu thì không tự lấy lại được trong ứng dụng: hãy liên hệ người vận hành hệ thống (bản
          demo: chạy lại deploy/reset-demo, việc này đưa toàn bộ dữ liệu về dữ liệu mẫu).
        </Typography.Paragraph>
      ) : null}
      {change.isError && !passwordFormErrorsOf(change.error) ? <ErrorAlert error={change.error} /> : null}
      <Form<PasswordFormValues> form={form} layout="vertical" requiredMark={false} onFinish={onFinish} disabled={pending}>
        {profile ? (
          <input
            type="text"
            name="username"
            autoComplete="username"
            value={profile.loginKey}
            readOnly
            tabIndex={-1}
            aria-hidden="true"
            style={VISUALLY_HIDDEN}
          />
        ) : null}
        <Form.Item
          name="currentPassword"
          label="Mật khẩu hiện tại"
          rules={[checkWith((value) => (isBlankPassword(value) ? 'Nhập mật khẩu hiện tại' : null))]}
        >
          <Input.Password autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label={NEW_PASSWORD_LABEL}
          dependencies={['currentPassword']}
          rules={[
            checkWith((value) => passwordError(value, NEW_PASSWORD_LABEL)),
            ({ getFieldValue }) => ({
              validator: (_: unknown, value?: string) =>
                !value || value !== getFieldValue('currentPassword')
                  ? Promise.resolve()
                  : Promise.reject(new Error('Mật khẩu mới phải khác mật khẩu hiện tại')),
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="Nhập lại mật khẩu mới"
          dependencies={['newPassword']}
          rules={[{ required: true, message: 'Nhập lại mật khẩu mới để xác nhận' }, sameAs('newPassword')]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={pending}>
          Đổi mật khẩu
        </Button>
      </Form>
    </Card>
  );
}

export function ProfilePage() {
  useDocumentTitle('Thông tin cá nhân');
  const profile = useProfile();
  return (
    <div style={{ maxWidth: 560 }}>
      <Typography.Title level={3} style={{ marginTop: 0, marginBottom: 16 }}>
        Thông tin cá nhân
      </Typography.Title>
      {profile.isError ? <ErrorAlert error={profile.error} /> : null}
      {profile.isPending ? <Skeleton active /> : null}
      {profile.data ? <ProfileForm profile={profile.data} /> : null}
      <ChangePasswordForm profile={profile.data} />
    </div>
  );
}
