import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { configureApiClient } from '../../../shared/api/client';
import { useMe } from '../../../shared/api/session';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { Me, Profile } from '../../../shared/types/platform';
import { ProfilePage } from './ProfilePage';

const ME_PATH = '/api/v1/platform/me';
const PROFILE_PATH = '/api/v1/platform/me/profile';
const PASSWORD_PATH = '/api/v1/platform/me/password';

const fetchMock = vi.fn<typeof fetch>();
const onUnauthorized = vi.fn<() => void>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function problem(problemType: string, status: number, errors?: { field: string; message: string }[]): Response {
  return json({ type: problemType, title: 'x', status, requestId: 'req-1', ...(errors ? { errors } : {}) }, status);
}

const OWNER: Profile = {
  userId: 'u-1',
  loginKey: 'chu@cuahang.vn',
  displayName: 'Chị Hoa',
  role: 'OWNER',
  phone: '0912345678',
  email: 'chu@cuahang.vn',
};

const STAFF: Profile = {
  userId: 'u-2',
  loginKey: 'minh.nv',
  displayName: 'Minh',
  role: 'STAFF',
  phone: null,
  email: null,
};

let profile: Profile;
let password: string;
/** Khi có, `PUT /me` trả phản hồi này thay vì lưu. */
let putResponse: Response | undefined;
/** `true`: `GET` hồ sơ không bao giờ trả lời (màn chỉ còn dữ liệu trong cache). */
let holdProfileGets: boolean;

/** Backend giả: giữ hồ sơ, mật khẩu trong bộ nhớ, trả đúng mã lỗi của TK-04; `/me` trả tên đang lưu. */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, string | null>) : {};
    if (url.pathname === PROFILE_PATH && method === 'GET') {
      if (holdProfileGets) return new Promise<Response>(() => {});
      return json(profile);
    }
    if (url.pathname === ME_PATH && method === 'GET') {
      const me: Me = {
        userId: profile.userId,
        storeId: 's-1',
        storeName: 'Tạp hóa Hoa',
        role: profile.role,
        displayName: profile.displayName,
      };
      return json(me);
    }
    if (url.pathname === ME_PATH && method === 'PUT') {
      if (putResponse) return putResponse;
      profile = { ...profile, displayName: String(body.displayName), phone: body.phone ?? null };
      return json(profile);
    }
    if (url.pathname === PASSWORD_PATH && method === 'POST') {
      if (body.currentPassword !== password) return problem('/errors/platform/wrong-current-password', 422);
      password = String(body.newPassword);
      return new Response(null, { status: 204 });
    }
    return problem('/errors/common/not-found', 404);
  });
}

/** Tên người dùng như ở thanh trên cùng của `AppLayout` (đọc `Me.displayName`). */
function TopBar() {
  const me = useMe();
  return <header aria-label="Thanh trên cùng">{me.data?.displayName}</header>;
}

function newQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
}

function renderPage(queryClient = newQueryClient()) {
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <TopBar />
        <ProfilePage />
      </AntdApp>
    </QueryClientProvider>,
  );
  return { queryClient, view };
}

async function renderLoaded() {
  renderPage();
  await screen.findByDisplayValue(profile.displayName);
}

function field(label: string): HTMLInputElement {
  return screen.getByLabelText(label);
}

function type(label: string, value: string) {
  fireEvent.change(field(label), { target: { value } });
}

function card(title: string): HTMLElement {
  const found = screen.getByText(title, { selector: '.ant-card-head-title' }).closest<HTMLElement>('.ant-card');
  if (!found) throw new Error(`không thấy phần ${title}`);
  return found;
}

function formItem(label: string): HTMLElement {
  const item = field(label).closest<HTMLElement>('.ant-form-item');
  if (!item) throw new Error(`không thấy ô ${label}`);
  return item;
}

