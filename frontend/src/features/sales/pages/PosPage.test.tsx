import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import type { CreateOrderRequest } from '../../../shared/types/sales';
import { cartStorageKey, emptyCart, loadCart, saveCart } from '../cart/cartStorage';
import { PosPage } from './PosPage';

it.each([false, true])('mở giỏ mới ngay khi Kafka nhận lệnh (thử lại sau tải trang: %s)', async (reload) => {
  const user = { storeId: 'store-kafka', storeName: 'Cửa hàng', userId: 'owner-kafka', displayName: 'An', role: 'OWNER' as const };
  const cart = { ...emptyCart(), lines: [{ variantId: 'variant-1', quantity: 1 }] };
  saveCart(user.storeId, user.userId, cart);
  const sent: { key: string | null; body: unknown }[] = [];
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const { pathname } = new URL(String(input), 'http://localhost');
    if (pathname.endsWith('/inventory/low-stock')) return Response.json([]);
    if (pathname.endsWith('/orders/quote')) return Response.json({
      lines: [{ variantId: 'variant-1', productName: 'Bánh gạo', netLineAmount: 18_000 }],
      subtotal: 18_000, lineDiscountTotal: 0, orderDiscount: 0, total: 18_000, warnings: [],
    });
    if (pathname.endsWith('/order-requests/failures')) return Response.json({ total: 1, items: [{
      operationId: cart.idempotencyKey, reason: 'Số lượng tồn đã thay đổi.', createdAt: '2026-11-27T09:00:00Z',
    }] });
    expect(pathname).toBe('/api/v1/sales/orders');
    sent.push({ key: new Headers(init?.headers).get('Idempotency-Key'), body: JSON.parse(String(init?.body)) });
    if (reload && sent.length === 1) return Response.json({ detail: 'Chưa nhận được xác nhận.' }, { status: 503 });
    return Response.json({ status: 'ACCEPTED', operationId: cart.idempotencyKey }, { status: 202 });
  });
  vi.stubGlobal('fetch', fetchMock);
  function mount() {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    return render(<QueryClientProvider client={queryClient}>
      <CurrentUserContext value={user}><PosPage /></CurrentUserContext>
    </QueryClientProvider>);
  }
  let view = mount();
  try {
    const confirm = await screen.findByRole<HTMLButtonElement>('button', { name: 'Xác nhận đã nhận tiền' });
    await waitFor(() => expect(confirm.disabled).toBe(false));
    fireEvent.click(confirm);
    if (reload) {
      await screen.findByRole('button', { name: 'Thử lại xác nhận' });
      view.unmount();
      view = mount();
      fireEvent.click(await screen.findByRole('button', { name: 'Thử lại xác nhận' }));
    }
    expect(await screen.findByText('Đã tiếp nhận đơn')).toBeTruthy();
    expect(sent).toHaveLength(reload ? 2 : 1);
    expect(sent.at(-1)).toEqual(sent[0]);
    expect(screen.getByText(cart.idempotencyKey)).toBeTruthy();
    expect(screen.getByText('Giỏ hàng đang trống.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Xem hóa đơn' })).toBeNull();
    expect(loadCart(user.storeId, user.userId).idempotencyKey).not.toBe(cart.idempotencyKey);
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/order-requests/'))).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: 'Đơn cần xử lý' }));
    expect(await screen.findByText('Số lượng tồn đã thay đổi.')).toBeTruthy();
  } finally {
    view.unmount();
    localStorage.removeItem(cartStorageKey(user.storeId, user.userId));
    vi.unstubAllGlobals();
  }
});

