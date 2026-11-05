import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../shared/types/platform';
import { appFeatures } from './features';
import { buildAppRoutes } from './router';

const OWNER: Me = { userId: 'u-owner', storeId: 's1', storeName: 'Tạp hóa Chị Lan', role: 'OWNER', displayName: 'Chị Lan' };
const STAFF: Me = { userId: 'u-staff', storeId: 's1', storeName: 'Tạp hóa Chị Lan', role: 'STAFF', displayName: 'Minh' };

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const UNAUTHORIZED = { type: '/errors/common/unauthorized', title: 'Chưa đăng nhập', status: 401 };

/** Backend giả: `GET /me` trả `me` (hoặc 401 khi `null`). */
function backend(me: Me | null) {
  fetchMock.mockImplementation(async (input) => {
    const url = String(input);
    if (url === '/api/v1/platform/me') return me ? json(me) : json(UNAUTHORIZED, 401);
    return json({ type: 'about:blank', title: 'Not Found', status: 404 }, 404);
  });
}

function renderApp(path: string) {
  const router = createMemoryRouter(buildAppRoutes(appFeatures), { initialEntries: [path] });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <RouterProvider router={router} />
      </AntdApp>
    </QueryClientProvider>,
  );
  return { router, queryClient };
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

describe('RequireAuth', () => {
  it('chưa đăng nhập (GET /me trả 401) thì chuyển về /login và nhớ trang đang mở', async () => {
    backend(null);
    const { router } = renderApp('/inventory/stock?filter=low');

    expect(await screen.findByRole('heading', { name: 'Đăng nhập' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: '/inventory/stock?filter=low' });
  });

  it('đã đăng nhập thì hiện màn hình cùng tên Cửa hàng và người dùng', async () => {
    backend(STAFF);
    renderApp('/sales/pos');

    expect(await screen.findByRole('heading', { name: 'Bán hàng' })).toBeTruthy();
    expect(screen.getByText('Tạp hóa Chị Lan')).toBeTruthy();
    expect(screen.getByText('Nhân viên')).toBeTruthy();
  });

  it('route công khai không gọi GET /me', async () => {
    backend(null);
    renderApp('/platform/register');

    expect(await screen.findByRole('heading', { name: 'Đăng ký Cửa hàng' })).toBeTruthy();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('RequireRole (PRD 4.7)', () => {
  it('Nhân viên mở màn chỉ dành cho Chủ cửa hàng thì thấy trang 403', async () => {
    backend(STAFF);
    renderApp('/reporting/dashboard');

    expect(await screen.findByText('Không có quyền')).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Trang tổng quan' })).toBeNull();
  });

  it('Chủ cửa hàng mở được màn báo cáo', async () => {
    backend(OWNER);
    renderApp('/reporting/dashboard');

    expect(await screen.findByRole('heading', { name: 'Trang tổng quan' })).toBeTruthy();
    expect(screen.queryByText('Không có quyền')).toBeNull();
  });

  it('menu của Nhân viên không có Báo cáo, Trợ lý AI, Phiếu nhập', async () => {
    backend(STAFF);
    renderApp('/sales/pos');
    await screen.findByRole('heading', { name: 'Bán hàng' });

    const menu = screen.getByRole('menu');
    expect(menu.textContent).toContain('Đơn hàng');
    expect(menu.textContent).toContain('Tồn kho');
    expect(menu.textContent).not.toContain('Báo cáo');
    expect(menu.textContent).not.toContain('Trợ lý AI');
    expect(menu.textContent).not.toContain('Phiếu nhập');
  });
});

describe('trang chính theo vai trò', () => {
  it('Chủ cửa hàng: / → /reporting/dashboard', async () => {
    backend(OWNER);
    const { router } = renderApp('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/reporting/dashboard'));
  });

  it('Nhân viên: / → /sales/pos', async () => {
    backend(STAFF);
    const { router } = renderApp('/');
    await waitFor(() => expect(router.state.location.pathname).toBe('/sales/pos'));
  });
});

describe('đăng nhập (AD-9)', () => {
  it('đăng nhập xong thì lưu Me vào cache và quay lại trang định mở', async () => {
    let loggedIn = false;
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === '/api/v1/platform/me') return loggedIn ? json(STAFF) : json(UNAUTHORIZED, 401);
      if (url === '/api/v1/platform/auth/login' && init?.method === 'POST') {
        loggedIn = true;
        return json(STAFF);
      }
      return json({ type: 'about:blank', title: 'Not Found', status: 404 }, 404);
    });
    const { router, queryClient } = renderApp('/sales/orders');

    await screen.findByRole('heading', { name: 'Đăng nhập' });
    fireEvent.change(screen.getByLabelText('Số điện thoại, email hoặc tên đăng nhập'), { target: { value: '  minh ' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'matkhau' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByRole('heading', { name: 'Đơn hàng' })).toBeTruthy();
    expect(router.state.location.pathname).toBe('/sales/orders');
    expect(queryClient.getQueryData(['platform', 'me'])).toEqual(STAFF);
    const loginCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/v1/platform/auth/login');
    expect(JSON.parse(String(loginCall?.[1]?.body))).toEqual({ loginKey: 'minh', password: 'matkhau' });
  });

  it('sai mật khẩu thì báo lỗi chung, ở lại trang đăng nhập', async () => {
    fetchMock.mockImplementation(async (input) => {
      const url = String(input);
      if (url === '/api/v1/platform/auth/login') {
        return json({ type: '/errors/platform/invalid-credentials', title: 'Đăng nhập không thành công', status: 401 }, 401);
      }
      return json(UNAUTHORIZED, 401);
    });
    const { router } = renderApp('/login');

    fireEvent.change(await screen.findByLabelText('Số điện thoại, email hoặc tên đăng nhập'), { target: { value: 'x' } });
    fireEvent.change(screen.getByLabelText('Mật khẩu'), { target: { value: 'y' } });
    fireEvent.click(screen.getByRole('button', { name: 'Đăng nhập' }));

    expect(await screen.findByText('Sai thông tin đăng nhập hoặc mật khẩu.')).toBeTruthy();
    expect(router.state.location.pathname).toBe('/login');
  });
});
