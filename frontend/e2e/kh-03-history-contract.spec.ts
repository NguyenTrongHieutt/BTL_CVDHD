import { expect, test } from '@playwright/test';
import type { CustomerOrder } from '../src/features/catalog/api/customerHistory';

if (process.env.KH03_BROWSER_CHANNEL) test.use({ channel: process.env.KH03_BROWSER_CHANNEL });

const purchasedAt = '2026-11-10T03:00:00Z';
const customers = [
  { customerId: '00000000-0000-4000-8000-000000000001', name: 'Anh An', phone: '0912222222' },
  { customerId: '00000000-0000-4000-8000-000000000002', name: 'Chị Hà', phone: '0913333333' },
].map(customer => ({ ...customer, note: null, createdAt: '2026-10-01T00:00:00Z' }));

function order(code: string, total: number): CustomerOrder {
  return { orderId: code, orderCode: code, createdAt: purchasedAt, total, status: 'COMPLETED', sellerName: 'Lan' };
}

const anOrders = Array.from({ length: 21 }, (_, i) => order(`AN-${i + 1}`, 10_000));
anOrders[1].status = 'CANCELLED';
const haOrders = [order('HA-1', 85_000)];

// An has 21 history rows. One sale was cancelled, and AN-1 was refunded 4,000 of its 10,000.
const histories = [
  { orders: anOrders, summary: { orderCount: 20, totalSpent: 196_000, lastPurchaseAt: purchasedAt } },
  { orders: haOrders, summary: { orderCount: 1, totalSpent: 85_000, lastPurchaseAt: purchasedAt } },
];

for (const role of ['OWNER', 'STAFF'] as const) {
  test(`KH-03 mocked sales API contract — ${role} views and pages customer purchase history`, async ({ page }) => {
    await page.route('**/api/v1/**', async route => {
      const { pathname, searchParams } = new URL(route.request().url());
      expect(route.request().method()).toBe('GET');
      if (pathname === '/api/v1/platform/me') {
        return route.fulfill({ json: { userId: role, storeId: 'store', storeName: 'Cửa hàng', displayName: 'Lan', role } });
      }
      if (pathname === '/api/v1/catalog/customers') {
        return route.fulfill({ json: { items: customers, total: 2, page: 0, size: 20 } });
      }
      for (const [i, customer] of customers.entries()) {
        const { orders, summary } = histories[i];
        if (pathname === `/api/v1/sales/customers/${customer.customerId}/purchase-summary`) {
          return route.fulfill({ json: summary });
        }
        if (pathname === '/api/v1/sales/orders' && searchParams.get('customerId') === customer.customerId) {
          expect(searchParams.get('size')).toBe('20');
          expect(searchParams.get('page')).toMatch(/^[01]$/);
          const index = Number(searchParams.get('page'));
          return route.fulfill({ json: { items: orders.slice(index * 20, (index + 1) * 20), page: index, size: 20, total: orders.length } });
        }
      }
      throw new Error(`Unexpected API request: ${route.request().url()}`);
    });

    await page.goto('/catalog/customers');
    await expect(page.getByRole('banner').getByText(role === 'OWNER' ? 'Chủ cửa hàng' : 'Nhân viên')).toBeVisible();
    await page.getByRole('button', { name: 'Lịch sử mua của Anh An' }).click();
    const an = page.getByRole('dialog', { name: 'Lịch sử mua · Anh An' });
    await expect(an.getByRole('cell', { name: /Số đơn/ })).toHaveText(/Số đơn\s*20$/);
    await expect(an.getByRole('cell', { name: /Tổng chi tiêu/ })).toContainText('196.000');
    await expect(an.getByRole('row', { name: /AN-1\s/ })).toContainText(/Hoàn tất.*10\.000.*Lan/);
    await expect(an.getByRole('row', { name: /AN-2\s/ })).toContainText('Đã hủy');
    await expect(an.getByRole('cell', { name: 'AN-21', exact: true })).toHaveCount(0);
    await an.getByTitle('2', { exact: true }).click();
    await expect(an.getByRole('cell', { name: 'AN-21', exact: true })).toBeVisible();
    await expect(an.getByRole('cell', { name: 'AN-1', exact: true })).toHaveCount(0);
    await an.getByRole('button').filter({ hasText: /^Đóng$/ }).click();

    await page.getByRole('button', { name: 'Lịch sử mua của Chị Hà' }).click();
    const ha = page.getByRole('dialog', { name: 'Lịch sử mua · Chị Hà' });
    await expect(ha.getByRole('cell', { name: /Số đơn/ })).toHaveText(/Số đơn\s*1$/);
    await expect(ha.getByRole('cell', { name: /Tổng chi tiêu/ })).toContainText('85.000');
    await expect(ha.getByRole('row', { name: /HA-1\s/ })).toContainText('85.000');
    await expect(ha.getByRole('cell', { name: /^AN-/ })).toHaveCount(0);
  });
}