it.each([
  { role: 'OWNER' as const, configured: false },
  { role: 'STAFF' as const, configured: false },
  { role: 'OWNER' as const, configured: true },
])('hiển thị trạng thái VietQR cho $role khi cấu hình là $configured', async ({ role, configured }) => {
  const user = { storeId: 'store-vietqr', storeName: 'Cửa hàng', userId: role, displayName: 'An', role };
  saveCart(user.storeId, user.userId, { ...emptyCart(), lines: [{ variantId: 'variant-1', quantity: 1 }] });
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (url) => {
    if (String(url).endsWith('/inventory/low-stock')) return Response.json([]);
    return Response.json({
      lines: [{ variantId: 'variant-1', productName: 'Bánh gạo', netLineAmount: 18_000 }],
      subtotal: 18_000, lineDiscountTotal: 0, orderDiscount: 0, total: 18_000,
      warnings: [], bankTransferAvailable: configured,
    });
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter><CurrentUserContext value={user}><PosPage /></CurrentUserContext></MemoryRouter>
    </QueryClientProvider>,
  );
  try {
    await screen.findAllByText(/18\.000/);
    const method = screen.getByRole('combobox', { name: 'Phương thức thanh toán' });
    fireEvent.mouseDown(method);
    const option = await screen.findByRole('option', { name: 'Chuyển khoản' });
    expect(option.getAttribute('aria-disabled') === 'true').toBe(!configured);
    expect(Boolean(screen.queryByText(/^Chưa thiết lập tài khoản nhận chuyển khoản\./))).toBe(!configured);
    expect(screen.queryByRole('link', { name: 'Cài đặt VietQR' })?.getAttribute('href'))
      .toBe(!configured && role === 'OWNER' ? '/platform/store' : undefined);
  } finally {
    view.unmount();
    localStorage.removeItem(cartStorageKey(user.storeId, user.userId));
    vi.unstubAllGlobals();
  }
});

it('tính lại tiền sau lỗi mạng với nguyên giỏ hàng và khóa thanh toán', async () => {
  const user = { storeId: 'store-pos', storeName: 'Cửa hàng', userId: 'staff-pos', displayName: 'An', role: 'STAFF' as const };
  const cart = { ...emptyCart(), lines: [{ variantId: 'variant-1', quantity: 2 }] };
  saveCart(user.storeId, user.userId, cart);
  const calls: (RequestInit | undefined)[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/inventory/low-stock')) return Response.json([]);
    expect(url).toMatch(/\/sales\/orders\/quote$/);
    calls.push(init);
    if (calls.length === 1) return Response.json({ type: 'about:blank', status: 503 }, { status: 503 });
    return Response.json({
      lines: [{ variantId: 'variant-1', productName: 'Bánh gạo', netLineAmount: 36_000 }],
      subtotal: 36_000, lineDiscountTotal: 0, orderDiscount: 0, total: 36_000, warnings: [],
    });
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <CurrentUserContext value={user}><PosPage /></CurrentUserContext>
    </QueryClientProvider>,
  );
  try {
    fireEvent.click(await screen.findByRole('button', { name: 'Thử lại tính tiền' }));
    expect(await screen.findByText('Khách phải trả')).toBeTruthy();
    expect(calls.map(call => call?.body)).toEqual([
      JSON.stringify({ lines: cart.lines, paymentMethod: 'CASH' }),
      JSON.stringify({ lines: cart.lines, paymentMethod: 'CASH' }),
    ]);
    expect(calls.map(call => new Headers(call?.headers).get('Idempotency-Key'))).toEqual([
      cart.idempotencyKey, cart.idempotencyKey,
    ]);
  } finally {
    view.unmount();
    localStorage.removeItem(cartStorageKey(user.storeId, user.userId));
  }
});

