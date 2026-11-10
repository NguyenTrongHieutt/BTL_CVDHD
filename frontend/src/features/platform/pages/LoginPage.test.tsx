import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { configureApiClient } from '../../../shared/api/client';
import type { Me } from '../../../shared/types/platform';
import { LoginPage } from './LoginPage';

const STAFF: Me = { userId: 'u-staff', storeId: 's1', storeName: 'Tạp hóa Lan', role: 'STAFF', displayName: 'Minh' };
const LOGIN_PATH = '/api/v1/platform/auth/login';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function renderPage(state?: unknown) {
  const router = createMemoryRouter(
    [
      { path: '/login', Component: LoginPage },
      { path: '/', element: <h1>Trang chính</h1> },
      { path: '/sales/pos', element: <h1>Bán hàng</h1> },
    ],
    { initialEntries: [{ pathname: '/login', state }] },
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

function logIn(loginKey: string, password: string) {
  fireEvent.change(screen.getByLabelText('Số điện thoại, email hoặc tên đăng nhập'), { target: { value: loginKey } });
  fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

describe('Đăng nhập (FR-44, TK-01)', () => {
  it('đăng nhập xong thì về trang đã định mở, gửi loginKey đã cắt khoảng trắng', async () => {
    fetchMock.mockResolvedValue(json(STAFF));
    const { router, queryClient } = renderPage({ from: '/sales/pos' });
    logIn('  minh.lan ', 'demo1234');

    expect(await screen.findByRole('heading', { name: 'Bán hàng' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/sales/pos');
    const [url, init] = fetchMock.mock.calls[0] ?? [];
    expect(String(url)).toBe(LOGIN_PATH);
    expect(JSON.parse(String(init?.body))).toEqual({ loginKey: 'minh.lan', password: 'demo1234' });
    expect(queryClient.getQueryData(['platform', 'me'])).toEqual(STAFF);
  });

  it.each(['//trang-khac.example/lua-dao', '/\\trang-khac.example', '/login', 42])(
    'không chuyển tới %s, về trang chính',
    async (from) => {
      fetchMock.mockResolvedValue(json(STAFF));
      const { router } = renderPage({ from });
      logIn('minh.lan', 'demo1234');

      expect(await screen.findByRole('heading', { name: 'Trang chính' })).toBeTruthy();
      expect(router.state.location.pathname).toBe('/');
    },
  );

  it('xóa dữ liệu của phiên trước khi đăng nhập', async () => {
    fetchMock.mockResolvedValue(json(STAFF));
    const { queryClient } = renderPage();
    queryClient.setQueryData(['sales', 'orders', {}], { items: ['đơn của người trước'] });
    logIn('minh.lan', 'demo1234');

    expect(await screen.findByRole('heading', { name: 'Trang chính' })).toBeTruthy();
    expect(queryClient.getQueryData(['sales', 'orders', {}])).toBeUndefined();
  });

  it('sai thông tin thì báo lỗi chung và ở lại trang đăng nhập', async () => {
    fetchMock.mockResolvedValue(
      json(
        {
          type: '/errors/platform/invalid-credentials',
          title: 'Đăng nhập không thành công',
          status: 401,
          detail: 'Sai tên đăng nhập hoặc mật khẩu.',
        },
        401,
      ),
    );
    const onUnauthorized = vi.fn<() => void>();
    configureApiClient({ onUnauthorized });
    const { router } = renderPage();
    logIn('minh.lan', 'sai');

    expect(await screen.findByText('Sai thông tin đăng nhập hoặc mật khẩu.')).toBeTruthy();
    // 401 ở đây là sai mật khẩu, không phải hết phiên: không chạy xử lý 401 chung
    expect(onUnauthorized).not.toHaveBeenCalled();
    configureApiClient({ onUnauthorized: null });
    await waitFor(() => expect(screen.getByRole('button', { name: 'Đăng nhập' }).hasAttribute('disabled')).toBe(false));
    expect(router.state.location.pathname).toBe('/login');
  });

  it('có liên kết sang màn đăng ký Cửa hàng', () => {
    renderPage();
    expect(screen.getByRole('link', { name: 'Đăng ký Cửa hàng' }).getAttribute('href')).toBe('/platform/register');
  });
});
