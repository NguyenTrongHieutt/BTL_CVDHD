/**
 * Tài khoản Nhân viên (FR-45, TK-02). Người giữ: Hiếu — features/platform (AD-20). Chỉ Chủ cửa hàng (route
 * `OWNER_ONLY`; backend trả `403` cho Nhân viên).
 *
 * Bảng mọi tài khoản của Cửa hàng (`DataTable`, khóa `['platform', 'users', …]`): Chủ cửa hàng đứng đầu, rồi Nhân
 * viên mới tạo trước. Tạo Nhân viên trong hộp thoại (tên đăng nhập trùng, `409 login-taken`, báo ngay dưới ô tên đăng
 * nhập; nếu lần gửi trước mất phản hồi thì nhắc xem lại bảng, vì có thể tài khoản đã được tạo); khóa, mở khóa có hộp
 * xác nhận, có hiệu lực ngay với phiên đang mở của Nhân viên; đặt lại mật khẩu trong hộp thoại (nhập mật khẩu mới hai
 * lần, không cần mật khẩu cũ). Dòng Chủ cửa hàng không có thao tác (backend trả `409 owner-protected`). Không có xóa tài
 * khoản, đổi vai trò, gửi mật khẩu qua SMS hay email: Chủ cửa hàng tự báo mật khẩu cho Nhân viên.
 */
import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Flex, Form, Input, Modal, Space, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useRef, useState } from 'react';
import { isApiError } from '../../../shared/api/client';
import { DataTable, ErrorAlert, useConfirm, useDocumentTitle } from '../../../shared/components';
import { ROLE_LABELS, USER_STATUS_LABELS } from '../../../shared/types/platform';
import type { CreateStaffRequest, StoreUser } from '../../../shared/types/platform';
import {
  LOGIN_KEY_MAX,
  LOGIN_KEY_MIN,
  LOGIN_TAKEN,
  displayNameError,
  fetchUsers,
  loginKeyError,
  passwordError,
  staffPhoneError,
  useCreateStaff,
  useResetPassword,
  useSetUserLocked,
  usersQueryKey,
} from '../api/users';

const EMPTY = '—';
const LOGIN_TAKEN_MESSAGE = 'Tên đăng nhập này đã được dùng. Hãy chọn tên khác.';
/** `409 login-taken` sau một lần gửi mất phản hồi: có thể chính lần đó đã tạo tài khoản. */
const LOGIN_TAKEN_AFTER_LOST_MESSAGE =
  'Tên đăng nhập này đã có. Lần gửi trước bị mất kết nối nên có thể tài khoản đã được tạo: xem lại bảng Nhân viên trước khi chọn tên khác.';
/** Bảng rộng cố định; màn hẹp thì cuộn ngang. */
const TABLE_SCROLL = { x: 960 };