it.each([
  { points: 6, total: 24_000, earned: 2, received: undefined, confirm: 'Xác nhận đã nhận tiền' },
  { points: 6, total: 24_000, earned: 2, received: 30_000, confirm: 'Xác nhận đã nhận tiền' },
  { points: 18, total: 0, earned: 0, received: undefined, confirm: 'Hoàn tất đơn' },
])('dùng $points điểm khi trả $total đồng, tiền khách đưa tùy chọn $received', async ({ points, total, earned, received, confirm }) => {
  const user = { storeId: 'store-kh04', storeName: 'Cửa hàng', userId: 'staff-kh04', displayName: 'An', role: 'STAFF' as const };
  const customerId = 'customer-1';
  const cart = { ...emptyCart(), customerId, lines: [{ variantId: 'variant-1', quantity: 2 }] };
  saveCart(user.storeId, user.userId, cart);
  const account = { balance: 22, earnAmountPerPoint: 10_000, pointValue: 2_000 };
  const confirmed: CreateOrderRequest[] = [];
  const keys: (string | null)[] = [];
  const quotes: CreateOrderRequest[] = [];
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.pathname === '/api/v1/inventory/low-stock') return Response.json([]);
    if (url.pathname === `/api/v1/catalog/customers/${customerId}`) {
      return Response.json({ customerId, phone: '0912345678', name: 'Chị Lan', note: null, createdAt: '2026-11-26T04:00:00Z' });
    }
    if (url.pathname === `/api/v1/sales/customers/${customerId}/loyalty`) return Response.json(account);
    const body = JSON.parse(String(init?.body)) as CreateOrderRequest;
    if (url.pathname === '/api/v1/sales/orders/quote') {
      quotes.push(body);
      const pointsRedeemed = body.pointsRedeemed ?? 0;
      const pointsDiscount = pointsRedeemed * account.pointValue;
      const payable = 36_000 - pointsDiscount;
      return Response.json({
        lines: [{ variantId: 'variant-1', productName: 'Bánh gạo', netLineAmount: payable }],
        subtotal: 36_000, lineDiscountTotal: 0, orderDiscount: 0, pointsDiscount, total: payable, warnings: [],
        change: body.cashReceived === undefined ? null : Math.max(0, body.cashReceived - payable),
        loyalty: { ...account, pointsRedeemed, pointsEarned: Math.floor(payable / account.earnAmountPerPoint),
          maxRedeemablePoints: 18 },
      });
    }
    if (url.pathname === '/api/v1/sales/orders') {
      confirmed.push(body);
      keys.push(new Headers(init?.headers).get('Idempotency-Key'));
      return Response.json({ orderId: 'order-kh04', orderCode: 'DH-000042', pointsEarned: earned, pointsRedeemed: points }, { status: 201 });
    }
    return Response.json({ status: 404 }, { status: 404 });
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <CurrentUserContext value={user}><PosPage /></CurrentUserContext>
    </QueryClientProvider>,
  );
  try {
    expect(await screen.findByText('22 điểm', { exact: true })).toBeTruthy();
    const input = await screen.findByRole('spinbutton', { name: 'Điểm dùng cho đơn này' });
    fireEvent.change(input, { target: { value: String(points) } });
    await waitFor(() => expect(quotes.at(-1)?.pointsRedeemed).toBe(points));
    expect(screen.getByText('Giảm do dùng điểm')).toBeTruthy();
    expect(screen.getByText('Điểm dự kiến cộng')).toBeTruthy();
    expect(screen.queryByRole('spinbutton', { name: 'Tiền khách đưa' })).toBeNull();
    let blockedWhenShort = false;
    if (received !== undefined) {
      fireEvent.click(screen.getByRole('button', { name: 'Tính tiền thừa' }));
      const cash = screen.getByRole('spinbutton', { name: 'Tiền khách đưa' });
      fireEvent.focus(cash);
      fireEvent.change(cash, { target: { value: '1000' } });
      await screen.findByText('Tiền khách đưa chưa đủ.');
      blockedWhenShort = screen.getByRole<HTMLButtonElement>('button', { name: confirm }).disabled;
      fireEvent.change(cash, { target: { value: String(received) } });
      fireEvent.blur(cash);
    }
    expect(blockedWhenShort).toBe(received !== undefined);
    await waitFor(() => expect(quotes.at(-1)?.cashReceived).toBe(received));
    await waitFor(() => expect(screen.getByRole<HTMLButtonElement>('button', { name: confirm }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: confirm }));
    expect(await screen.findByText(/Đã bán hàng · DH-000042/)).toBeTruthy();
    expect(await screen.findByText(new RegExp(`Đã dùng ${points} điểm`))).toBeTruthy();
    expect(confirmed).toEqual([expect.objectContaining({ customerId, pointsRedeemed: points,
      cashReceived: received ?? total, quotedTotal: total })]);
    expect(keys).toEqual([cart.idempotencyKey]);
  } finally {
    view.unmount();
    localStorage.removeItem(cartStorageKey(user.storeId, user.userId));
    vi.unstubAllGlobals();
  }
});
