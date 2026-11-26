import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../shared/types/catalog';
import type { StockLedgerEntry } from '../../../shared/types/inventory';
import { foldText } from '../components/textUtils';
import { withStockLedgerFilters } from '../api/stockLedger';
import type { LedgerDocument } from '../api/stockLedgerDocuments';
import { StockLedgerPage } from './StockLedgerPage';

// Màn antd nhiều ô: chạy cả bộ trên máy bận có thể vượt 5 giây mặc định
vi.setConfig({ testTimeout: 15_000 });

const LEDGER_PATH = '/api/v1/inventory/ledger';
const PRODUCTS_PATH = '/api/v1/catalog/products';
const PRODUCT_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)$/;
const PAGE_PATH = '/catalog/stock-ledger';
const VIETNAM_OFFSET_MS = 7 * 3_600_000;
const DAY_MS = 86_400_000;

// Id dạng UUID như backend (bộ lọc trên URL chỉ nhận UUID)
const MILK = 'aaaaaaaa-0000-4000-8000-000000000001';
const MILK_VARIANT = 'aaaaaaaa-1111-4000-8000-000000000001';
const YOGURT = 'aaaaaaaa-0000-4000-8000-000000000002';
const YOGURT_VARIANT = 'aaaaaaaa-1111-4000-8000-000000000002';
const SHIRT = 'aaaaaaaa-0000-4000-8000-000000000003';
const SHIRT_DEFAULT = 'aaaaaaaa-1111-4000-8000-000000000003';
const SHIRT_RED = 'aaaaaaaa-2222-4000-8000-000000000003';
const SHIRT_BLUE = 'aaaaaaaa-3333-4000-8000-000000000003';
const WATER = 'aaaaaaaa-0000-4000-8000-000000000004';
const WATER_VARIANT = 'aaaaaaaa-1111-4000-8000-000000000004';
const GONE_VARIANT = 'aaaaaaaa-9999-4000-8000-000000000009';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function variant(id: string, sku: string, name: string | null, isDefault: boolean, barcode: string | null = null) {
  return { id, sku, barcode, name, attributes: {}, salePrice: 10000, isDefault, status: 'ACTIVE' as const };
}

function product(id: string, name: string, variants: Product['variants'], overrides: Partial<Product> = {}): Product {
  return {
    id,
    name,
    categoryId: null,
    categoryName: null,
    unit: 'cái',
    description: null,
    imageUrl: null,
    hasVariants: variants.length > 1,
    status: 'ACTIVE',
    createdAt: '2026-10-01T03:00:00Z',
    updatedAt: '2026-10-01T03:00:00Z',
    priceRange: { min: 10000, max: 10000 },
    variants,
    ...overrides,
  };
}

let documentNo: number;

function entry(
  createdAt: string,
  documentType: string,
  variantId: string,
  delta: number,
  quantityAfter: number,
  overrides: Partial<StockLedgerEntry> = {},
): StockLedgerEntry {
  const names: Record<string, [string, string | null]> = {
    [MILK_VARIANT]: ['Sữa tươi', null],
    [YOGURT_VARIANT]: ['Sữa chua Ba Vì', null],
    [SHIRT_RED]: ['Áo thun', 'Đỏ / L'],
    [SHIRT_BLUE]: ['Áo thun', 'Xanh / M'],
    [WATER_VARIANT]: ['Nước suối', null],
  };
  const [productName, variantName] = names[variantId] ?? [null, null];
  documentNo += 1;
  return {
    createdAt,
    documentType,
    documentId: `d${String(documentNo).padStart(7, '0')}-0000-4000-8000-00000000000${documentNo % 10}`,
    variantId,
    productName,
    variantName,
    delta,
    quantityAfter,
    actorName: 'Hiếu',
    ...overrides,
  };
}

/** Sản phẩm của backend giả (cả hàng ngừng kinh doanh, như `GET …/products` không có `status`). */
let products: Product[];
/** Sổ kho của backend giả, thứ tự bất kỳ (backend giả tự sắp mới nhất trước). */
let ledger: StockLedgerEntry[];
/** Lần tải sổ kho tới lỗi: `500` hoặc mất mạng; giữ tới khi đặt lại. */
let failLedger: 'server' | 'network' | null;
/** Backend trả `400` trên `toDate` cho mọi khoảng ngày (như khi backend kiểm khác client). */
let rejectRange: boolean;
/** Chi tiết Sản phẩm (`GET …/products/{id}`) trả `500` tới khi đặt lại. */
let failProductDetail: boolean;

/** Ngày lịch Việt Nam (`yyyy-MM-dd`) của một thời điểm UTC. */
function vietnamDate(iso: string): string {
  return new Date(new Date(iso).getTime() + VIETNAM_OFFSET_MS).toISOString().slice(0, 10);
}

function validationProblem(message: string): Response {
  return json(
    {
      type: '/errors/common/validation',
      title: 'Dữ liệu không hợp lệ',
      status: 400,
      detail: message,
      requestId: 'req-400',
      errors: [{ field: 'toDate', message }],
    },
    400,
  );
}

/** Như `StockLedgerQuery`: lọc Biến thể, loại, khoảng ngày Việt Nam (gồm hai đầu, tối đa 366 ngày), mới nhất. */
function listLedger(params: URLSearchParams): Response {
  if (failLedger === 'network') throw new TypeError('Failed to fetch');
  if (failLedger === 'server') {
    return json({ type: '/errors/common/internal', title: 'Lỗi hệ thống', status: 500, requestId: 'req-500' }, 500);
  }
  const [variantId, documentType] = [params.get('variantId'), params.get('documentType')];
  const [fromDate, toDate] = [params.get('fromDate'), params.get('toDate')];
  if (fromDate && toDate) {
    const days = (Date.parse(toDate) - Date.parse(fromDate)) / DAY_MS;
    if (rejectRange || days < 0 || days >= 366) {
      return validationProblem('Khoảng ngày phải theo thứ tự và tối đa 366 ngày.');
    }
  }
  const matching = ledger
    .filter(
      (e) =>
        (!variantId || e.variantId === variantId) &&
        (!documentType || e.documentType === documentType) &&
        (!fromDate || vietnamDate(e.createdAt) >= fromDate) &&
        (!toDate || vietnamDate(e.createdAt) <= toDate),
    )
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const page = Number(params.get('page') ?? '0');
  const size = Number(params.get('size') ?? '20');
  return json({ items: matching.slice(page * size, (page + 1) * size), total: matching.length, page, size });
}