function callsTo(method: string, path: string) {
  return fetchMock.mock.calls.filter(
    ([input, init]) => (init?.method ?? 'GET') === method && new URL(String(input), 'http://localhost').pathname === path,
  );
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

function saveProfile() {
  fireEvent.click(screen.getByRole('button', { name: 'Lưu thông tin' }));
}

function submitPassword() {
  fireEvent.click(screen.getByRole('button', { name: 'Đổi mật khẩu' }));
}

function fillPasswords(current: string, next: string, confirm = next) {
  type('Mật khẩu hiện tại', current);
  type('Mật khẩu mới', next);
  type('Nhập lại mật khẩu mới', confirm);
}

beforeEach(() => {
  fetchMock.mockReset();
  onUnauthorized.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  configureApiClient({ onUnauthorized });
  profile = { ...OWNER };
  password = 'matkhau1';
  putResponse = undefined;
  holdProfileGets = false;
  backend();
});

afterEach(() => {
  configureApiClient({ onUnauthorized: null });
});

describe('Thông tin cá nhân (FR-47, TK-04)', () => {
  it('hiện hồ sơ; tên đăng nhập, vai trò, email chỉ để xem', async () => {
    await renderLoaded();
    const info = card('Hồ sơ');
    expect(within(info).getByText('Tên đăng nhập')).toBeTruthy();
    expect(within(info).getAllByText('chu@cuahang.vn')).toHaveLength(2);   // tên đăng nhập và email
    expect(within(info).getByText('Chủ cửa hàng')).toBeTruthy();
    expect(field('Tên hiển thị').value).toBe('Chị Hoa');
    expect(field('Số điện thoại liên hệ (không bắt buộc)').value).toBe('0912345678');
    // Không có ô để sửa tên đăng nhập, email (chỉ có ô ẩn, chỉ đọc cho trình quản lý mật khẩu)
    const editable = screen.queryAllByDisplayValue<HTMLInputElement>('chu@cuahang.vn').filter((input) => !input.readOnly);
    expect(editable).toHaveLength(0);
  });

  it('Nhân viên thấy vai trò Nhân viên, không có dòng email', async () => {
    profile = { ...STAFF };
    await renderLoaded();
    const info = card('Hồ sơ');
    expect(within(info).getByText('minh.nv')).toBeTruthy();
    expect(within(info).getByText('Nhân viên')).toBeTruthy();
    expect(within(info).queryByText('Email')).toBeNull();
    expect(field('Số điện thoại liên hệ (không bắt buộc)').value).toBe('');
  });

  it('đổi tên hiển thị thì tên ở thanh trên cùng đổi theo, không cần đăng nhập lại', async () => {
    await renderLoaded();
    expect(await screen.findByText('Chị Hoa', { selector: 'header' })).toBeTruthy();

    type('Tên hiển thị', ' Minh Anh ');
    type('Số điện thoại liên hệ (không bắt buộc)', '');
    saveProfile();

    expect(await screen.findByText('Đã lưu thông tin cá nhân')).toBeTruthy();
    expect(callsTo('PUT', ME_PATH)).toHaveLength(1);
    expect(bodyOf(callsTo('PUT', ME_PATH)[0])).toEqual({ displayName: 'Minh Anh', phone: null });
    // `PUT` ghi đè: không có Idempotency-Key
    expect(new Headers(callsTo('PUT', ME_PATH)[0][1]?.headers).get('Idempotency-Key')).toBeNull();
    expect(await screen.findByText('Minh Anh', { selector: 'header' })).toBeTruthy();
    expect(field('Tên hiển thị').value).toBe('Minh Anh');
  });

  it('số điện thoại gửi dạng 0… như backend lưu', async () => {
    await renderLoaded();
    type('Số điện thoại liên hệ (không bắt buộc)', '+84 987-654.321');
    saveProfile();
    await waitFor(() => expect(callsTo('PUT', ME_PATH)).toHaveLength(1));
    expect(bodyOf(callsTo('PUT', ME_PATH)[0])).toEqual({ displayName: 'Chị Hoa', phone: '0987654321' });
    expect(await screen.findByDisplayValue('0987654321')).toBeTruthy();
  });

  it('tên trống, số sai dạng thì báo dưới ô và không gửi', async () => {
    await renderLoaded();
    type('Tên hiển thị', '   ');
    type('Số điện thoại liên hệ (không bắt buộc)', '0912 345');
    saveProfile();
    expect(await screen.findByText('Nhập tên hiển thị')).toBeTruthy();
    expect(screen.getByText('Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số')).toBeTruthy();

    type('Tên hiển thị', 'a'.repeat(101));
    saveProfile();
    expect(await screen.findByText('Tên hiển thị tối đa 100 ký tự')).toBeTruthy();
    expect(callsTo('PUT', ME_PATH)).toHaveLength(0);
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng; lỗi khác ở khung phía trên', async () => {
    putResponse = problem('/errors/common/validation', 400, [{ field: 'displayName', message: 'Tên hiển thị có ký tự không hợp lệ' }]);
    await renderLoaded();
    saveProfile();
    expect(await within(formItem('Tên hiển thị')).findByText('Tên hiển thị có ký tự không hợp lệ')).toBeTruthy();
    expect(screen.queryByText('Dữ liệu nhập chưa hợp lệ.')).toBeNull();

    putResponse = problem('/errors/common/internal', 500);
    saveProfile();
    expect(await screen.findByText('Máy chủ gặp lỗi.')).toBeTruthy();
  });

  it('đổi mật khẩu: gửi nguyên mật khẩu, xóa trắng form, vẫn đăng nhập', async () => {
    await renderLoaded();
    fillPasswords('matkhau1', ' mat khau moi ');
    submitPassword();

    expect(await screen.findByText('Đã đổi mật khẩu. Lần đăng nhập sau dùng mật khẩu mới.')).toBeTruthy();
    expect(callsTo('POST', PASSWORD_PATH)).toHaveLength(1);
    expect(bodyOf(callsTo('POST', PASSWORD_PATH)[0])).toEqual({
      currentPassword: 'matkhau1',
      newPassword: ' mat khau moi ',
    });
    expect(password).toBe(' mat khau moi ');
    await waitFor(() => expect(field('Mật khẩu hiện tại').value).toBe(''));
    expect(field('Mật khẩu mới').value).toBe('');
    expect(field('Nhập lại mật khẩu mới').value).toBe('');
    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(screen.getByText('Chị Hoa', { selector: 'header' })).toBeTruthy();
  });

  it('sai mật khẩu hiện tại thì báo dưới ô đó, không coi là hết phiên', async () => {
    await renderLoaded();
    fillPasswords('sai-mat-khau', 'mat-khau-moi-9');
    submitPassword();

    const current = formItem('Mật khẩu hiện tại');
    expect(await within(current).findByText('Mật khẩu hiện tại không đúng.')).toBeTruthy();
    expect(within(current).getByText(PROBLEM_MESSAGES['/errors/platform/wrong-current-password'].hint ?? '')).toBeTruthy();
    expect(password).toBe('matkhau1');
    expect(onUnauthorized).not.toHaveBeenCalled();
    // Chữ đã gõ còn nguyên để sửa
    expect(field('Mật khẩu mới').value).toBe('mat-khau-moi-9');
    expect(screen.getByText('Chị Hoa', { selector: 'header' })).toBeTruthy();
  });

  it('kiểm mật khẩu mới trước khi gửi', async () => {
    await renderLoaded();
    submitPassword();
    expect(await screen.findByText('Nhập mật khẩu hiện tại')).toBeTruthy();
    expect(screen.getByText('Nhập mật khẩu mới')).toBeTruthy();
    expect(screen.getByText('Nhập lại mật khẩu mới để xác nhận')).toBeTruthy();

    fillPasswords('matkhau1', 'ngan');
    submitPassword();
    expect(await screen.findByText('Mật khẩu mới từ 8 đến 72 ký tự')).toBeTruthy();

    fillPasswords('matkhau1', 'matkhau1');
    submitPassword();
    expect(await screen.findByText('Mật khẩu mới phải khác mật khẩu hiện tại')).toBeTruthy();

    fillPasswords('matkhau1', 'ă'.repeat(37));
    submitPassword();
    expect(await screen.findByText('Mật khẩu mới quá dài, hãy dùng mật khẩu ngắn hơn')).toBeTruthy();

    fillPasswords('matkhau1', 'mat-khau-moi-9', 'mat-khau-moi-8');
    submitPassword();
    expect(await screen.findByText('Mật khẩu nhập lại không khớp')).toBeTruthy();
    expect(callsTo('POST', PASSWORD_PATH)).toHaveLength(0);
  });

  it('lưu xong mở lại màn thì form hiện giá trị đã lưu ngay từ cache', async () => {
    const { queryClient, view } = renderPage();
    await screen.findByDisplayValue('Chị Hoa');
    type('Tên hiển thị', 'Chị Hoa Mới');
    type('Số điện thoại liên hệ (không bắt buộc)', '0987 654 321');
    saveProfile();
    expect(await screen.findByText('Đã lưu thông tin cá nhân')).toBeTruthy();
    view.unmount();

    // Máy chủ không trả lời lần tải lại: form chỉ có dữ liệu trong cache
    holdProfileGets = true;
    renderPage(queryClient);
    expect(await screen.findByDisplayValue('Chị Hoa Mới')).toBeTruthy();
    expect(field('Số điện thoại liên hệ (không bắt buộc)').value).toBe('0987654321');
  });

  it('dữ liệu mới về khi form chưa sửa thì form hiện dữ liệu mới', async () => {
    const queryClient = newQueryClient();
    // Cache còn bản cũ; máy chủ đã có bản mới (sửa ở nơi khác)
    queryClient.setQueryData(['platform', 'profile'], { ...OWNER, displayName: 'Tên cũ', phone: '0900000000' });
    renderPage(queryClient);
    await screen.findByDisplayValue('Tên cũ');
    expect(await screen.findByDisplayValue('Chị Hoa')).toBeTruthy();
    expect(field('Số điện thoại liên hệ (không bắt buộc)').value).toBe('0912345678');
  });

  it('lưu xong thì bảng Nhân viên (khóa platform, users) được tải lại', async () => {
    const queryClient = newQueryClient();
    const usersKey = ['platform', 'users', { page: 0, size: 20 }];
    queryClient.setQueryData(usersKey, { items: [], total: 0, page: 0, size: 20 });
    renderPage(queryClient);
    await screen.findByDisplayValue('Chị Hoa');
    expect(queryClient.getQueryState(usersKey)?.isInvalidated).toBe(false);

    type('Tên hiển thị', 'Chị Hoa Mới');
    saveProfile();
    expect(await screen.findByText('Đã lưu thông tin cá nhân')).toBeTruthy();
    expect(queryClient.getQueryState(usersKey)?.isInvalidated).toBe(true);
  });

  it('số cũ không theo quy tắc số Việt Nam gửi lại y nguyên thì vẫn lưu được tên', async () => {
    profile = { ...STAFF, phone: '028 1234 5678' };
    await renderLoaded();
    expect(field('Số điện thoại liên hệ (không bắt buộc)').value).toBe('028 1234 5678');
    type('Tên hiển thị', 'Minh Anh');
    saveProfile();
    expect(await screen.findByText('Đã lưu thông tin cá nhân')).toBeTruthy();
    expect(bodyOf(callsTo('PUT', ME_PATH)[0])).toEqual({ displayName: 'Minh Anh', phone: '028 1234 5678' });

    // Sửa số cũ thành số khác thì phải là số Việt Nam
    type('Số điện thoại liên hệ (không bắt buộc)', '028 1234 5679');
    saveProfile();
    expect(await screen.findByText('Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số')).toBeTruthy();
    expect(callsTo('PUT', ME_PATH)).toHaveLength(1);
  });

  it('Chủ cửa hàng đăng ký bằng số điện thoại: số liên hệ không đổi số đăng nhập', async () => {
    profile = { ...OWNER, loginKey: '0912345678', email: null };
    await renderLoaded();
    const phone = formItem('Số điện thoại liên hệ (không bắt buộc)');
    expect(within(phone).getByText('Ví dụ 0912 345 678 hoặc +84 912 345 678.')).toBeTruthy();
    expect(within(phone).getByText(/không đổi số điện thoại đăng nhập \(0912345678\)/)).toBeTruthy();
  });

  it('Chủ cửa hàng được nhắc không tự lấy lại mật khẩu đã quên và phiên khác vẫn đăng nhập', async () => {
    await renderLoaded();
    const passwordCard = card('Đổi mật khẩu');
    expect(within(passwordCard).getByText(/không tự lấy lại được/)).toBeTruthy();
    expect(within(passwordCard).getByText(/các phiên trên thiết bị khác cũng vẫn đăng nhập/)).toBeTruthy();
    // Đăng ký bằng email: không có câu về số đăng nhập
    expect(screen.queryByText(/không đổi số điện thoại đăng nhập/)).toBeNull();
  });

  it('Nhân viên không thấy câu dành cho Chủ cửa hàng', async () => {
    profile = { ...STAFF };
    await renderLoaded();
    expect(within(card('Đổi mật khẩu')).queryByText(/không tự lấy lại được/)).toBeNull();
  });

  it('form đổi mật khẩu có ô tên đăng nhập ẩn cho trình quản lý mật khẩu', async () => {
    await renderLoaded();
    const form = field('Mật khẩu hiện tại').closest('form');
    const username = form?.querySelector<HTMLInputElement>('input[autocomplete="username"]');
    expect(username?.value).toBe('chu@cuahang.vn');
    expect(username?.readOnly).toBe(true);
    expect(username?.tabIndex).toBe(-1);
  });
});
