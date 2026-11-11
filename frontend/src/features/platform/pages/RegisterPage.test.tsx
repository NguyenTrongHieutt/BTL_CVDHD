import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../shared/types/platform';
import { RegisterPage } from './RegisterPage';

const OWNER: Me = { userId: 'u-owner', storeId: 's-new', storeName: 'Tạp hóa Hoa', role: 'OWNER', displayName: 'Hoa' };
const STORES_PATH = '/api/v1/platform/stores';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function renderPage() {
  const router = createMemoryRouter(
    [
      { path: '/platform/register', Component: RegisterPage },
      { path: '/', element: <h1>Trang chính</h1> },
      { path: '/login', element: <h1>Đăng nhập</h1> },
    ],
    { initialEntries: ['/platform/register'] },
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <RouterProvider router={router} />
      </AntdApp>
    </QueryClientProvider>,
  );
  return { router, queryClient };
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function fillValidForm() {
  type('Tên Cửa hàng', 'Tạp hóa Hoa');
  type('Tên của bạn', 'Hoa');
  type('Số điện thoại hoặc email', ' 0912345678 ');
  type('Mật khẩu', 'matkhau123');
  type('Nhập lại mật khẩu', 'matkhau123');
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: 'Đăng ký' }));
}

function registerCalls() {
  return fetchMock.mock.calls.filter(([url]) => String(url) === STORES_PATH);
}

function idempotencyKeyOf(call: Parameters<typeof fetch>): string | null {
  return new Headers(call[1]?.headers).get('Idempotency-Key');
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

describe('Đăng ký Cửa hàng (FR-43, TK-00)', () => {
  it('kiểm tra phía client trước khi gửi', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    submit();
    expect(await screen.findByText('Nhập tên Cửa hàng')).toBeTruthy();
    expect(screen.getByText('Nhập tên của bạn')).toBeTruthy();
    expect(screen.getByText('Nhập số điện thoại hoặc email')).toBeTruthy();
    expect(screen.getByText('Nhập mật khẩu')).toBeTruthy();
    expect(screen.getByText('Nhập lại mật khẩu để xác nhận')).toBeTruthy();

    type('Tên Cửa hàng', 'Tạp hóa Hoa');
    type('Tên của bạn', 'Hoa');
    type('Số điện thoại hoặc email', '091234567');
    type('Mật khẩu', 'ngan');
    type('Nhập lại mật khẩu', 'khac-han');
    submit();
    expect(
      await screen.findByText('Nhập số điện thoại (0xxxxxxxxx hoặc +84xxxxxxxxx) hoặc email hợp lệ'),
    ).toBeTruthy();
    expect(screen.getByText('Mật khẩu từ 8 đến 72 ký tự')).toBeTruthy();
    expect(screen.getByText('Mật khẩu nhập lại không khớp')).toBeTruthy();

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('nhận cả email và số +84', async () => {
    fetchMock.mockResolvedValue(json(OWNER, 201));
    renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    type('Số điện thoại hoặc email', '+84912345678');
    submit();
    await waitFor(() => expect(registerCalls()).toHaveLength(1));
    expect(JSON.parse(String(registerCalls()[0][1]?.body))).toMatchObject({ loginKey: '+84912345678' });
  });

  it('đăng ký xong thì lưu Me và vào thẳng ứng dụng', async () => {
    fetchMock.mockResolvedValue(json(OWNER, 201));
    const { router, queryClient } = renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    type('Số điện thoại hoặc email', ' Hoa@Mail.com ');
    submit();

    expect(await screen.findByRole('heading', { name: 'Trang chính' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/');
    expect(queryClient.getQueryData(['platform', 'me'])).toEqual(OWNER);
    const [call] = registerCalls();
    expect(call[1]?.method).toBe('POST');
    expect(JSON.parse(String(call[1]?.body))).toEqual({
      storeName: 'Tạp hóa Hoa',
      loginKey: 'Hoa@Mail.com',
      password: 'matkhau123',
      displayName: 'Hoa',
    });
    expect(idempotencyKeyOf(call)).toMatch(UUID_PATTERN);
  });

  it('lỗi theo trường từ errors[] hiện dưới ô tương ứng', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          type: '/errors/common/validation',
          title: 'Dữ liệu không hợp lệ',
          status: 400,
          errors: [{ field: 'password', message: 'Mật khẩu quá dài: tối đa 72 byte' }],
        },
        400,
      ),
    );
    const { router } = renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    submit();

    expect(await screen.findByText('Mật khẩu quá dài: tối đa 72 byte')).toBeTruthy();
    expect(screen.queryByText('Dữ liệu nhập chưa hợp lệ.')).toBeNull();
    expect(router.state.location.pathname).toBe('/platform/register');
  });

  it('số điện thoại đã dùng: báo ở ô, gợi ý đăng nhập, lần gửi sau dùng Idempotency-Key mới (UJ-1)', async () => {
    fetchMock.mockResolvedValueOnce(
      json({ type: '/errors/platform/login-taken', title: 'Tên đăng nhập đã được dùng', status: 409 }, 409),
    );
    fetchMock.mockResolvedValueOnce(json(OWNER, 201));
    const { router } = renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    submit();
    expect(await screen.findByText('Số điện thoại hoặc email này đã được dùng')).toBeTruthy();
    expect(screen.getByLabelText('Số điện thoại hoặc email').getAttribute('aria-invalid')).toBe('true');
    const alert = screen.getByRole('alert');
    expect(alert.textContent).toContain('Số điện thoại hoặc email này đã có tài khoản.');
    expect(within(alert).getByRole('link', { name: 'Đăng nhập' }).getAttribute('href')).toBe('/login');
    expect(router.state.location.pathname).toBe('/platform/register');

    type('Số điện thoại hoặc email', '0987654321');
    submit();
    expect(await screen.findByRole('heading', { name: 'Trang chính' })).toBeTruthy();
    const [first, second] = registerCalls();
    expect(idempotencyKeyOf(first)).not.toBe(idempotencyKeyOf(second));
  });

  it('khóa đã dùng với nội dung khác: báo Cửa hàng có thể đã tạo và gợi ý đăng nhập', async () => {
    fetchMock.mockResolvedValue(
      json({ type: '/errors/common/idempotency-key-reuse', title: 'Idempotency-Key đã dùng', status: 409 }, 409),
    );
    const { router } = renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    submit();
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('Cửa hàng có thể đã được tạo ở lần gửi trước. Hãy thử đăng nhập.');
    expect(alert.textContent).not.toContain('Chứng từ');
    fireEvent.click(within(alert).getByRole('link', { name: 'Đăng nhập' }));
    expect(await screen.findByRole('heading', { name: 'Đăng nhập' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('mất mạng: gửi lại giữ nguyên Idempotency-Key (AD-7)', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    fetchMock.mockResolvedValueOnce(json(OWNER, 200));
    renderPage();
    await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' });

    fillValidForm();
    submit();
    expect(await screen.findByText('Không kết nối được máy chủ.')).toBeTruthy();

    submit();
    expect(await screen.findByRole('heading', { name: 'Trang chính' })).toBeTruthy();
    const [first, second] = registerCalls();
    expect(idempotencyKeyOf(first)).toMatch(UUID_PATTERN);
    expect(idempotencyKeyOf(second)).toBe(idempotencyKeyOf(first));
  });
});