/** Như `GET /api/v1/catalog/products?q=` (SP-05): tên chứa `q` không dấu, hoặc SKU bắt đầu bằng `q`; sắp theo tên. */
function listProducts(params: URLSearchParams): Response {
  const q = params.get('q');
  const matching = products
    .filter(
      (p) =>
        !q ||
        foldText(p.name).includes(foldText(q)) ||
        p.variants.some((v) => [v.sku, v.barcode ?? ''].some((code) => code.toUpperCase().startsWith(q.toUpperCase()))),
    )
    .sort((a, b) => foldText(a.name).localeCompare(foldText(b.name)));
  const page = Number(params.get('page') ?? '0');
  const size = Number(params.get('size') ?? '20');
  return json({ items: matching.slice(page * size, (page + 1) * size), total: matching.length, page, size });
}

const ACTOR_ID = 'bf12e0e5-29a4-46dd-bd48-631b3b74fd29';

function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (method === 'GET' && url.pathname === LEDGER_PATH) return listLedger(url.searchParams);
    if (method === 'GET' && url.pathname === PRODUCTS_PATH) return listProducts(url.searchParams);
    if (method === 'GET' && url.pathname === '/api/v1/platform/users') {
      return json({ items: [{ userId: ACTOR_ID, displayName: 'Hiếu' }], total: 1, page: 0, size: 100 });
    }
    const detail = PRODUCT_PATH.exec(url.pathname);
    if (method === 'GET' && detail && failProductDetail) {
      return json({ type: '/errors/common/internal', title: 'Lỗi hệ thống', status: 500, requestId: 'r' }, 500);
    }
    const found = detail && products.find((p) => p.id === decodeURIComponent(detail[1]));
    if (method === 'GET' && found) return json(found);
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404, requestId: 'r' }, 404);
  });
}

function mockDocuments(documents: Record<string, LedgerDocument>) {
  const lookup = fetchMock.getMockImplementation();
  fetchMock.mockImplementation(async (input, init) => {
    const pathname = new URL(String(input), 'http://localhost').pathname;
    if (documents[pathname]) return json(documents[pathname]);
    if (lookup) return lookup(input, init);
    throw new Error('Thiếu API giả lập cho sổ kho');
  });
}

function renderPage(
  path = PAGE_PATH,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  const router = createMemoryRouter([{ path: PAGE_PATH, Component: StockLedgerPage }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <RouterProvider router={router} />
      </AntdApp>
    </QueryClientProvider>,
  );
  return { queryClient, router };
}

function callsTo(pathname: string | RegExp): URL[] {
  return fetchMock.mock.calls
    .map(([input, init]) => ({ url: new URL(String(input), 'http://localhost'), method: init?.method ?? 'GET' }))
    .filter(({ url, method }) =>
      method === 'GET' && (typeof pathname === 'string' ? url.pathname === pathname : pathname.test(url.pathname)),
    )
    .map(({ url }) => url);
}

/** Tham số query của từng lần tải sổ kho. */
function ledgerQueries(): Record<string, string>[] {
  return callsTo(LEDGER_PATH).map((url) => Object.fromEntries(url.searchParams));
}

function lastLedgerQuery(): Record<string, string> {
  const queries = ledgerQueries();
  return queries[queries.length - 1] ?? {};
}

function urlParams(router: ReturnType<typeof createMemoryRouter>): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(router.state.location.search));
}

it('bấm mã phiếu nhập để xem toàn bộ dòng gốc và giữ bộ lọc khi đóng', async () => {
  const row = entry('2026-11-26T01:00:00Z', 'PURCHASE_RECEIPT', MILK_VARIANT, 3, 10);
  ledger = [row];
  const sourcePath = LEDGER_PATH + '/documents/PURCHASE_RECEIPT/' + row.documentId;
  mockDocuments({
    [sourcePath]: {
      documentType: 'PURCHASE_RECEIPT', documentId: row.documentId, code: 'PN00003',
      recordedAt: row.createdAt, supplierName: 'Nhà cung cấp thử', totalAmount: 59_000,
      lines: [
        { lineNo: 1, productName: 'Sữa tươi', variantName: null, quantity: 3, unitAmount: 15_000, amount: 45_000 },
        { lineNo: 2, productName: 'Nước suối', variantName: null, quantity: 2, unitAmount: 7_000, amount: 14_000 },
      ],
    },
  });
  const { router } = renderPage(PAGE_PATH + '?type=PURCHASE_RECEIPT&variantId=' + MILK_VARIANT);
  const link = await screen.findByRole('link', { name: /Mở phiếu nhập D/ });
  fireEvent.click(link);
  const dialog = within(await screen.findByRole('dialog'));
  expect(await dialog.findByText('Phiếu nhập · PN00003')).toBeTruthy();
  expect(dialog.getByText('Nhà cung cấp thử')).toBeTruthy();
  expect(dialog.getByText('Nước suối')).toBeTruthy();
  expect(callsTo(sourcePath)).toHaveLength(1);
  expect(urlParams(router)).toMatchObject({ type: 'PURCHASE_RECEIPT', variantId: MILK_VARIANT, sourceId: row.documentId });
  fireEvent.click(dialog.getByRole('button', { name: 'Đóng' }));
  await waitFor(() => expect(urlParams(router)).toEqual({ type: 'PURCHASE_RECEIPT', variantId: MILK_VARIANT }));
  expect(screen.getByRole('link', { name: /Mở phiếu nhập D/ })).toBeTruthy();
});

