import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import { cartStorageKey, emptyCart, saveCart } from '../cart/cartStorage';
import { PosPage } from './PosPage';

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
