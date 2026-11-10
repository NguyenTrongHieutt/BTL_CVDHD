import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { StoreUser } from '../../../shared/types/platform';
import { StaffPage } from './StaffPage';

const USERS_PATH = '/api/v1/platform/users';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function problem(type: string, status: number): Response {
  return json({ type, title: 'x', status, requestId: 'req-1' }, status);
}

let users: StoreUser[];
/** Tên đăng nhập đã có ở Cửa hàng khác (unique toàn hệ thống). */
let takenElsewhere: string[];
let nextId: number;

const OWNER: StoreUser = {
  userId: 'u-1',
  loginKey: 'chu@cuahang.vn',
  displayName: 'Chủ Hoa',
  role: 'OWNER',
  status: 'ACTIVE',
  phone: null,
  createdAt: '2026-11-01T03:00:00Z',
};

/** Backend giả giữ tài khoản trong bộ nhớ, có phân trang, trả đúng mã lỗi của TK-02. */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, string>) : undefined;

    if (url.pathname === USERS_PATH && method === 'GET') {
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return json({ items: users.slice(page * size, (page + 1) * size), total: users.length, page, size });
    }
    if (url.pathname === USERS_PATH && method === 'POST' && body) {
      const key = body.loginKey.toLowerCase();
      if (takenElsewhere.includes(key) || users.some((u) => u.loginKey === key)) {
        return problem('/errors/platform/login-taken', 409);
      }
      const created: StoreUser = {
        userId: `u-${nextId++}`,
        loginKey: key,
        displayName: body.displayName,
        role: 'STAFF',
        status: 'ACTIVE',
        phone: body.phone ?? null,
        createdAt: '2026-11-10T03:00:00Z',
      };
      users = [OWNER, created, ...users.filter((u) => u.role === 'STAFF')];
      return json(created, 201);
    }
    const match = /^\/api\/v1\/platform\/users\/([^/]+)\/(lock|unlock|reset-password)$/.exec(url.pathname);
    const user = match ? users.find((u) => u.userId === match[1]) : undefined;
    if (match && method === 'POST' && user) {
      if (user.role === 'OWNER') return problem('/errors/platform/owner-protected', 409);
      if (match[2] === 'reset-password') return new Response(null, { status: 204 });
      const changed: StoreUser = { ...user, status: match[2] === 'lock' ? 'LOCKED' : 'ACTIVE' };
      users = users.map((u) => (u.userId === user.userId ? changed : u));
      return json(changed);
    }
    return problem('/errors/common/not-found', 404);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <StaffPage />
      </AntdApp>
    </QueryClientProvider>,
  );
}