it('mở phiếu trả từ liên kết trực tiếp và chuyển được sang đúng đơn bán gốc', async () => {
  const sale = entry('2026-11-25T04:00:00Z', 'SALE', MILK_VARIANT, -3, 8);
  const returned = entry('2026-11-26T01:00:00Z', 'SALE_RETURN', MILK_VARIANT, 1, 9);
  ledger = [sale, returned];
  mockDocuments({
    ['/api/v1/sales/ledger-documents/SALE_RETURN/' + returned.documentId]: {
      documentType: 'SALE_RETURN', documentId: returned.documentId, recordedAt: returned.createdAt,
      orderId: sale.documentId, relatedOrderCode: 'DH-000005', note: 'Trả một hộp', totalAmount: 15_000,
      lines: [{ lineNo: 1, productName: 'Sữa tươi', variantName: null, quantity: 1, amount: 15_000 }],
    },
    ['/api/v1/sales/ledger-documents/SALE/' + sale.documentId]: {
      documentType: 'SALE', documentId: sale.documentId, code: 'DH-000005',
      recordedAt: sale.createdAt, status: 'COMPLETED', totalAmount: 45_000,
      lines: [{ lineNo: 1, productName: 'Sữa tươi', variantName: null, quantity: 3, amount: 45_000 }],
    },
  });
  const { router } = renderPage(PAGE_PATH + '?type=SALE_RETURN&sourceType=SALE_RETURN&sourceId=' + returned.documentId);
  const original = await within(screen.getByRole('dialog')).findByRole('link', { name: 'DH-000005' });
  fireEvent.click(original);
  const order = within(await screen.findByRole('dialog'));
  expect(await order.findByText('Đơn bán · DH-000005')).toBeTruthy();
  expect(order.getByText('Đã hoàn thành')).toBeTruthy();
  expect(urlParams(router)).toMatchObject({ type: 'SALE_RETURN', sourceType: 'SALE', sourceId: sale.documentId });
});

/** Bảng sổ kho (không tính các bảng lịch của ô chọn ngày khi đang mở). */
function table(): HTMLElement {
  const tables = screen.getAllByRole('table').filter((t) => !t.closest('.ant-picker-dropdown'));
  if (tables.length !== 1) throw new Error(`có ${tables.length} bảng sổ kho`);
  return tables[0];
}

function filterBar(): HTMLElement {
  return screen.getByRole('search', { name: 'Bộ lọc lịch sử xuất nhập kho' });
}

function dateGroup(): HTMLElement {
  return screen.getByRole('group', { name: 'Khoảng ngày' });
}

function dataRows(): HTMLElement[] {
  return within(table())
    .getAllByRole('row')
    .slice(1)
    .filter((row) => within(row).queryAllByRole('cell').length > 1);
}

/** Chữ trong từng ô của các dòng dữ liệu. */
function rowTexts(): string[][] {
  return dataRows().map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent ?? ''));
}

/** Cột "Sản phẩm / Biến thể" và "Thay đổi" của các dòng, đủ để biết bảng đang lọc đúng chưa. */
function rowSummaries(): string[] {
  return rowTexts().map((cells) => `${cells[1]} ${cells[2]} ${cells[3]}`);
}

/** Chữ đang hiện trong ô chọn có nhãn này (không tính vùng `aria-live` của antd). */
function selectedText(label: string): string {
  return within(filterBar()).getByLabelText(label).closest('.ant-select-content')?.textContent ?? '';
}

function productSearches(): string[] {
  return callsTo(PRODUCTS_PATH)
    .map((url) => url.searchParams.get('q'))
    .filter((q): q is string => q !== null);
}

/** Tiêu đề các gợi ý đang hiện của mọi ô chọn đang mở. */
function optionTitles(): (string | null)[] {
  return [...document.querySelectorAll('.ant-select-item-option')].map((option) => option.getAttribute('title'));
}

async function pickOption(label: string, option: string) {
  fireEvent.mouseDown(within(filterBar()).getByLabelText(label));
  const found = await waitFor(() => {
    const options = [...document.querySelectorAll(`.ant-select-item-option[title="${option}"]`)];
    if (options.length === 0) throw new Error(`chưa thấy lựa chọn ${option}`);
    return options[options.length - 1];
  });
  fireEvent.click(found);
}

/** Gõ vào ô chọn Sản phẩm rồi chọn một Sản phẩm trong gợi ý. */
async function searchAndPickProduct(text: string, name: string) {
  const box = within(filterBar()).getByLabelText('Lọc theo Sản phẩm');
  fireEvent.mouseDown(box);
  fireEvent.change(box, { target: { value: text } });
  // Gõ xong một lúc mới tìm: chờ kết quả của từ khóa này rồi mới chọn
  await waitFor(() => expect(productSearches()).toContain(text));
  await pickOption('Lọc theo Sản phẩm', name);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  documentNo = 0;
  failLedger = null;
  rejectRange = false;
  failProductDetail = false;
  products = [
    product(MILK, 'Sữa tươi', [variant(MILK_VARIANT, 'SUA-TUOI', null, true, '8934673123456')]),
    product(YOGURT, 'Sữa chua Ba Vì', [variant(YOGURT_VARIANT, 'SUA-CHUA', null, true)], { status: 'INACTIVE' }),
    product(SHIRT, 'Áo thun', [
      variant(SHIRT_DEFAULT, 'AO', null, true),
      variant(SHIRT_RED, 'AO-DO-L', 'Đỏ / L', false, '8934673123457'),
      variant(SHIRT_BLUE, 'AO-XANH-M', 'Xanh / M', false),
    ]),
    product(WATER, 'Nước suối', [variant(WATER_VARIANT, 'NUOC', null, true)]),
  ];
  ledger = [
    entry('2026-10-20T02:00:00Z', 'OPENING', MILK_VARIANT, 24, 24, { actorName: null }),
    // 17:30 UTC ngày 01/11 là 00:30 ngày 02/11 giờ Việt Nam
    entry('2026-11-01T17:30:00Z', 'PURCHASE_RECEIPT', MILK_VARIANT, 1200, 1224),
    entry('2026-11-05T03:15:00Z', 'SALE', MILK_VARIANT, -3, 1221, { actorName: 'Lan' }),
    entry('2026-11-05T04:00:00Z', 'SALE_CANCEL', MILK_VARIANT, 3, 1224),
    entry('2026-11-03T01:00:00Z', 'PURCHASE_RECEIPT', WATER_VARIANT, 50, 50),
    entry('2026-11-10T09:00:00Z', 'STOCKTAKE', WATER_VARIANT, -2, 48),
    entry('2026-11-08T02:00:00Z', 'PURCHASE_RECEIPT', SHIRT_RED, 4, 4),
    entry('2026-11-12T02:00:00Z', 'SALE_RETURN', SHIRT_RED, 1, 5),
    entry('2026-11-09T02:00:00Z', 'PURCHASE_RECEIPT', SHIRT_BLUE, 6, 6),
    entry('2026-11-13T02:00:00Z', 'SALE', YOGURT_VARIANT, -1, 9),
  ];
  backend();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.useRealTimers();
});

