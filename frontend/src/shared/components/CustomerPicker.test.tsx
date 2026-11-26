import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { CustomerSummary } from '../types/catalog';
import { CustomerPicker } from './CustomerPicker';

it('chuyển trang khách hàng và về trang đầu khi đổi tìm kiếm', async () => {
  const makeCustomer = (index: number) => ({
    customerId: String(index), name: 'Khách ' + index, phone: '090000000' + index,
  });
  const calls: URLSearchParams[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const { searchParams } = new URL(input, 'http://localhost');
    calls.push(searchParams);
    const page = Number(searchParams.get('page'));
    const q = searchParams.get('q');
    const items = q || page > 0 ? [makeCustomer(9)] : Array.from({ length: 8 }, (_, i) => makeCustomer(i + 1));
    return Response.json({ items, page, size: 8, total: q ? 1 : 9 });
  }));
  const onChange = vi.fn<(customer: CustomerSummary | null) => void>();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <CustomerPicker onChange={onChange} />
    </QueryClientProvider>,
  );
  try {
    fireEvent.click(screen.getByRole('button', { name: 'Tìm và chọn khách' }));
    const dialog = await screen.findByRole('dialog', { name: 'Chọn khách hàng' });
    expect(await within(dialog).findByRole('button', { name: /Khách 1 ·/ })).toBeTruthy();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Trang sau' }));
    const customer = await within(dialog).findByRole('button', { name: /Khách 9 ·/ });
    expect(within(dialog).getByText('Trang 2/2')).toBeTruthy();
    expect(calls.some(params => params.get('page') === '1')).toBe(true);
    expect(customer).toBeTruthy();
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Tên hoặc số điện thoại' }), {
      target: { value: 'Khách 9' },
    });
    await waitFor(() => expect(calls.at(-1)?.get('q')).toBe('Khách 9'));
    expect(calls.at(-1)?.get('page')).toBe('0');
    fireEvent.click(await within(dialog).findByRole('button', { name: /Khách 9 ·/ }));
    expect(onChange).toHaveBeenCalledWith(makeCustomer(9));
  } finally {
    view.unmount();
    vi.unstubAllGlobals();
  }
});
