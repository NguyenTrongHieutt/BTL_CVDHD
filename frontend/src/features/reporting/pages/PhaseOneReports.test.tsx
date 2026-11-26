import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
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

it('shows net revenue, order totals, and daily breakdown from Q1 and Q2', async () => {
  setupPage(<RevenueReportPage />, (url) => {
    if (url.startsWith('/api/v1/reporting/q/Q1?')) {
      return Response.json({
        queryId: 'Q1', range: { fromDate: '2026-11-01', toDate: '2026-11-01' },
        params: {}, columns: [], rows: [{
          date: '2026-11-01', grossSales: 50_000, refundTotal: 10_000, revenue: 40_000, discountTotal: 5_000,
        }],
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

it('shows inventory valuation and variant rows returned by Q5', async () => {
  setupPage(<StockReportPage />, (url) => {
    expect(url).toContain('/api/v1/reporting/q/Q5');
    return Response.json({
      queryId: 'Q5', range: { fromDate: '2026-11-20', toDate: '2026-11-20' },
      params: {}, columns: [], rows: [{
        productId: 'p1', productName: 'Sữa tươi', categoryName: 'Đồ uống',
        variantId: 'v1', variantName: null, sku: 'MILK-01',
        stockQuantity: 5, avgUnitCost: 4_000, stockValue: 20_000,
      }],
      summary: { stockQuantity: 5, stockValue: 20_000, variantCount: 1, byCategory: [], byProduct: [] },
    });
  });

  const card = await screen.findByText('Tồn kho theo sản phẩm và biến thể');
  expect(card).toBeTruthy();
  expect(screen.getAllByText('20.000', { exact: false })).toHaveLength(2);
  expect(screen.getByText('MILK-01')).toBeTruthy();
  expect(within(screen.getByRole('table')).getByText('Sữa tươi')).toBeTruthy();
});
