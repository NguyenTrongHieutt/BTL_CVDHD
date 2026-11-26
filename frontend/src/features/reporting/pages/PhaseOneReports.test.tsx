import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ProductRankingPage } from './ProductRankingPage';
import { ProfitReportPage } from './ProfitReportPage';
import { RevenueReportPage } from './RevenueReportPage';
import { StockReportPage } from './StockReportPage';

function setupPage(page: React.ReactNode, respond: (url: string) => Response) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request) => respond(String(input))));
  render(<QueryClientProvider client={queryClient}>{page}</QueryClientProvider>);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

it('shows net revenue and order totals across pages of the daily report', async () => {
  setupPage(<RevenueReportPage />, (url) => {
    if (url.startsWith('/api/v1/reporting/q/Q1?')) {
      return Response.json({
        queryId: 'Q1', range: { fromDate: '2026-11-01', toDate: '2026-11-15' },
        params: {}, columns: [], rows: [{
          date: '2026-11-01', grossSales: 50_000, refundTotal: 10_000, revenue: 40_000, discountTotal: 5_000,
        }, ...Array.from({ length: 14 }, (_, index) => ({
          date: `2026-11-${String(index + 2).padStart(2, '0')}`, grossSales: 0, refundTotal: 0, revenue: 0, discountTotal: 0,
        }))],
        summary: { grossSales: 50_000, refundTotal: 10_000, revenue: 40_000, discountTotal: 5_000 },
      });
    }
    expect(url).toContain('/api/v1/reporting/q/Q2?');
    return Response.json({
      queryId: 'Q2', range: { fromDate: '2026-11-01', toDate: '2026-11-01' },
      params: {}, columns: [], rows: [{
        date: '2026-11-01', orderCount: 3, cancelledCount: 1, fullyReturnedCount: 0, returnCount: 1,
      }],
      summary: { orderCount: 3, cancelledCount: 1, fullyReturnedCount: 0, returnCount: 1 },
    });
  });

  const revenue = await screen.findByText('Doanh thu theo ngày');
  expect(revenue).toBeTruthy();
  expect(screen.getAllByText('40.000', { exact: false })).toHaveLength(2);
  expect(screen.getAllByText('10.000', { exact: false })).toHaveLength(2);
  expect(screen.getByText('01/11/2026')).toBeTruthy();
  expect(screen.queryByText('15/11/2026')).toBeNull();
  fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
  expect(await screen.findByText('15/11/2026')).toBeTruthy();
  expect(screen.getByText('40.000', { exact: false })).toBeTruthy();
});

it('renders best and slow product rankings returned by Q4', async () => {
  setupPage(<ProductRankingPage />, (url) => {
    const best = url.includes('direction=BEST_SELLING');
    const productName = best ? 'Bánh gạo' : 'Trà thảo mộc';
    return Response.json({
      queryId: 'Q4', range: { fromDate: '2026-11-01', toDate: '2026-11-30' },
      params: {}, columns: [], rows: [{
        rank: 1, productId: best ? 'best' : 'slow', productName, categoryName: 'Đồ uống',
        quantity: best ? 9 : 0, revenue: best ? 90_000 : 0, soldQuantity: best ? 9 : 0, returnedQuantity: 0,
      }],
      summary: { productCount: 1 },
    });
  });

  expect(await screen.findByText('Bánh gạo')).toBeTruthy();
  expect(screen.getByText('Trà thảo mộc')).toBeTruthy();
  expect(screen.getByText('Bán chạy nhất')).toBeTruthy();
  expect(screen.getByText('Bán chậm nhất')).toBeTruthy();
});

it('shows the gross profit totals and a negative day after a return', async () => {
  setupPage(<ProfitReportPage />, (url) => {
    expect(url).toMatch(/\/api\/v1\/reporting\/q\/Q3\?fromDate=\d{4}-\d\d-\d\d&toDate=\d{4}-\d\d-\d\d/);
    return Response.json({
      queryId: 'Q3', range: { fromDate: '2026-11-01', toDate: '2026-11-02' },
      params: {}, columns: [], rows: [
        { date: '2026-11-01', revenue: 100_000, costOfGoodsSold: 60_000, grossProfit: 40_000 },
        { date: '2026-11-02', revenue: -50_000, costOfGoodsSold: -30_000, grossProfit: -20_000 },
      ],
      summary: { revenue: 50_000, costOfGoodsSold: 30_000, grossProfit: 20_000 },
    });
  });

  expect(await screen.findByRole('heading', { name: 'Báo cáo lợi nhuận' })).toBeTruthy();
  const table = await screen.findByRole('table');
  const returnDay = within(table).getByRole('row', { name: /02\/11\/2026/ });
  expect(within(returnDay).getByText(/-20\.000/)).toBeTruthy();
  expect(screen.getAllByText(/20\.000/)).toHaveLength(2);
});

it('keeps stock report totals across server pages and resets the page when searching by SKU', async () => {
  const requests: URLSearchParams[] = [];
  setupPage(<StockReportPage />, (url) => {
    const request = new URL(url, 'http://example.test');
    expect(request.pathname).toBe('/api/v1/reporting/stock');
    requests.push(request.searchParams);
    const page = Number(request.searchParams.get('page'));
    const filtered = request.searchParams.has('q');
    return Response.json({
      page, size: 20, total: filtered ? 1 : 21, asOf: '2026-11-20T05:00:00Z',
      items: [{
        productId: 'p1', productName: 'Sữa tươi', categoryName: 'Đồ uống', isActive: true,
        variantId: 'v1', variantName: null, sku: page === 1 ? 'MILK-21' : 'MILK-01',
        stockQuantity: 5, minQuantity: 3, avgUnitCost: 4_000, stockValue: 20_000,
      }],
      summary: { stockQuantity: 5, stockValue: filtered ? 20_000 : 42_000, variantCount: filtered ? 1 : 21 },
    });
  });

  expect(await screen.findByText('MILK-01')).toBeTruthy();
  expect(screen.getByText(/42\.000/)).toBeTruthy();
  fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
  expect(await screen.findByText('MILK-21')).toBeTruthy();
  expect(requests.at(-1)?.get('page')).toBe('1');
  expect(screen.getByText(/42\.000/)).toBeTruthy();

  const search = screen.getByRole('searchbox', { name: 'Tìm hàng' });
  fireEvent.change(search, { target: { value: 'MILK-01' } });
  fireEvent.keyDown(search, { key: 'Enter', code: 'Enter' });
  await waitFor(() => expect(requests.at(-1)?.get('q')).toBe('MILK-01'));
  expect(requests.at(-1)?.get('page')).toBe('0');
  expect(within(screen.getByRole('table')).getByText('Sữa tươi')).toBeTruthy();
});
