import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import { OrdersPage } from './OrdersPage';

it('mở được hóa đơn của đơn đã hủy và giữ bộ lọc khi quay lại', async () => {
  const order = { orderId: 'order-1', orderCode: 'DH-000042', createdAt: '2026-11-25T00:00:00Z',
    status: 'CANCELLED', sellerName: 'An', total: 36_000, canCancel: false };
  const filteredCodes: (string | null)[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = new URL(input, 'http://localhost');
    if (url.pathname.endsWith('/sales/orders/sellers')) return Response.json([]);
    if (url.pathname.endsWith('/sales/orders')) {
      filteredCodes.push(url.searchParams.get('orderCode'));
      return Response.json({ items: [order], page: 0, size: 20, total: 1 });
    }
    expect(url.pathname).toBe('/api/v1/sales/orders/order-1/receipt');
    return Response.json({ store: { name: 'Tiệm Bánh Gạo' }, order: { ...order, subtotal: 36_000, discount: 0 }, lines: [] });
  }));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><MemoryRouter><App><OrdersPage /></App></MemoryRouter></QueryClientProvider>);

  const filter = screen.getByRole('searchbox', { name: 'Mã đơn hàng' });
  fireEvent.change(filter, { target: { value: order.orderCode } });
  fireEvent.keyDown(filter, { key: 'Enter', code: 'Enter', keyCode: 13 });
  await waitFor(() => expect(filteredCodes.at(-1)).toBe(order.orderCode));
  const trigger = await screen.findByRole('button', { name: 'Xem hóa đơn ' + order.orderCode });
  fireEvent.click(trigger);
  expect(await screen.findByText('Tiệm Bánh Gạo')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'In hóa đơn' })).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: 'Quay lại đơn hàng' }));
  expect((screen.getByRole('searchbox', { name: 'Mã đơn hàng' }) as HTMLInputElement).value).toBe(order.orderCode);
  await waitFor(() => expect(document.activeElement).toBe(trigger));
});