function callsTo(method: string, path: string) {
  return fetchMock.mock.calls.filter(
    ([input, init]) => (init?.method ?? 'GET') === method && new URL(String(input), 'http://localhost').pathname === path,
  );
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

function table(): HTMLElement {
  return screen.getByRole('table');
}

function row(loginKey: string): HTMLElement {
  const cell = within(table()).getByText(loginKey);
  const tr = cell.closest('tr');
  if (!tr) throw new Error(`Không thấy dòng ${loginKey}`);
  return tr;
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function fill(label: string, value: string) {
  fireEvent.change(within(dialog()).getByLabelText(label), { target: { value } });
}

/** Hộp xác nhận của `useConfirm`; antd ghi tiêu đề hai lần (một bản cho `aria-labelledby`). */
async function findConfirm(title: string) {
  await screen.findAllByText(title);
}

/** Bấm nút của hộp xác nhận mở sau cùng (hộp cũ còn trong DOM khi jsdom không chạy hiệu ứng đóng). */
function clickConfirmButton(name: string) {
  const buttons = screen.getAllByRole('button', { name });
  fireEvent.click(buttons[buttons.length - 1]);
}

async function openCreateDialog() {
  fireEvent.click(screen.getByRole('button', { name: /Thêm Nhân viên/ }));
  await screen.findByRole('dialog');
}

function fillCreateForm(values: { loginKey: string; displayName: string; password: string; confirm?: string; phone?: string }) {
  fill('Tên đăng nhập', values.loginKey);
  fill('Tên Nhân viên', values.displayName);
  if (values.phone !== undefined) fill('Số điện thoại (không bắt buộc)', values.phone);
  fill('Mật khẩu', values.password);
  fill('Nhập lại mật khẩu', values.confirm ?? values.password);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  users = [
    OWNER,
    {
      userId: 'u-2',
      loginKey: 'nv.lan',
      displayName: 'Lan',
      role: 'STAFF',
      status: 'ACTIVE',
      phone: '0912 345 678',
      createdAt: '2026-11-05T03:00:00Z',
    },
    {
      userId: 'u-3',
      loginKey: 'nv.hoa',
      displayName: 'Hoa',
      role: 'STAFF',
      status: 'LOCKED',
      phone: null,
      createdAt: '2026-11-03T03:00:00Z',
    },
  ];
  takenElsewhere = ['minh'];
  nextId = 4;
  backend();
});

describe('Tài khoản Nhân viên (FR-45, TK-02)', () => {
  it('hiện bảng tài khoản: vai trò, trạng thái, số điện thoại, ngày tạo; dòng Chủ cửa hàng không có thao tác', async () => {
    renderPage();
    expect(await within(table()).findByText('nv.lan')).toBeTruthy();
    expect(within(row('chu@cuahang.vn')).getByText('Chủ cửa hàng')).toBeTruthy();
    expect(within(row('chu@cuahang.vn')).queryAllByRole('button')).toHaveLength(0);

    expect(within(row('nv.lan')).getByText('Nhân viên')).toBeTruthy();
    expect(within(row('nv.lan')).getByText('Đang hoạt động')).toBeTruthy();
    expect(within(row('nv.lan')).getByText('0912 345 678')).toBeTruthy();
    expect(within(row('nv.lan')).getByText('05/11/2026')).toBeTruthy();
    expect(within(row('nv.lan')).getByRole('button', { name: 'Khóa nv.lan' })).toBeTruthy();
    expect(within(row('nv.lan')).getByRole('button', { name: 'Đặt lại mật khẩu nv.lan' })).toBeTruthy();

    expect(within(row('nv.hoa')).getByText('Đã khóa')).toBeTruthy();
    expect(within(row('nv.hoa')).getByText('—')).toBeTruthy();
    expect(within(row('nv.hoa')).getByRole('button', { name: 'Mở khóa nv.hoa' })).toBeTruthy();

    const [call] = callsTo('GET', USERS_PATH);
    const query = new URL(String(call[0]), 'http://localhost').searchParams;
    expect(query.get('page')).toBe('0');
    expect(query.get('size')).toBe('20');
  });

  it('thêm Nhân viên: gửi tên đăng nhập chữ thường đã cắt khoảng trắng, không gửi số điện thoại trống, bảng có dòng mới', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    await openCreateDialog();
    fillCreateForm({ loginKey: '  Minh.Lan ', displayName: '  Minh ', password: 'matkhau1' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(table()).findByText('minh.lan')).toBeTruthy();
    const [post] = callsTo('POST', USERS_PATH);
    expect(bodyOf(post)).toEqual({ loginKey: 'minh.lan', displayName: 'Minh', password: 'matkhau1' });
    expect(new Headers(post[1]?.headers).get('Idempotency-Key')).toBeNull();
    expect(await screen.findByText('Đã tạo tài khoản "minh.lan". Nhân viên đăng nhập bằng tên này.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(callsTo('GET', USERS_PATH).length).toBeGreaterThanOrEqual(2);
  });

  it('thêm Nhân viên có số điện thoại: gửi số đã chuẩn hóa', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    await openCreateDialog();
    fillCreateForm({ loginKey: 'nv.mai', displayName: 'Mai', password: 'matkhau1', phone: ' 0987 654 321 ' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(table()).findByText('nv.mai')).toBeTruthy();
    const [post] = callsTo('POST', USERS_PATH);
    expect(bodyOf(post)).toEqual({ loginKey: 'nv.mai', displayName: 'Mai', password: 'matkhau1', phone: '0987 654 321' });
  });

  it.each([
    ['0912345678', 'Tên đăng nhập không được là số điện thoại hay email (dành cho Chủ cửa hàng)'],
    ['+84912345678', 'Tên đăng nhập không được là số điện thoại hay email (dành cho Chủ cửa hàng)'],
    ['a@b.vn', 'Tên đăng nhập không được là số điện thoại hay email (dành cho Chủ cửa hàng)'],
    ['ab', 'Tên đăng nhập từ 3 đến 50 ký tự'],
    ['_minh', 'Tên đăng nhập chỉ gồm chữ cái không dấu, chữ số và các dấu . _ -, bắt đầu bằng chữ cái hoặc chữ số'],
    ['Lân', 'Tên đăng nhập chỉ gồm chữ cái không dấu, chữ số và các dấu . _ -, bắt đầu bằng chữ cái hoặc chữ số'],
  ])('kiểm phía client: tên đăng nhập "%s" bị từ chối, không gửi', async (loginKey, error) => {
    renderPage();
    await within(table()).findByText('nv.lan');

    await openCreateDialog();
    fillCreateForm({ loginKey, displayName: 'Minh', password: 'matkhau1' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(dialog()).findByText(error)).toBeTruthy();
    expect(callsTo('POST', USERS_PATH)).toHaveLength(0);
  });

  it('kiểm phía client: tên trống, mật khẩu ngắn, quá 72 byte, nhập lại không khớp, số điện thoại sai thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    await openCreateDialog();
    fillCreateForm({ loginKey: 'nv.minh', displayName: '   ', password: 'ngan', confirm: 'khac', phone: 'abc' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(dialog()).findByText('Nhập tên Nhân viên')).toBeTruthy();
    expect(within(dialog()).getByText('Mật khẩu từ 8 đến 72 ký tự')).toBeTruthy();
    expect(within(dialog()).getByText('Mật khẩu nhập lại không khớp')).toBeTruthy();
    expect(within(dialog()).getByText('Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . -')).toBeTruthy();

    // 37 chữ "ă" = 37 ký tự nhưng 74 byte
    fill('Mật khẩu', 'ă'.repeat(37));
    fill('Nhập lại mật khẩu', 'ă'.repeat(37));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(await within(dialog()).findByText('Mật khẩu quá dài, hãy dùng mật khẩu ngắn hơn')).toBeTruthy();

    // Chỉ khoảng trắng: màn đăng nhập không bao giờ nhận, nên là thiếu
    fill('Mật khẩu', ' '.repeat(8));
    fill('Nhập lại mật khẩu', ' '.repeat(8));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(await within(dialog()).findByText('Nhập mật khẩu')).toBeTruthy();

    // Surrogate lẻ: backend mã hóa UTF-8 sẽ đổi thành "?"
    fill('Mật khẩu', 'matkhau\uD800x');
    fill('Nhập lại mật khẩu', 'matkhau\uD800x');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(await within(dialog()).findByText('Mật khẩu có ký tự không hợp lệ')).toBeTruthy();

    expect(callsTo('POST', USERS_PATH)).toHaveLength(0);
  });

  it('chưa có Nhân viên: bảng chỉ có Chủ cửa hàng, kèm gợi ý bấm "Thêm Nhân viên"', async () => {
    users = [OWNER];
    renderPage();
    expect(await within(table()).findByText('chu@cuahang.vn')).toBeTruthy();
    expect(within(table()).getByText('Chưa có Nhân viên nào. Bấm "Thêm Nhân viên" để tạo tài khoản.')).toBeTruthy();
  });

  it('có Nhân viên thì không hiện gợi ý', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    expect(screen.queryByText(/Chưa có Nhân viên nào/)).toBeNull();
  });

  it('tải bảng lỗi: chỉ báo lỗi, không nói "chưa có Nhân viên"', async () => {
    fetchMock.mockImplementation(async () => problem('/errors/common/internal', 500));
    renderPage();
    expect(await screen.findByText('Máy chủ gặp lỗi.')).toBeTruthy();
    expect(screen.queryByText(/Chưa có Nhân viên nào/)).toBeNull();
  });

  it('mất phản hồi rồi gửi lại nhận login-taken: nhắc có thể tài khoản đã được tạo, bảng tải lại', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    const gets = callsTo('GET', USERS_PATH).length;
    // Lần gửi đầu: máy chủ đã tạo nhưng phản hồi mất (lỗi mạng)
    fetchMock.mockImplementationOnce(async () => {
      users = [
        OWNER,
        {
          userId: 'u-9',
          loginKey: 'nv.minh',
          displayName: 'Minh',
          role: 'STAFF',
          status: 'ACTIVE',
          phone: null,
          createdAt: '2026-11-10T03:00:00Z',
        },
        ...users.filter((u) => u.role === 'STAFF'),
      ];
      throw new TypeError('Failed to fetch');
    });

    await openCreateDialog();
    fillCreateForm({ loginKey: 'nv.minh', displayName: 'Minh', password: 'matkhau1' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    // Bảng tải lại cả khi lỗi, nên hiện tài khoản vừa được tạo
    expect(await within(table()).findByText('nv.minh')).toBeTruthy();
    expect(callsTo('GET', USERS_PATH).length).toBeGreaterThan(gets);

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));
    expect(await within(dialog()).findByText(/có thể tài khoản đã được tạo: xem lại bảng Nhân viên/)).toBeTruthy();
    expect(users.filter((u) => u.loginKey === 'nv.minh')).toHaveLength(1);
  });

  it('tên đăng nhập đã có (ở bất kỳ Cửa hàng nào): báo dưới ô tên đăng nhập và giữ hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    await openCreateDialog();
    fillCreateForm({ loginKey: 'MINH', displayName: 'Minh', password: 'matkhau1' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(dialog()).findByText('Tên đăng nhập này đã được dùng. Hãy chọn tên khác.')).toBeTruthy();
    expect(within(dialog()).getByLabelText('Tên đăng nhập').getAttribute('aria-invalid')).toBe('true');
    expect(users).toHaveLength(3);
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    // Yêu cầu kế tiếp (POST tạo) nhận lỗi theo trường
    fetchMock.mockImplementationOnce(async () =>
      json(
        {
          type: '/errors/common/validation',
          title: 'x',
          status: 400,
          errors: [{ field: 'displayName', message: 'Tên Nhân viên có ký tự không hợp lệ' }],
        },
        400,
      ),
    );

    await openCreateDialog();
    fillCreateForm({ loginKey: 'nv.minh', displayName: 'Minh', password: 'matkhau1' });
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Tạo tài khoản' }));

    expect(await within(dialog()).findByText('Tên Nhân viên có ký tự không hợp lệ')).toBeTruthy();
    expect(within(dialog()).getByLabelText('Tên Nhân viên').getAttribute('aria-invalid')).toBe('true');
  });

  it('khóa có xác nhận: bấm Không thì không gửi; đồng ý thì dòng đổi sang Đã khóa', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Khóa nv.lan' }));
    await findConfirm('Khóa tài khoản "Lan"?');
    clickConfirmButton('Không');
    await waitFor(() => expect(within(row('nv.lan')).getByText('Đang hoạt động')).toBeTruthy());
    expect(callsTo('POST', `${USERS_PATH}/u-2/lock`)).toHaveLength(0);

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Khóa nv.lan' }));
    await findConfirm('Khóa tài khoản "Lan"?');
    expect(screen.getAllByText(/bị đăng xuất ngay ở thao tác kế tiếp/).length).toBeGreaterThan(0);
    clickConfirmButton('Khóa');

    await waitFor(() => expect(within(row('nv.lan')).getByText('Đã khóa')).toBeTruthy());
    expect(callsTo('POST', `${USERS_PATH}/u-2/lock`)).toHaveLength(1);
    expect(within(row('nv.lan')).getByRole('button', { name: 'Mở khóa nv.lan' })).toBeTruthy();
    expect(await screen.findByText('Đã khóa tài khoản "Lan"')).toBeTruthy();
  });

  it('mở khóa có xác nhận: dòng đổi sang Đang hoạt động', async () => {
    renderPage();
    await within(table()).findByText('nv.hoa');

    fireEvent.click(within(row('nv.hoa')).getByRole('button', { name: 'Mở khóa nv.hoa' }));
    await findConfirm('Mở khóa tài khoản "Hoa"?');
    clickConfirmButton('Mở khóa');

    await waitFor(() => expect(within(row('nv.hoa')).getByText('Đang hoạt động')).toBeTruthy());
    expect(callsTo('POST', `${USERS_PATH}/u-3/unlock`)).toHaveLength(1);
    expect(await screen.findByText('Đã mở khóa tài khoản "Hoa"')).toBeTruthy();
  });

  it('khóa tài khoản không còn (404): báo lỗi tiếng Việt và tải lại bảng', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    users = users.filter((u) => u.userId !== 'u-2');

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Khóa nv.lan' }));
    await findConfirm('Khóa tài khoản "Lan"?');
    clickConfirmButton('Khóa');

    expect(await screen.findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    await waitFor(() => expect(within(table()).queryByText('nv.lan')).toBeNull());
  });

  it('khóa tài khoản mà máy chủ coi là Chủ cửa hàng (409 owner-protected): báo câu tiếng Việt', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    users = users.map((u) => (u.userId === 'u-2' ? { ...u, role: 'OWNER' } : u));

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Khóa nv.lan' }));
    await findConfirm('Khóa tài khoản "Lan"?');
    clickConfirmButton('Khóa');

    expect(
      await screen.findByText('Không khóa, mở khóa hay đặt lại mật khẩu được cho tài khoản Chủ cửa hàng.'),
    ).toBeTruthy();
    expect(callsTo('POST', `${USERS_PATH}/u-2/lock`)).toHaveLength(1);
  });

  it('đặt lại mật khẩu cho tài khoản không còn (404): báo trong hộp thoại, bảng bỏ dòng đó', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    users = users.filter((u) => u.userId !== 'u-2');

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Đặt lại mật khẩu nv.lan' }));
    await screen.findByRole('dialog');
    fill('Mật khẩu mới', 'mat-khau-moi-9');
    fill('Nhập lại mật khẩu mới', 'mat-khau-moi-9');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đặt lại mật khẩu' }));

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    await waitFor(() => expect(within(table()).queryByText('nv.lan')).toBeNull());
  });

  it('đặt lại mật khẩu cho Chủ cửa hàng (409 owner-protected): báo câu tiếng Việt trong hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');
    users = users.map((u) => (u.userId === 'u-2' ? { ...u, role: 'OWNER' } : u));

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Đặt lại mật khẩu nv.lan' }));
    await screen.findByRole('dialog');
    fill('Mật khẩu mới', 'mat-khau-moi-9');
    fill('Nhập lại mật khẩu mới', 'mat-khau-moi-9');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đặt lại mật khẩu' }));

    expect(
      await within(dialog()).findByText('Không khóa, mở khóa hay đặt lại mật khẩu được cho tài khoản Chủ cửa hàng.'),
    ).toBeTruthy();
  });

  it('đặt lại mật khẩu trong hộp thoại: gửi mật khẩu mới, không gửi mật khẩu cũ', async () => {
    renderPage();
    await within(table()).findByText('nv.lan');

    fireEvent.click(within(row('nv.lan')).getByRole('button', { name: 'Đặt lại mật khẩu nv.lan' }));
    await screen.findByRole('dialog');
    expect(within(dialog()).getByText('Đặt lại mật khẩu: Lan')).toBeTruthy();

    // Nhập lại không khớp, mật khẩu ngắn: không gửi
    fill('Mật khẩu mới', 'ngan');
    fill('Nhập lại mật khẩu mới', 'khac');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đặt lại mật khẩu' }));
    expect(await within(dialog()).findByText('Mật khẩu từ 8 đến 72 ký tự')).toBeTruthy();
    expect(within(dialog()).getByText('Mật khẩu nhập lại không khớp')).toBeTruthy();
    expect(callsTo('POST', `${USERS_PATH}/u-2/reset-password`)).toHaveLength(0);

    fill('Mật khẩu mới', 'mat-khau-moi-9');
    fill('Nhập lại mật khẩu mới', 'mat-khau-moi-9');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đặt lại mật khẩu' }));

    await waitFor(() => expect(callsTo('POST', `${USERS_PATH}/u-2/reset-password`)).toHaveLength(1));
    const [post] = callsTo('POST', `${USERS_PATH}/u-2/reset-password`);
    expect(bodyOf(post)).toEqual({ password: 'mat-khau-moi-9' });
    expect(
      await screen.findByText('Đã đặt lại mật khẩu cho "Lan". Hãy báo mật khẩu mới cho Nhân viên.'),
    ).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
