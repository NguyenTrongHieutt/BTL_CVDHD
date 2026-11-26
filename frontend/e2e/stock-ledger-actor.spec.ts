// Set VITE_LEDGER_ACTOR_FILTER=false only when testing against an older frontend.
import { expect, test, type Route } from '@playwright/test';

const actorA = { userId: '00000000-0000-4000-8000-000000000001', displayName: 'Actor A' };
const actorB = { userId: '00000000-0000-4000-8000-000000000002', displayName: 'Actor B' };
const ledger = Array.from({ length: 43 }, (_, index) => {
  const actor = index % 2 === 0 ? actorA : actorB;
  return {
    actorId: actor.userId,
    actorName: actor.displayName,
    productName: `Product ${index + 1}`,
    variantId: '00000000-0000-4000-8000-000000000003',
    variantName: null,
    documentId: `10000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
    documentType: 'OPENING',
    createdAt: '2026-11-01T08:00:00Z',
    delta: 1,
    quantityAfter: 1,
  };
});

test('owner filters paged stock ledger by actor and clears the filter', async ({ page }) => {
  test.skip(process.env.VITE_LEDGER_ACTOR_FILTER === 'false', 'Actor filtering is disabled');

  await page.route('**/api/v1/platform/me', route => route.fulfill({ json: {
    ...actorA, role: 'OWNER', storeName: 'Test store', storeId: '00000000-0000-4000-8000-000000000004',
  } }));
  await page.route('**/api/v1/platform/users?*', route => route.fulfill({ json: {
    items: [actorA, actorB], page: 0, size: 100, total: 2,
  } }));
  await page.route('**/api/v1/inventory/ledger?*', async (route: Route) => {
    const params = new URL(route.request().url()).searchParams;
    const actorId = params.get('actorId');
    const rows = actorId === null ? ledger : ledger.filter(row => row.actorId === actorId);
    const pageIndex = Number(params.get('page'));
    const size = Number(params.get('size'));
    await route.fulfill({ json: {
      items: rows.slice(pageIndex * size, (pageIndex + 1) * size),
      page: pageIndex, size, total: rows.length,
    } });
  });

  const table = page.getByRole('table');
  async function expectRows(expected: typeof ledger, total: number) {
    await expect(page.getByText(`Tổng ${total} dòng`, { exact: true })).toBeVisible();
    const rows = table.locator('tbody tr[data-row-key]');
    await expect(rows).toHaveCount(expected.length);
    await expect(rows).toContainText(expected.map(row => row.productName));
    await expect(rows).toContainText(expected.map(row => row.actorName));
  }
  async function selectActor(actor: typeof actorA) {
    await page.getByRole('combobox', { name: 'Lọc theo người thực hiện' }).click();
    await page.locator('.ant-select-dropdown:visible').getByText(actor.displayName, { exact: true }).click();
    await expect(page).toHaveURL(url => url.searchParams.get('actorId') === actor.userId);
  }

  await page.goto('/catalog/stock-ledger');
  await expectRows(ledger.slice(0, 20), 43);

  const aRows = ledger.filter(row => row.actorId === actorA.userId);
  await selectActor(actorA);
  await expectRows(aRows.slice(0, 20), 22);
  await page.getByTitle('2', { exact: true }).click();
  await expectRows(aRows.slice(20), 22);

  const bRows = ledger.filter(row => row.actorId === actorB.userId);
  await selectActor(actorB);
  await expectRows(bRows.slice(0, 20), 21);
  await page.getByTitle('2', { exact: true }).click();
  await expectRows(bRows.slice(20), 21);

  await page.getByRole('button', { name: 'Xóa bộ lọc' }).click();
  await expect(page).toHaveURL(url => !url.searchParams.has('actorId'));
  await expectRows(ledger.slice(0, 20), 43);
});