const dateFormat = new Intl.DateTimeFormat('vi-VN', {
  timeZone: 'Asia/Ho_Chi_Minh',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

/** Rule antd từ một hàm kiểm trả câu lỗi (giống backend). */
function checkWith(check: (value: string | undefined) => string | null) {
  return {
    validator: (_: unknown, value?: string) => {
      const error = check(value);
      return error ? Promise.reject(new Error(error)) : Promise.resolve();
    },
  };
}

/** Ô "Nhập lại mật khẩu" phải khớp ô `field`. */
function sameAs(field: string) {
  return ({ getFieldValue }: { getFieldValue: (name: string) => unknown }) => ({
    validator: (_: unknown, value?: string) =>
      !value || getFieldValue(field) === value
        ? Promise.resolve()
        : Promise.reject(new Error('Mật khẩu nhập lại không khớp')),
  });
}

/**
 * Lỗi của backend đặt được hết dưới các ô có tên trong `fields`; `null` nếu có phần không thuộc ô nào (khi đó hiện
 * khung báo lỗi chung).
 */
function formErrorsOf<F extends string>(
  error: unknown,
  fields: readonly F[],
  loginTakenMessage = LOGIN_TAKEN_MESSAGE,
): { name: F; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  const isField = (field: string): field is F => (fields as readonly string[]).includes(field);
  const loginKey = 'loginKey';
  if (error.type === LOGIN_TAKEN && isField(loginKey)) {
    return [{ name: loginKey, errors: [loginTakenMessage] }];
  }
  if (error.errors.length === 0 || !error.errors.every((e) => isField(e.field))) return null;
  const byField = new Map<F, string[]>();
  for (const { field, message } of error.errors) {
    if (isField(field)) byField.set(field, [...(byField.get(field) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

/** Số cột của bảng (`columns` trong `StaffPage`), cho ô gợi ý trải hết chiều ngang. */
const COLUMN_COUNT = 7;

/**
 * Gợi ý dưới bảng khi Cửa hàng chưa có Nhân viên: Chủ cửa hàng luôn đứng đầu trang đầu, nên trang có dòng mà không dòng
 * nào là Nhân viên nghĩa là chưa có Nhân viên nào. Bảng rỗng (chưa tải được) thì không gợi ý gì.
 */
function noStaffHint(rows: readonly StoreUser[]) {
  if (rows.length === 0 || rows.some((user) => user.role === 'STAFF')) return null;
  return (
    <Table.Summary.Row>
      <Table.Summary.Cell index={0} colSpan={COLUMN_COUNT}>
        <Typography.Text type="secondary">
          {'Chưa có Nhân viên nào. Bấm "Thêm Nhân viên" để tạo tài khoản.'}
        </Typography.Text>
      </Table.Summary.Cell>
    </Table.Summary.Row>
  );
}

interface CreateFormValues extends CreateStaffRequest {
  confirmPassword: string;
}

const CREATE_FIELDS: readonly (keyof CreateStaffRequest)[] = ['loginKey', 'displayName', 'password', 'phone'];

function CreateStaffModal({ onClose }: { onClose: () => void }) {
  const [form] = Form.useForm<CreateFormValues>();
  const { message } = App.useApp();
  const create = useCreateStaff();
  // Đang gửi thì không cho đóng: đóng sẽ bỏ mất kết quả của yêu cầu đang chạy.
  const pending = create.isPending;
  // Đã có lần gửi mất phản hồi (lỗi mạng, 5xx): máy chủ có thể đã tạo tài khoản rồi.
  const lostResponse = useRef(false);

  const onFinish = (values: CreateFormValues) => {
    create.mutate(
      { loginKey: values.loginKey, displayName: values.displayName, password: values.password, phone: values.phone },
      {
        onSuccess: (user) => {
          void message.success(`Đã tạo tài khoản "${user.loginKey}". Nhân viên đăng nhập bằng tên này.`);
          onClose();
        },
        onError: (error) => {
          if (isApiError(error) && (error.status === 0 || error.status >= 500)) lostResponse.current = true;
          const errors = formErrorsOf(
            error,
            CREATE_FIELDS,
            lostResponse.current ? LOGIN_TAKEN_AFTER_LOST_MESSAGE : LOGIN_TAKEN_MESSAGE,
          );
          if (errors) form.setFields(errors);
        },
      },
    );
  };

  return (
    <Modal
      open
      title="Thêm Nhân viên"
      okText="Tạo tài khoản"
      cancelText="Hủy"
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) onClose();
      }}
      confirmLoading={pending}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {create.isError && !formErrorsOf(create.error, CREATE_FIELDS) ? <ErrorAlert error={create.error} /> : null}
      <Form<CreateFormValues> form={form} layout="vertical" requiredMark={false} onFinish={onFinish} disabled={pending}>
        <Form.Item
          name="loginKey"
          label="Tên đăng nhập"
          extra={`${LOGIN_KEY_MIN}–${LOGIN_KEY_MAX} ký tự: chữ cái không dấu, chữ số, dấu . _ -. Không dùng số điện thoại hay email.`}
          rules={[checkWith(loginKeyError)]}
        >
          <Input autoFocus autoComplete="off" spellCheck={false} />
        </Form.Item>
        <Form.Item name="displayName" label="Tên Nhân viên" rules={[checkWith(displayNameError)]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label="Số điện thoại (không bắt buộc)" rules={[checkWith(staffPhoneError)]}>
          <Input autoComplete="off" inputMode="tel" />
        </Form.Item>
        <Form.Item name="password" label="Mật khẩu" rules={[checkWith(passwordError)]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="Nhập lại mật khẩu"
          dependencies={['password']}
          rules={[{ required: true, message: 'Nhập lại mật khẩu để xác nhận' }, sameAs('password')]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

interface ResetFormValues {
  password: string;
  confirmPassword: string;
}

const RESET_FIELDS: readonly 'password'[] = ['password'];

function ResetPasswordModal({ user, onClose }: { user: StoreUser; onClose: () => void }) {
  const [form] = Form.useForm<ResetFormValues>();
  const { message } = App.useApp();
  const reset = useResetPassword();
  const pending = reset.isPending;

  const onFinish = ({ password }: ResetFormValues) => {
    reset.mutate(
      { userId: user.userId, password },
      {
        onSuccess: () => {
          void message.success(`Đã đặt lại mật khẩu cho "${user.displayName}". Hãy báo mật khẩu mới cho Nhân viên.`);
          onClose();
        },
        onError: (error) => {
          const errors = formErrorsOf(error, RESET_FIELDS);
          if (errors) form.setFields(errors);
        },
      },
    );
  };

  return (
    <Modal
      open
      title={`Đặt lại mật khẩu: ${user.displayName}`}
      okText="Đặt lại mật khẩu"
      cancelText="Hủy"
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) onClose();
      }}
      confirmLoading={pending}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      <Typography.Paragraph type="secondary">
        {`Tên đăng nhập: ${user.loginKey}. Mật khẩu cũ hết dùng được ngay khi lưu.`}
      </Typography.Paragraph>
      {reset.isError && !formErrorsOf(reset.error, RESET_FIELDS) ? <ErrorAlert error={reset.error} /> : null}
      <Form<ResetFormValues> form={form} layout="vertical" requiredMark={false} onFinish={onFinish} disabled={pending}>
        <Form.Item name="password" label="Mật khẩu mới" rules={[checkWith(passwordError)]}>
          <Input.Password autoFocus autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="Nhập lại mật khẩu mới"
          dependencies={['password']}
          rules={[{ required: true, message: 'Nhập lại mật khẩu để xác nhận' }, sameAs('password')]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export function StaffPage() {
  useDocumentTitle('Tài khoản Nhân viên');
  const [creating, setCreating] = useState(false);
  const [resetting, setResetting] = useState<StoreUser | null>(null);
  const setLocked = useSetUserLocked();
  const confirm = useConfirm();
  const { message } = App.useApp();

  const onToggleLock = async (user: StoreUser) => {
    const locking = user.status === 'ACTIVE';
    const ok = await confirm(
      locking
        ? {
            title: `Khóa tài khoản "${user.displayName}"?`,
            content:
              'Nhân viên này sẽ không đăng nhập được. Nếu đang dùng ứng dụng, họ bị đăng xuất ngay ở thao tác kế tiếp. Bạn mở khóa lại được bất cứ lúc nào.',
            okText: 'Khóa',
            danger: true,
          }
        : {
            title: `Mở khóa tài khoản "${user.displayName}"?`,
            content: 'Nhân viên này đăng nhập lại được bằng mật khẩu hiện tại.',
            okText: 'Mở khóa',
          },
    );
    if (!ok) return;
    setLocked.mutate(
      { userId: user.userId, locked: locking },
      {
        onSuccess: () => {
          void message.success(
            locking ? `Đã khóa tài khoản "${user.displayName}"` : `Đã mở khóa tài khoản "${user.displayName}"`,
          );
        },
      },
    );
  };

  const columns: TableColumnsType<StoreUser> = [
    { title: 'Tên đăng nhập', dataIndex: 'loginKey', key: 'loginKey', ellipsis: true },
    { title: 'Tên', dataIndex: 'displayName', key: 'displayName', ellipsis: true },
    {
      title: 'Vai trò',
      dataIndex: 'role',
      key: 'role',
      width: 140,
      render: (role: StoreUser['role']) => <Tag color={role === 'OWNER' ? 'gold' : 'blue'}>{ROLE_LABELS[role]}</Tag>,
    },
    {
      title: 'Trạng thái',
      dataIndex: 'status',
      key: 'status',
      width: 150,
      render: (status: StoreUser['status']) => (
        <Tag color={status === 'ACTIVE' ? 'green' : 'red'}>{USER_STATUS_LABELS[status]}</Tag>
      ),
    },
    { title: 'Số điện thoại', dataIndex: 'phone', key: 'phone', width: 150, render: (phone: string | null) => phone ?? EMPTY },
    {
      title: 'Ngày tạo',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 120,
      render: (createdAt: string) => dateFormat.format(new Date(createdAt)),
    },
    {
      title: 'Thao tác',
      key: 'actions',
      width: 230,
      render: (_, user) => {
        if (user.role !== 'STAFF') return <Typography.Text type="secondary">{EMPTY}</Typography.Text>;
        const busy = setLocked.isPending && setLocked.variables?.userId === user.userId;
        return (
          <Space size={0}>
            <Button
              type="link"
              size="small"
              danger={user.status === 'ACTIVE'}
              aria-label={`${user.status === 'ACTIVE' ? 'Khóa' : 'Mở khóa'} ${user.loginKey}`}
              disabled={setLocked.isPending}
              loading={busy}
              onClick={() => void onToggleLock(user)}
            >
              {user.status === 'ACTIVE' ? 'Khóa' : 'Mở khóa'}
            </Button>
            <Button
              type="link"
              size="small"
              aria-label={`Đặt lại mật khẩu ${user.loginKey}`}
              onClick={() => setResetting(user)}
            >
              Đặt lại mật khẩu
            </Button>
          </Space>
        );
      },
    },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 8 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          Tài khoản Nhân viên
        </Typography.Title>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => {
            // Chỉ xóa lỗi khóa, mở khóa cũ; không bỏ kết quả của yêu cầu đang chạy
            if (!setLocked.isPending) setLocked.reset();
            setCreating(true);
          }}
        >
          Thêm Nhân viên
        </Button>
      </Flex>
      <Typography.Paragraph type="secondary">
        Nhân viên đăng nhập bằng tên đăng nhập và mật khẩu bạn tạo ở đây. Tài khoản bị khóa không đăng nhập được.
      </Typography.Paragraph>
      {setLocked.isError ? <ErrorAlert error={setLocked.error} onClose={() => setLocked.reset()} /> : null}
      <DataTable<StoreUser>
        rowKey="userId"
        queryKey={usersQueryKey}
        fetchPage={(request, signal) => fetchUsers(request, signal)}
        columns={columns}
        scroll={TABLE_SCROLL}
        // Bảng luôn có dòng Chủ cửa hàng, nên rỗng chỉ khi chưa tải được (lỗi hiện phía trên)
        locale={{ emptyText: 'Không có dữ liệu' }}
        summary={noStaffHint}
      />
      {creating ? <CreateStaffModal onClose={() => setCreating(false)} /> : null}
      {resetting ? (
        <ResetPasswordModal key={resetting.userId} user={resetting} onClose={() => setResetting(null)} />
      ) : null}
    </div>
  );
}