describe('Lịch sử xuất nhập kho (FR-28, KHO-07)', () => {
  it('ẩn lọc người thực hiện và bỏ qua actorId trên URL khi backend chưa hỗ trợ', async () => {
    vi.stubEnv('VITE_LEDGER_ACTOR_FILTER', 'false');
    renderPage(PAGE_PATH + '?actorId=' + ACTOR_ID);
    await waitFor(() => expect(ledgerQueries().length).toBeGreaterThan(0));
    expect(screen.queryByLabelText('Lọc theo người thực hiện')).toBeNull();
    expect(lastLedgerQuery()).not.toHaveProperty('actorId');
    expect(callsTo('/api/v1/platform/users')).toHaveLength(0);
  });

  it('chọn và xóa người thực hiện cập nhật URL và gửi đúng mã tài khoản', async () => {
    vi.stubEnv('VITE_LEDGER_ACTOR_FILTER', 'true');
    const { router } = renderPage();
    await pickOption('Lọc theo người thực hiện', 'Hiếu');
    await waitFor(() => expect(lastLedgerQuery().actorId).toBe(ACTOR_ID));
    expect(urlParams(router).actorId).toBe(ACTOR_ID);
    fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
    await waitFor(() => expect(lastLedgerQuery()).not.toHaveProperty('actorId'));
    expect(urlParams(router)).not.toHaveProperty('actorId');
  });

  it('mở màn: mới nhất trước, giờ Việt Nam, nhãn tiếng Việt, số âm đỏ có dấu −, số dương xanh có dấu +', async () => {
    ledger = [
      entry('2026-11-05T03:15:00Z', 'SALE', MILK_VARIANT, -3, 1221, { actorName: 'Lan' }),
      entry('2026-11-01T17:30:00Z', 'PURCHASE_RECEIPT', MILK_VARIANT, 1200, 1224),
      entry('2026-11-12T02:00:00Z', 'SALE_RETURN', SHIRT_RED, 1, 5),
      entry('2026-10-20T02:00:00Z', 'OPENING', MILK_VARIANT, 24, 24, { actorName: null }),
      entry('2026-11-05T04:00:00Z', 'SALE_CANCEL', MILK_VARIANT, 3, 1224),
      entry('2026-11-10T09:00:00Z', 'STOCKTAKE', WATER_VARIANT, 0, 48),
    ];
    renderPage();
    await within(table()).findByText('Trả hàng');

    expect(ledgerQueries()).toEqual([{ page: '0', size: '20' }]);
    expect(document.title).toContain('Lịch sử xuất nhập kho');
    expect(rowTexts().map((cells) => cells.slice(0, 6))).toEqual([
      ['12/11/2026 09:00', 'Trả hàng', 'Áo thunĐỏ / L', '+1', '5', 'Hiếu'],
      ['10/11/2026 16:00', 'Kiểm kho', 'Nước suối', '0', '48', 'Hiếu'],
      ['05/11/2026 11:00', 'Hủy đơn', 'Sữa tươi', '+3', '1.224', 'Hiếu'],
      ['05/11/2026 10:15', 'Bán hàng', 'Sữa tươi', '−3', '1.221', 'Lan'],
      ['02/11/2026 00:30', 'Nhập hàng', 'Sữa tươi', '+1.200', '1.224', 'Hiếu'],
      ['20/10/2026 09:00', 'Tồn đầu kỳ', 'Sữa tươi', '+24', '24', '—'],
    ]);
    const sale = dataRows()[3];
    expect(within(sale).getByText('−3').closest('.ant-typography-danger')).not.toBeNull();
    const receipt = dataRows()[4];
    expect(within(receipt).getByText('+1.200').closest('.ant-typography-success')).not.toBeNull();
    const zero = within(dataRows()[1]).getByText('0', { selector: 'strong' });
    expect(zero.closest('.ant-typography-success, .ant-typography-danger')).toBeNull();
    // Mã chứng từ rút gọn, có nút sao chép mã đầy đủ
    const [first] = ledger.filter((e) => e.documentType === 'SALE_RETURN');
    expect(within(dataRows()[0]).getByText(first.documentId.slice(0, 8).toUpperCase())).toBeTruthy();
    expect(within(dataRows()[0]).getByRole('button', { name: 'Sao chép mã đầy đủ' })).toBeTruthy();
  });

  it('loại giao dịch lạ hiện nguyên mã; Biến thể không còn thì "Sản phẩm không còn"', async () => {
    ledger = [
      entry('2026-11-14T16:59:00Z', 'SALE', GONE_VARIANT, -1, 0),
      entry('2026-11-14T10:00:00Z', 'TRANSFER_OUT', WATER_VARIANT, -5, 43),
    ];
    renderPage();
    await within(table()).findByText('Sản phẩm không còn');

    expect(rowTexts().map((cells) => cells.slice(0, 4))).toEqual([
      ['14/11/2026 23:59', 'Bán hàng', 'Sản phẩm không còn', '−1'],
      ['14/11/2026 17:00', 'TRANSFER_OUT', 'Nước suối', '−5'],
    ]);
  });

  it('lọc loại "Nhập hàng": gửi documentType, URL giữ ?type=, về trang 1', async () => {
    ledger = Array.from({ length: 25 }, (_, i) => {
      const at = new Date(Date.UTC(2026, 10, 1, 2) + i * 3_600_000).toISOString();
      return i % 5 === 0
        ? entry(at, 'PURCHASE_RECEIPT', MILK_VARIANT, 10, 100)
        : entry(at, 'SALE', MILK_VARIANT, -1, 100);
    });
    const { router } = renderPage();
    await waitFor(() => expect(dataRows()).toHaveLength(20));
    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() => expect(lastLedgerQuery()).toEqual({ page: '1', size: '20' }));
    await waitFor(() => expect(dataRows()).toHaveLength(5));

    await pickOption('Lọc theo loại giao dịch', 'Nhập hàng');

    await waitFor(() =>
      expect(lastLedgerQuery()).toEqual({ documentType: 'PURCHASE_RECEIPT', page: '0', size: '20' }),
    );
    await waitFor(() => expect(dataRows()).toHaveLength(5));
    expect(rowTexts().every((cells) => cells[1] === 'Nhập hàng')).toBe(true);
    expect(urlParams(router)).toEqual({ type: 'PURCHASE_RECEIPT' });
    expect(selectedText('Lọc theo loại giao dịch')).toBe('Nhập hàng');
  });

  it('lọc Sản phẩm: gõ "sua", chọn "Sữa tươi" thì gửi variantId Biến thể mặc định; gợi ý có cả hàng ngừng', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Trả hàng');

    const box = within(filterBar()).getByLabelText('Lọc theo Sản phẩm');
    fireEvent.mouseDown(box);
    fireEvent.change(box, { target: { value: 's' } });
    fireEvent.change(box, { target: { value: 'sua' } });
    // Gõ xong mới tìm: chỉ một lần tìm với "sua"; gợi ý chỉ còn Sản phẩm khớp
    await waitFor(() => expect(productSearches()).toEqual(['sua']));
    await waitFor(() => expect(document.querySelector('.ant-select-item-option[title="Nước suối"]')).toBeNull());
    // Hàng đã ngừng kinh doanh vẫn có trong gợi ý (lịch sử của nó vẫn còn)
    expect(
      within(document.querySelector('.ant-select-item-option[title="Sữa chua Ba Vì"]') as HTMLElement).getByText(
        'Ngừng kinh doanh',
      ),
    ).toBeTruthy();
    // Không lọc theo trạng thái
    expect(callsTo(PRODUCTS_PATH).some((url) => url.searchParams.has('status'))).toBe(false);

    await pickOption('Lọc theo Sản phẩm', 'Sữa tươi');

    await waitFor(() => expect(lastLedgerQuery()).toEqual({ variantId: MILK_VARIANT, page: '0', size: '20' }));
    await waitFor(() => expect(dataRows()).toHaveLength(4));
    expect(rowTexts().every((cells) => cells[2] === 'Sữa tươi')).toBe(true);
    expect(urlParams(router)).toEqual({ productId: MILK, variantId: MILK_VARIANT });
    // Một Biến thể: không có ô chọn Biến thể; chọn từ gợi ý nên không tải chi tiết Sản phẩm
    expect(within(filterBar()).queryByLabelText('Chọn Biến thể')).toBeNull();
    expect(callsTo(PRODUCT_PATH)).toHaveLength(0);
  });

  it('Sản phẩm nhiều Biến thể: chưa chọn Biến thể thì chưa lọc, chọn "Đỏ / L" thì gửi variantId đó', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Trả hàng');

    await searchAndPickProduct('ao', 'Áo thun');

    expect(await within(filterBar()).findByText('Chọn Biến thể để lọc')).toBeTruthy();
    expect(urlParams(router)).toEqual({ productId: SHIRT });
    expect(ledgerQueries().every((query) => !('variantId' in query))).toBe(true);
    // Sản phẩm chưa có Biến thể chưa là bộ lọc: không có "Xóa bộ lọc"
    expect(within(filterBar()).queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();

    const variantBox = within(filterBar()).getByLabelText('Chọn Biến thể');
    fireEvent.mouseDown(variantBox);
    fireEvent.change(variantBox, { target: { value: 'do / l' } });
    await waitFor(() => expect(optionTitles()).toContain('Đỏ / L · AO-DO-L'));
    expect(optionTitles()).not.toContain('Xanh / M · AO-XANH-M');
    fireEvent.change(variantBox, { target: { value: '8934673123457' } });
    await pickOption('Chọn Biến thể', 'Đỏ / L · AO-DO-L');

    await waitFor(() => expect(lastLedgerQuery()).toEqual({ variantId: SHIRT_RED, page: '0', size: '20' }));
    await waitFor(() => expect(rowSummaries()).toEqual(['Trả hàng Áo thunĐỏ / L +1', 'Nhập hàng Áo thunĐỏ / L +4']));
    expect(urlParams(router)).toEqual({ productId: SHIRT, variantId: SHIRT_RED });
    expect(within(filterBar()).queryByText('Chọn Biến thể để lọc')).toBeNull();
  });

  it('chia biến thể thành từng trang và chọn được biến thể ở trang sau', async () => {
    const extras = Array.from({ length: 5 }, (_, index) => variant(
      'd1f7e80a-503a-478d-8ad6-00000000000' + index,
      'AO-THEM-' + index, 'Biến thể thêm ' + index, false,
    ));
    products = products.map((item) => item.id === SHIRT ? { ...item, variants: [...item.variants, ...extras] } : item);
    const { router } = renderPage(PAGE_PATH + '?productId=' + SHIRT);
    fireEvent.mouseDown(await within(filterBar()).findByLabelText('Chọn Biến thể'));
    expect(await screen.findByText('Trang 1/2')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Trang sau' }));
    const last = await screen.findByTitle('Biến thể thêm 4 · AO-THEM-4');
    expect(screen.queryByTitle('Đỏ / L · AO-DO-L')).toBeNull();
    fireEvent.click(last);
    await waitFor(() => expect(urlParams(router).variantId).toBe(extras[4].id));
  });

  it('Chủ cửa hàng chọn "Nhập hàng" và một Sản phẩm: bảng chỉ còn các lần nhập của Sản phẩm đó', async () => {
    renderPage();
    await within(table()).findByText('Trả hàng');

    await pickOption('Lọc theo loại giao dịch', 'Nhập hàng');
    await waitFor(() => expect(dataRows()).toHaveLength(4));
    await searchAndPickProduct('sua tuoi', 'Sữa tươi');

    await waitFor(() =>
      expect(lastLedgerQuery()).toEqual({
        variantId: MILK_VARIANT,
        documentType: 'PURCHASE_RECEIPT',
        page: '0',
        size: '20',
      }),
    );
    await waitFor(() => expect(rowSummaries()).toEqual(['Nhập hàng Sữa tươi +1.200']));
  });

  it('khoảng ngày 01/11–15/11 (chọn nhanh "Tháng này"): gửi fromDate=2026-11-01&toDate=2026-11-15', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 15/11/2026 theo giờ Việt Nam
    vi.setSystemTime(new Date('2026-11-15T05:00:00Z'));
    const { router } = renderPage();
    await within(table()).findByText('Trả hàng');

    const [start] = within(dateGroup()).getAllByRole('textbox');
    fireEvent.click(start);
    fireEvent.click(await screen.findByText('Tháng này'));

    await waitFor(() =>
      expect(lastLedgerQuery()).toEqual({ fromDate: '2026-11-01', toDate: '2026-11-15', page: '0', size: '20' }),
    );
    expect(urlParams(router)).toEqual({ from: '2026-11-01', to: '2026-11-15' });
    // Lần Tồn đầu kỳ ngày 20/10 nằm ngoài khoảng
    await waitFor(() => expect(dataRows()).toHaveLength(9));
    expect(rowTexts().some((cells) => cells[1] === 'Tồn đầu kỳ')).toBe(false);
    const inputs = within(dateGroup()).getAllByRole('textbox') as HTMLInputElement[];
    expect(inputs.map((input) => input.value)).toEqual(['01/11/2026', '15/11/2026']);
  });

  it('khoảng ngày quá 366 ngày (link gõ tay): không gửi, báo dưới ô ngày', async () => {
    const { router } = renderPage(`${PAGE_PATH}?from=2025-11-14&to=2026-11-15`);

    const alert = await within(dateGroup()).findByRole('alert');
    expect(alert.textContent).toBe('Khoảng ngày tối đa 366 ngày. Hãy chọn khoảng ngắn hơn.');
    expect(dateGroup().getAttribute('aria-describedby')).toBe(alert.id);
    expect(within(table()).getByText('Chọn lại khoảng ngày để xem lịch sử.')).toBeTruthy();
    expect(ledgerQueries()).toEqual([]);
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull();

    // Xóa bộ lọc: hết báo lỗi, tải lại bảng
    fireEvent.click(within(filterBar()).getByRole('button', { name: 'Xóa bộ lọc' }));
    await within(table()).findByText('Trả hàng');
    expect(within(dateGroup()).queryByRole('alert')).toBeNull();
    expect(ledgerQueries()).toEqual([{ page: '0', size: '20' }]);
    expect(router.state.location.search).toBe('');
  });

  it('đúng 366 ngày tính cả hai đầu thì vẫn gửi', async () => {
    renderPage(`${PAGE_PATH}?from=2025-11-15&to=2026-11-15`);
    await within(table()).findByText('Trả hàng');
    expect(ledgerQueries()).toEqual([{ fromDate: '2025-11-15', toDate: '2026-11-15', page: '0', size: '20' }]);
    expect(within(dateGroup()).queryByRole('alert')).toBeNull();
  });

  it('lỗi 400 của backend trên khoảng ngày hiện dưới ô ngày, không phải khung lỗi', async () => {
    rejectRange = true;
    renderPage(`${PAGE_PATH}?from=2026-11-01&to=2026-11-15`);

    const alert = await within(dateGroup()).findByRole('alert');
    expect(alert.textContent).toBe('Khoảng ngày phải theo thứ tự và tối đa 366 ngày.');
    expect(screen.queryByText('Dữ liệu nhập chưa hợp lệ.')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull();
    expect(within(table()).getByText('Chọn lại khoảng ngày để xem lịch sử.')).toBeTruthy();
  });

  it('mở từ link có bộ lọc: các ô hiện đúng, gửi đủ tham số ngay lần đầu', async () => {
    renderPage(
      `${PAGE_PATH}?type=PURCHASE_RECEIPT&productId=${MILK}&variantId=${MILK_VARIANT}&from=2026-11-01&to=2026-11-15`,
    );
    await waitFor(() => expect(rowSummaries()).toEqual(['Nhập hàng Sữa tươi +1.200']));

    // Không có lần tải nào thiếu bộ lọc
    expect(ledgerQueries()).toEqual([
      {
        variantId: MILK_VARIANT,
        documentType: 'PURCHASE_RECEIPT',
        fromDate: '2026-11-01',
        toDate: '2026-11-15',
        page: '0',
        size: '20',
      },
    ]);
    expect(within(filterBar()).getByText('Nhập hàng')).toBeTruthy();
    // Tên Sản phẩm lấy từ chi tiết Sản phẩm trên URL
    expect(await within(filterBar()).findByText('Sữa tươi')).toBeTruthy();
    expect(callsTo(PRODUCT_PATH).map((url) => url.pathname)).toEqual([`${PRODUCTS_PATH}/${MILK}`]);
    const inputs = within(dateGroup()).getAllByRole('textbox') as HTMLInputElement[];
    expect(inputs.map((input) => input.value)).toEqual(['01/11/2026', '15/11/2026']);
  });

  it('mở từ link có Sản phẩm nhiều Biến thể: ô chọn Biến thể hiện đúng Biến thể', async () => {
    renderPage(`${PAGE_PATH}?productId=${SHIRT}&variantId=${SHIRT_BLUE}`);
    await waitFor(() => expect(rowSummaries()).toEqual(['Nhập hàng Áo thunXanh / M +6']));
    expect(await within(filterBar()).findByText('Áo thun')).toBeTruthy();
    expect(await within(filterBar()).findByText('Xanh / M · AO-XANH-M')).toBeTruthy();
  });

  it('link chỉ có variantId: lọc đúng, ô chọn Sản phẩm hiện tên lấy từ sổ kho', async () => {
    renderPage(`${PAGE_PATH}?variantId=${WATER_VARIANT.toUpperCase()}`);
    await waitFor(() => expect(rowSummaries()).toEqual(['Kiểm kho Nước suối −2', 'Nhập hàng Nước suối +50']));
    expect(lastLedgerQuery()).toEqual({ variantId: WATER_VARIANT, page: '0', size: '20' });
    expect(within(filterBar()).getByText('Nước suối')).toBeTruthy();
    expect(callsTo(PRODUCT_PATH)).toHaveLength(0);
  });

  it('giá trị hỏng trên URL bị bỏ qua thay vì gửi đi', async () => {
    renderPage(`${PAGE_PATH}?type=TRANSFER&variantId=abc&productId=x-1&from=2026-02-30&to=2026-11-15`);
    await within(table()).findByText('Trả hàng');
    expect(ledgerQueries()).toEqual([{ page: '0', size: '20' }]);
    expect(within(filterBar()).queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();
  });

  it('productId không khớp variantId, hoặc Sản phẩm một Biến thể mà thiếu variantId: không tin productId', async () => {
    renderPage(`${PAGE_PATH}?productId=${MILK}&variantId=${WATER_VARIANT}`);
    await waitFor(() => expect(rowSummaries()).toEqual(['Kiểm kho Nước suối −2', 'Nhập hàng Nước suối +50']));
    await waitFor(() => expect(callsTo(PRODUCT_PATH)).toHaveLength(1));
    // Tên theo Biến thể đang lọc (lấy từ sổ kho), không phải tên Sản phẩm trên URL
    await waitFor(() => expect(selectedText('Lọc theo Sản phẩm')).toBe('Nước suối'));
    cleanup();

    renderPage(`${PAGE_PATH}?productId=${MILK}`);
    await within(table()).findByText('Trả hàng');
    await waitFor(() => expect(callsTo(PRODUCT_PATH)).toHaveLength(2));
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });
    // Như chưa chọn Sản phẩm (không hiện "Sữa tươi" mà bảng lại không lọc)
    await waitFor(() => expect(within(filterBar()).getByText('Mọi Sản phẩm (gõ để tìm)')).toBeTruthy());
    expect(within(filterBar()).queryByText('Sữa tươi')).toBeNull();
  });

  it('khoảng ngày ngược hoặc chỉ có một đầu trên URL cũng bị bỏ qua', async () => {
    renderPage(`${PAGE_PATH}?from=2026-11-15&to=2026-11-01`);
    await within(table()).findByText('Trả hàng');
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });
  });

  it('chỉ có ngày bắt đầu trên URL: bỏ qua', async () => {
    renderPage(`${PAGE_PATH}?from=2026-11-01`);
    await within(table()).findByText('Trả hàng');
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });
  });

  it('chỉ có ngày kết thúc trên URL: bỏ qua', async () => {
    renderPage(`${PAGE_PATH}?to=2026-11-15`);
    await within(table()).findByText('Trả hàng');
    expect(ledgerQueries()).toEqual([{ page: '0', size: '20' }]);
  });

  it('chưa có dòng nào: "Chưa có lần xuất nhập nào"', async () => {
    ledger = [];
    renderPage();
    expect(await within(table()).findByText('Chưa có lần xuất nhập nào.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();
  });

  it('có lọc mà không có dòng: "Không có dòng nào khớp bộ lọc", bấm "Xóa bộ lọc" thì bỏ hết bộ lọc', async () => {
    const { router } = renderPage(`${PAGE_PATH}?type=STOCKTAKE&productId=${MILK}&variantId=${MILK_VARIANT}`);
    expect(await within(table()).findByText('Không có dòng nào khớp bộ lọc.')).toBeTruthy();

    fireEvent.click(within(table()).getByRole('button', { name: 'Xóa bộ lọc' }));

    await within(table()).findByText('Trả hàng');
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });
    expect(router.state.location.search).toBe('');
    expect(within(filterBar()).queryByText('Sữa tươi')).toBeNull();
    expect(within(filterBar()).queryByText('Kiểm kho')).toBeNull();
  });

  it('lỗi tải (500): khung lỗi và nút "Thử lại"; thử lại được thì hiện bảng', async () => {
    failLedger = 'server';
    renderPage();
    expect(await screen.findByText('Máy chủ gặp lỗi.')).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();

    failLedger = null;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));

    await screen.findByRole('table');
    expect(await within(table()).findByText('Trả hàng')).toBeTruthy();
    expect(screen.queryByText('Máy chủ gặp lỗi.')).toBeNull();
    expect(ledgerQueries()).toHaveLength(2);
  });

  it('mất mạng: báo không kết nối được, có nút "Thử lại"', async () => {
    failLedger = 'network';
    renderPage();
    expect(await screen.findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Thử lại' })).toBeTruthy();
  });

  it("dùng khóa ['inventory', 'ledger', params]: invalidate ['inventory'] thì bảng tải lại", async () => {
    const { queryClient } = renderPage(`${PAGE_PATH}?type=SALE`);
    await within(table()).findByText('Sữa chua Ba Vì');
    expect(
      queryClient.getQueryData(['inventory', 'ledger', { documentType: 'SALE', page: 0, size: 20 }]),
    ).toBeTruthy();

    ledger.push(entry('2026-11-15T01:00:00Z', 'SALE', WATER_VARIANT, -4, 44));
    await act(() => queryClient.invalidateQueries({ queryKey: ['inventory'] }));

    await waitFor(() => expect(rowSummaries()[0]).toBe('Bán hàng Nước suối −4'));
    expect(ledgerQueries()).toHaveLength(2);
  });

  it('mở lại màn thì tải lại sổ kho, kể cả khi ứng dụng coi dữ liệu còn mới trong 30 giây', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } });
    renderPage(PAGE_PATH, queryClient);
    await within(table()).findByText('Trả hàng');
    cleanup();

    ledger.push(entry('2026-11-15T01:00:00Z', 'SALE', WATER_VARIANT, -4, 44));
    renderPage(PAGE_PATH, queryClient);

    await waitFor(() => expect(rowSummaries()[0]).toBe('Bán hàng Nước suối −4'));
    expect(ledgerQueries()).toHaveLength(2);
  });

  it('tải lại lỗi khi đã có bảng: giữ bảng, khung lỗi và "Thử lại" ở trên', async () => {
    const { queryClient } = renderPage();
    await within(table()).findByText('Trả hàng');

    failLedger = 'server';
    await act(() => queryClient.invalidateQueries({ queryKey: ['inventory', 'ledger'] }));

    expect(await screen.findByText('Máy chủ gặp lỗi.')).toBeTruthy();
    expect(within(table()).getByText('Trả hàng')).toBeTruthy();
    failLedger = null;
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(screen.queryByText('Máy chủ gặp lỗi.')).toBeNull());
    expect(within(table()).getByText('Trả hàng')).toBeTruthy();
  });

  it('đổi số dòng mỗi trang: gửi size mới, về trang đầu', async () => {
    ledger = Array.from({ length: 25 }, (_, i) =>
      entry(new Date(Date.UTC(2026, 10, 1, 2) + i * 3_600_000).toISOString(), 'SALE', MILK_VARIANT, -1, 100),
    );
    renderPage();
    await waitFor(() => expect(dataRows()).toHaveLength(20));
    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() => expect(lastLedgerQuery()).toEqual({ page: '1', size: '20' }));

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Page Size' }));
    const option = await waitFor(() => {
      const found = document.querySelector('.ant-select-item-option[title="50 / page"]');
      if (!found) throw new Error('chưa thấy lựa chọn 50 / page');
      return found;
    });
    fireEvent.click(option);

    await waitFor(() => expect(lastLedgerQuery()).toEqual({ page: '0', size: '50' }));
    await waitFor(() => expect(dataRows()).toHaveLength(25));
  });

  it('đổi bộ lọc giữ các tham số khác trên URL', async () => {
    const { router } = renderPage(`${PAGE_PATH}?tab=history&type=SALE`);
    await within(table()).findByText('Sữa chua Ba Vì');

    await pickOption('Lọc theo loại giao dịch', 'Nhập hàng');
    await waitFor(() => expect(urlParams(router)).toEqual({ tab: 'history', type: 'PURCHASE_RECEIPT' }));
    fireEvent.click(within(filterBar()).getByRole('button', { name: 'Xóa bộ lọc' }));
    await waitFor(() => expect(urlParams(router)).toEqual({ tab: 'history' }));

    const next = withStockLedgerFilters(new URLSearchParams('tab=x&type=SALE&from=2026-11-01&to=2026-11-15'), {
      variantId: MILK_VARIANT,
    });
    expect(next.toString()).toBe(`tab=x&variantId=${MILK_VARIANT}`);
  });

  it('quét mã vạch (Enter) trước khi hết thời gian gõ: tìm ngay, không chọn nhầm gợi ý cũ', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Trả hàng');
    const box = within(filterBar()).getByLabelText('Lọc theo Sản phẩm') as HTMLInputElement;
    fireEvent.mouseDown(box);
    await waitFor(() => expect(optionTitles()).toContain('Áo thun'));

    fireEvent.change(box, { target: { value: '8934673123456' } });
    // Chữ mới chưa được tìm: không còn gợi ý cũ để Enter chọn nhầm
    expect(optionTitles()).toEqual([]);
    expect(screen.getByText('Đang tìm…')).toBeTruthy();
    fireEvent.keyDown(box, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13 });
    // Enter không chọn gì, không đóng danh sách, không mất mã vừa quét
    expect(urlParams(router)).toEqual({});
    expect(box.value).toBe('8934673123456');
    // Tìm ngay, không chờ hết 300 ms
    await waitFor(() => expect(productSearches()).toEqual(['8934673123456']), { timeout: 250 });
    await waitFor(() => expect(optionTitles()).toEqual(['Sữa tươi']));
    fireEvent.click(document.querySelector('.ant-select-item-option[title="Sữa tươi"]') as HTMLElement);

    await waitFor(() => expect(lastLedgerQuery()).toEqual({ variantId: MILK_VARIANT, page: '0', size: '20' }));
    // Chọn xong thì xóa chữ đã gõ: mở lại thấy đủ gợi ý, không bị lọc theo mã cũ
    expect(box.value).toBe('');
    fireEvent.mouseDown(box);
    await waitFor(() => expect(optionTitles()).toEqual(expect.arrayContaining(['Áo thun', 'Nước suối', 'Sữa tươi'])));
  });

  it('đóng danh sách gợi ý thì xóa chữ đang gõ', async () => {
    renderPage();
    await within(table()).findByText('Trả hàng');
    const box = within(filterBar()).getByLabelText('Lọc theo Sản phẩm') as HTMLInputElement;
    fireEvent.mouseDown(box);
    fireEvent.change(box, { target: { value: 'nuoc' } });
    await waitFor(() => expect(optionTitles()).toEqual(['Nước suối']));

    fireEvent.keyDown(box, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27 });
    await waitFor(() => expect(box.value).toBe(''));
    fireEvent.mouseDown(box);
    await waitFor(() => expect(optionTitles()).toEqual(expect.arrayContaining(['Áo thun', 'Nước suối', 'Sữa tươi'])));
  });

  it('chi tiết Sản phẩm trên URL tải lỗi: báo dưới ô, "Tải lại" được', async () => {
    failProductDetail = true;
    renderPage(`${PAGE_PATH}?productId=${SHIRT}`);
    expect(await within(filterBar()).findByText('Không tải được Sản phẩm đã chọn. Máy chủ gặp lỗi.')).toBeTruthy();
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });

    failProductDetail = false;
    fireEvent.click(within(filterBar()).getByRole('button', { name: 'Tải lại' }));

    await waitFor(() => expect(selectedText('Lọc theo Sản phẩm')).toBe('Áo thun'));
    expect(within(filterBar()).getByText('Chọn Biến thể để lọc')).toBeTruthy();
    expect(within(filterBar()).queryByText(/Không tải được Sản phẩm/)).toBeNull();
  });

  it('Sản phẩm trên URL không còn (404): báo dưới ô', async () => {
    renderPage(`${PAGE_PATH}?productId=aaaaaaaa-0000-4000-8000-0000000000ff`);
    expect(
      await within(filterBar()).findByText('Không tải được Sản phẩm đã chọn. Không tìm thấy dữ liệu.'),
    ).toBeTruthy();
    expect(lastLedgerQuery()).toEqual({ page: '0', size: '20' });
  });
});
