import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';

function setup(dashboard: () => Response, lowStock?: (page: number) => Response) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url === '/api/v1/reporting/dashboard') return dashboard();
    const request = new URL(url, 'http://example.test');
    expect(request.pathname).toBe('/api/v1/inventory/low-stock/page');
    expect(request.searchParams.get('size')).toBe('5');
    const page = Number(request.searchParams.get('page'));
    return lowStock?.(page) ?? Response.json({ items: [], total: 0, page, size: 5 });
  }));
  render(<QueryClientProvider client={queryClient}><DashboardPage /></QueryClientProvider>);
}

it('shows sales and the product ranking returned by the server', async () => {
  setup(() => Response.json({
    date: '2026-11-20', today: { revenue: 50_000, orderCount: 2 },
    top5Last7Days: [{ productId: 'rice', name: 'Bánh gạo', quantity: 5, revenue: 50_000 }],
  }));
  const metrics = await screen.findByRole('region', { name: 'Kết quả hôm nay' });
  expect(within(metrics).getByText('50.000', { exact: false })).toBeTruthy();
  expect(within(metrics).getByText('2')).toBeTruthy();
  expect(screen.getByText('20/11/2026')).toBeTruthy();
  const ranking = screen.getByRole('region', { name: 'Bán chạy trong 7 ngày' });
  expect(within(ranking).getByRole('listitem').textContent).toContain('Bánh gạo');
  expect(within(ranking).getByText('Đã bán 5')).toBeTruthy();
});

it('yêu cầu trang cảnh báo tồn thấp tiếp theo từ máy chủ', async () => {
  const lowStock = vi.fn<(page: number) => Response>((page) => Response.json({
    items: [{ variantId: `v-${page}`, productName: `Hàng dưới ngưỡng ${page + 1}`, quantity: 0, minQuantity: 3 }],
    total: 6, page, size: 5,
  }));
  setup(() => Response.json({
    date: '2026-11-20', today: { revenue: 0, orderCount: 0 }, top5Last7Days: [],
  }), lowStock);
  await screen.findByText('Hàng dưới ngưỡng 1');
  fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
  expect(await screen.findByText('Hàng dưới ngưỡng 2')).toBeTruthy();
  expect(lowStock.mock.calls).toEqual([[0], [1]]);
});

it('keeps inventory alerts available and retries dashboard data on failure', async () => {
  const dashboard = vi.fn<() => Response>()
    .mockReturnValueOnce(Response.json({ title: 'Không tải được', status: 503 }, { status: 503 }))
    .mockReturnValueOnce(Response.json({
      date: '2026-11-20', today: { revenue: 0, orderCount: 0 }, top5Last7Days: [],
    }));
  setup(dashboard);
  await screen.findByRole('alert');
  expect(screen.getByText('Cảnh báo tồn thấp')).toBeTruthy();
  expect(screen.queryByText('Chưa có sản phẩm bán ra trong 7 ngày.')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Tải lại tổng quan' }));
  expect(await screen.findByText('Chưa có sản phẩm bán ra trong 7 ngày.')).toBeTruthy();
  expect(dashboard).toHaveBeenCalledTimes(2);
});
