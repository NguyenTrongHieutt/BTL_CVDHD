import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';

if (process.env.KH03_BROWSER_CHANNEL) test.use({ channel: process.env.KH03_BROWSER_CHANNEL });

async function post<T>(
  request: APIRequestContext,
  path: string,
  data: object,
  status: number,
  key?: string,
): Promise<T> {
  const response = await request.post(path, {
    data,
    headers: key ? { 'Idempotency-Key': key } : undefined,
  });
  expect(response.status(), `POST ${path}`).toBe(status);
  return response.json();
}

test.describe('KH-03 với API thật', () => {
  test.skip(process.env.KH03_LIVE !== '1', 'Chỉ chạy trên dữ liệu demo riêng bằng KH03_LIVE=1.');

  for (const role of ['OWNER', 'STAFF'] as const) {
    test(`${role} xem lịch sử của khách gắn trên đơn vừa bán`, async ({ request, page, baseURL }) => {
      const origin = new URL(baseURL ?? '');
      expect(['localhost', '127.0.0.1', '[::1]']).toContain(origin.hostname);

      const tag = randomUUID().slice(0, 8);
      const password = `Demo-${randomUUID()}`;
      const ownerLogin = `kh03.${tag}@example.test`;
      const staffLogin = `kh03.${tag}`;
      const ownerName = 'Chủ cửa hàng KH-03';
      const customerName = 'Khách đã mua';
      const otherCustomerName = 'Khách chưa mua';

      await post(request, '/api/v1/platform/stores', {
        storeName: `Demo KH-03 ${tag}`, loginKey: ownerLogin, displayName: ownerName, password,
      }, 201, randomUUID());
      await post(request, '/api/v1/platform/users', { loginKey: staffLogin, displayName: 'Nhân viên KH-03', password }, 201);
      const product = await post<{ variants: { id: string }[] }>(request, '/api/v1/catalog/products', {
        name: 'Sản phẩm KH-03', sku: 'KH03-01', salePrice: 15_000, openingQuantity: 10, openingUnitCost: 5_000,
      }, 201, randomUUID());
      const customer = await post<{ customerId: string }>(request, '/api/v1/catalog/customers', {
        name: customerName, phone: '0901234567',
      }, 201);
      await post(request, '/api/v1/catalog/customers', { name: otherCustomerName, phone: '0901234568' }, 201);
      const orderInput = {
        lines: [{ variantId: product.variants[0].id, quantity: 2 }],
        customerId: customer.customerId,
        paymentMethod: 'CASH',
        cashReceived: 50_000,
      };
      const key = randomUUID();
      const quote = await post<{ total: number }>(request, '/api/v1/sales/orders/quote', orderInput, 200, key);
      expect(quote.total).toBe(30_000);
      const order = await post<{ orderCode: string }>(request, '/api/v1/sales/orders', {
        ...orderInput, quotedTotal: quote.total,
      }, 201, key);

      await post(page.request, '/api/v1/platform/auth/login', {
        loginKey: role === 'OWNER' ? ownerLogin : staffLogin, password,
      }, 200);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.goto('/catalog/customers');
      const historyTrigger = page.getByRole('button', { name: `Lịch sử mua của ${customerName}` });
      await historyTrigger.press('Enter');
      const history = page.getByRole('dialog', { name: `Lịch sử mua · ${customerName}` });
      await expect(history.getByRole('cell', { name: /Số đơn/ })).toHaveText(/Số đơn\s*1$/);
      await expect(history.getByRole('cell', { name: /Tổng chi tiêu/ })).toContainText('30.000');
      await expect(history.getByRole('cell', { name: /Lần mua gần nhất/ })).not.toContainText('Chưa mua hàng');
      await expect(history.getByRole('row', { name: new RegExp(order.orderCode) })).toContainText(/Hoàn tất.*30\.000.*Chủ cửa hàng KH-03/);
      await page.keyboard.press('Escape');
      await expect(history).toBeHidden();
      await expect(historyTrigger).toBeFocused();

      await page.getByRole('button', { name: `Lịch sử mua của ${otherCustomerName}` }).click();
      const empty = page.getByRole('dialog', { name: `Lịch sử mua · ${otherCustomerName}` });
      await expect(empty.getByRole('cell', { name: /Số đơn/ })).toHaveText(/Số đơn\s*0$/);
      await expect(empty.getByText('Chưa mua hàng', { exact: true })).toBeVisible();
      await expect(empty.getByText('Khách hàng chưa có đơn hàng.', { exact: true })).toBeVisible();
    });
  }
});
