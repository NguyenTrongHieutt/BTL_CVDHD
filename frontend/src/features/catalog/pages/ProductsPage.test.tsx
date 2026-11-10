import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import type { Category, CreateProductRequest, Product, ProductStatus } from '../../../shared/types/catalog';
import type { Me, Role } from '../../../shared/types/platform';
import { foldText } from '../components/textUtils';
import { ProductsPage } from './ProductsPage';

const PRODUCTS_PATH = '/api/v1/catalog/products';
const CATEGORIES_PATH = '/api/v1/catalog/categories';
const PAGE_PATH = '/catalog/products';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
/** Danh mục có id dạng UUID (có chữ, để thấy id viết hoa trên URL), đi qua được URL của bộ lọc. */
const DRINKS = 'aaaaaaaa-1111-4111-8111-111111111111';
const SNACKS = 'bbbbbbbb-2222-4222-8222-222222222222';
/** Bỏ dấu, chữ thường: như backend sắp, so khớp tên. */
const fold = foldText;

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

function product(n: number, overrides: Partial<Product> = {}): Product {
  return {
    id: `p-${n}`,
    name: `Sản phẩm ${String(n).padStart(2, '0')}`,
    categoryId: null,
    categoryName: null,
    unit: 'cái',
    description: null,
    hasVariants: false,
    status: 'ACTIVE',
    createdAt: '2026-11-10T03:00:00Z',
    updatedAt: '2026-11-10T03:00:00Z',
    variants: [
      { id: `v-${n}`, sku: `SP-0000000${n}`, barcode: null, name: null, salePrice: 1000 * n, isDefault: true, status: 'ACTIVE' },
    ],
    ...overrides,
  };
}

/** Sản phẩm của backend giả; thêm mới vào đầu mảng, còn danh sách trả về sắp theo tên như backend. */
let products: Product[];
let categories: Category[];
let nextId: number;
/** Lần POST tới trả lỗi mạng (fetch ném lỗi) thay vì gọi backend giả. */
let failNextPostWithNetworkError: boolean;
/** Lần POST tới được backend ghi nhưng phản hồi bị mất (lỗi mạng sau khi đã tạo). */
let loseNextPostResponse: boolean;
/** Lần ngừng kinh doanh tới trả lỗi mạng (fetch ném lỗi), không đổi gì. */
let failNextDeactivateWithNetworkError: boolean;
/** Lần ngừng kinh doanh tới được backend ghi (`INACTIVE`) nhưng phản hồi bị mất (lỗi mạng sau khi đã ngừng). */
let loseNextDeactivateResponse: boolean;
/** Danh sách Danh mục trả `500`. */
let failCategories: boolean;

const DEACTIVATE_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/deactivate$/;

/** Biến thể mặc định: giá, SKU, mã vạch của dòng trong bảng. */
function defaultOf(p: Product) {
  return p.variants.find((v) => v.isDefault) ?? p.variants[0];
}

/** Khóa sắp của `sort=<field>,asc|desc`; mặc định theo tên không dấu. */
function sortKey(sortField: string, p: Product): string | number {
  if (sortField === 'salePrice') return defaultOf(p).salePrice;
  if (sortField === 'createdAt') return p.createdAt;
  return fold(p.name);
}

/**
 * Danh sách như `GET /api/v1/catalog/products` của SP-05: `q` (tên chứa, không dấu; SKU, mã vạch bắt đầu bằng),
 * `categoryId`, `status`, `minPrice`/`maxPrice` (giá Biến thể mặc định, gồm hai đầu); `sort`, mặc định theo tên.
 */
function listProducts(params: URLSearchParams): Response {
  const q = params.get('q');
  const categoryId = params.get('categoryId');
  const status = params.get('status');
  const minPrice = params.get('minPrice') === null ? null : Number(params.get('minPrice'));
  const maxPrice = params.get('maxPrice') === null ? null : Number(params.get('maxPrice'));
  if (minPrice !== null && maxPrice !== null && minPrice > maxPrice) {
    return json(
      {
        type: '/errors/common/validation',
        title: 'Dữ liệu không hợp lệ',
        status: 400,
        requestId: 'req-400',
        errors: [{ field: 'minPrice', message: 'Giá thấp nhất không được lớn hơn giá cao nhất' }],
      },
      400,
    );
  }
  const startsWith = (code: string | null) => !!q && !!code && code.toUpperCase().startsWith(q.toUpperCase());
  // Như backend: mã của bất kỳ Biến thể nào; giá theo Biến thể mặc định
  const codeMatches = (p: Product) => p.variants.some((v) => startsWith(v.sku) || startsWith(v.barcode));
  const matching = products
    .filter((p) => {
      const variant = defaultOf(p);
      return (
        (!q || fold(p.name).includes(fold(q)) || codeMatches(p)) &&
        (!categoryId || p.categoryId === categoryId) &&
        (!status || p.status === status) &&
        (minPrice === null || variant.salePrice >= minPrice) &&
        (maxPrice === null || variant.salePrice <= maxPrice)
      );
    })
    .sort((a, b) => {
      const [sortField, direction] = (params.get('sort') ?? 'name,asc').split(',');
      const [x, y] = [sortKey(sortField, a), sortKey(sortField, b)];
      return (x < y ? -1 : x > y ? 1 : 0) * (direction === 'desc' ? -1 : 1);
    });
  const page = Number(params.get('page') ?? '0');
  const size = Number(params.get('size') ?? '20');
  return json({ items: matching.slice(page * size, (page + 1) * size), total: matching.length, page, size });
}

/**
 * Backend giả: danh sách có tìm kiếm, lọc, phân trang (SP-05); POST trả đúng mã lỗi của SP-01 và phát lại theo
 * `Idempotency-Key` (cùng nội dung thì trả lại Sản phẩm cũ, khác nội dung thì `409 idempotency-key-reuse`). Ngừng
 * kinh doanh (SP-03) đổi Sản phẩm và Biến thể sang `INACTIVE`, gọi lại vẫn `200`.
 */
function backend() {
  const byKey = new Map<string, { body: string; product: Product }>();
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (url.pathname === CATEGORIES_PATH && method === 'GET') {
      if (failCategories) {
        return json({ type: '/errors/common/internal', title: 'Lỗi hệ thống', status: 500, requestId: 'r' }, 500);
      }
      return json({ items: categories, total: categories.length, page: 0, size: 100 });
    }
    if (url.pathname === PRODUCTS_PATH && method === 'GET') {
      return listProducts(url.searchParams);
    }
    if (url.pathname === PRODUCTS_PATH && method === 'POST') {
      if (failNextPostWithNetworkError) {
        failNextPostWithNetworkError = false;
        throw new TypeError('Failed to fetch');
      }
      const key = new Headers(init?.headers).get('Idempotency-Key') ?? '';
      const earlier = byKey.get(key);
      if (earlier && earlier.body === String(init?.body)) {
        return json(earlier.product, 200, { 'Idempotent-Replay': 'true' });
      }
      if (earlier) {
        return json(
          { type: '/errors/common/idempotency-key-reuse', title: 'Idempotency-Key đã dùng', status: 409, requestId: 'r' },
          409,
        );
      }
      const body = JSON.parse(String(init?.body)) as CreateProductRequest;
      const codeTaken = (problemType: string, title: string) =>
        json({ type: problemType, title, status: 409, detail: title, requestId: 'req-409' }, 409);
      if (body.barcode && products.some((p) => p.variants.some((v) => v.barcode === body.barcode))) {
        return codeTaken('/errors/catalog/barcode-taken', 'Mã vạch đã có');
      }
      if (body.sku && products.some((p) => p.variants.some((v) => v.sku === body.sku))) {
        return codeTaken('/errors/catalog/sku-taken', 'SKU đã có');
      }
      if (body.categoryId === 'c-deleted') {
        return json(
          {
            type: '/errors/common/validation',
            title: 'Dữ liệu không hợp lệ',
            status: 400,
            requestId: 'req-400',
            errors: [{ field: 'categoryId', message: 'Danh mục không tồn tại. Hãy chọn lại Danh mục.' }],
          },
          400,
        );
      }
      const id = nextId++;
      const category = categories.find((c) => c.id === body.categoryId);
      const created: Product = {
        ...product(id),
        // Tạo sau mọi Sản phẩm có sẵn: đứng đầu khi sắp mới nhất trước
        createdAt: new Date(Date.UTC(2026, 10, 10, 5, 0, 0) + id * 1000).toISOString(),
        name: body.name,
        categoryId: category?.id ?? null,
        categoryName: category?.name ?? null,
        unit: body.unit ?? 'cái',
        description: body.description ?? null,
        variants: [
          {
            id: `v-${id}`,
            sku: body.sku ?? `SP-NEW0000${id}`,
            barcode: body.barcode ?? null,
            name: null,
            salePrice: body.salePrice,
            isDefault: true,
            status: 'ACTIVE',
          },
        ],
      };
      products = [created, ...products];
      byKey.set(key, { body: String(init?.body), product: created });
      if (loseNextPostResponse) {
        loseNextPostResponse = false;
        throw new TypeError('Failed to fetch');
      }
      return json(created, 201);
    }
    const deactivating = DEACTIVATE_PATH.exec(url.pathname);
    if (deactivating && method === 'POST') {
      if (failNextDeactivateWithNetworkError) {
        failNextDeactivateWithNetworkError = false;
        throw new TypeError('Failed to fetch');
      }
      const id = decodeURIComponent(deactivating[1]);
      const existing = products.find((p) => p.id === id);
      if (existing) {
        const stopped: Product = {
          ...existing,
          status: 'INACTIVE',
          variants: existing.variants.map((v) => ({ ...v, status: 'INACTIVE' })),
        };
        products = products.map((p) => (p.id === id ? stopped : p));
        if (loseNextDeactivateResponse) {
          loseNextDeactivateResponse = false;
          throw new TypeError('Failed to fetch');
        }
        return json(stopped);
      }
    }
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

function me(role: Role): Me {
  return { userId: 'u-1', storeId: 's-1', storeName: 'Cửa hàng thử', role, displayName: 'Người thử' };
}

/** Mở màn Sản phẩm ở `path` (có thể kèm query của bộ lọc). */
function renderPage(role: Role = 'OWNER', path = PAGE_PATH) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: PAGE_PATH, Component: ProductsPage }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <CurrentUserContext value={me(role)}>
          <RouterProvider router={router} />
        </CurrentUserContext>
      </AntdApp>
    </QueryClientProvider>,
  );
  return { queryClient, router };
}

/** Tham số query của từng lần tải danh sách Sản phẩm. */
function productQueries(): URLSearchParams[] {
  return fetchMock.mock.calls
    .map(([input, init]) => ({ url: new URL(String(input), 'http://localhost'), method: init?.method ?? 'GET' }))
    .filter(({ url, method }) => method === 'GET' && url.pathname === PRODUCTS_PATH)
    .map(({ url }) => url.searchParams);
}

function lastProductQuery(): Record<string, string> {
  const queries = productQueries();
  return Object.fromEntries(queries[queries.length - 1] ?? []);
}

function filterBar(): HTMLElement {
  return screen.getByRole('search', { name: 'Bộ lọc Sản phẩm' });
}

/** Chọn một lựa chọn của ô chọn trong thanh bộ lọc. */
async function chooseFilter(label: string, option: string) {
  fireEvent.mouseDown(within(filterBar()).getByLabelText(label));
  const found = await waitFor(() => {
    const options = [...document.querySelectorAll(`.ant-select-item-option[title="${option}"]`)];
    if (options.length === 0) throw new Error(`chưa thấy lựa chọn ${option}`);
    return options[options.length - 1];
  });
  fireEvent.click(found);
}

function setPrice(label: 'Giá từ' | 'Giá đến', value: string) {
  const input = within(filterBar()).getByLabelText(label);
  fireEvent.change(input, { target: { value } });
  fireEvent.blur(input);
}

function posts() {
  return fetchMock.mock.calls.filter(
    ([input, init]) =>
      init?.method === 'POST' && new URL(String(input), 'http://localhost').pathname === PRODUCTS_PATH,
  );
}

function deactivations() {
  return fetchMock.mock.calls.filter(
    ([input, init]) => init?.method === 'POST' && DEACTIVATE_PATH.test(new URL(String(input), 'http://localhost').pathname),
  );
}

function categoryGets(): number {
  return fetchMock.mock.calls.filter(
    ([input, init]) =>
      (init?.method ?? 'GET') === 'GET' && new URL(String(input), 'http://localhost').pathname === CATEGORIES_PATH,
  ).length;
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

function keyOf(call: Parameters<typeof fetch>): string | null {
  return new Headers(call[1]?.headers).get('Idempotency-Key');
}

function table(): HTMLElement {
  return screen.getByRole('table');
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function field(label: string): HTMLElement {
  return within(dialog()).getByLabelText(label);
}

function type(label: string, value: string) {
  fireEvent.change(field(label), { target: { value } });
}

function submit() {
  fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
}

/** Dòng của bảng có tên Sản phẩm này. */
function rowOf(name: string): HTMLElement {
  const row = within(table())
    .getAllByRole('row')
    .find((r) => within(r).queryAllByRole('cell')[0]?.textContent === name);
  if (!row) throw new Error(`không có dòng ${name}`);
  return row;
}

/** Hộp xác nhận của `useConfirm`; antd ghi tiêu đề hai lần (một bản cho `aria-labelledby`). */
async function findConfirm(title: string) {
  await screen.findAllByText(title);
}

/** Bấm nút của hộp xác nhận mở sau cùng (hộp cũ còn trong DOM khi jsdom không chạy hiệu ứng đóng). */
function clickConfirmButton(name: string) {
  const buttons = screen.getAllByRole('button', { name });
  fireEvent.click(buttons[buttons.length - 1]);
}

/** Tên Sản phẩm theo thứ tự các dòng của bảng. */
function rowNames(): string[] {
  return within(table())
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell')[0]?.textContent ?? '');
}

async function openAddDialog() {
  fireEvent.click(screen.getByRole('button', { name: /Thêm Sản phẩm/ }));
  await screen.findByRole('dialog');
}

async function chooseCategory(name: string) {
  fireEvent.mouseDown(field('Danh mục'));
  const option = await waitFor(() => {
    const found = document.querySelector(`.ant-select-item-option[title="${name}"]`);
    if (!found) throw new Error(`chưa thấy lựa chọn ${name}`);
    return found;
  });
  fireEvent.click(option);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  products = [product(2, { categoryId: 'c-1', categoryName: 'Đồ uống' }), product(1)];
  categories = [
    { id: 'c-1', name: 'Đồ uống', productCount: 1 },
    { id: 'c-2', name: 'Bánh kẹo', productCount: 0 },
  ];
  nextId = 100;
  failNextPostWithNetworkError = false;
  loseNextPostResponse = false;
  failNextDeactivateWithNetworkError = false;
  loseNextDeactivateResponse = false;
  failCategories = false;
  backend();
});

describe('ProductsPage', () => {
  it('hiện bảng sắp theo tên: tên, Danh mục, SKU, giá bán; không có Giá vốn; không gửi bộ lọc trống', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    expect(rowNames()).toEqual(['Sản phẩm 01', 'Sản phẩm 02']);
    // Không có `sort`: backend sắp theo tên không dấu
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
    expect(within(table()).getByText('Đồ uống')).toBeTruthy();
    expect(within(table()).getByText('SP-00000002')).toBeTruthy();
    expect(within(table()).getByText(/2\.000/)).toBeTruthy();
    expect(within(table()).getAllByText('Đang bán')).toHaveLength(2);
    expect(screen.queryByText(/Giá vốn/)).toBeNull();
  });

  it('Nhân viên thấy bảng nhưng không thấy nút "Thêm Sản phẩm"', async () => {
    renderPage('STAFF');
    expect(await within(table()).findByText('Sản phẩm 01')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Thêm Sản phẩm/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Ngừng kinh doanh/ })).toBeNull();
    expect(within(table()).queryByText('Thao tác')).toBeNull();
    expect(within(table()).getAllByText('Đang bán')).toHaveLength(2);
  });

  it('Chủ cửa hàng thêm đủ thông tin: gửi đúng body và Idempotency-Key, đóng form thì Sản phẩm mới nằm đầu bảng', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', '  Sữa tươi Vinamilk 1L ');
    await chooseCategory('Đồ uống');
    type('Đơn vị tính', ' hộp ');
    type('Giá bán', '15000');
    // Tự nhóm nghìn bằng dấu chấm khi gõ
    expect((field('Giá bán') as HTMLInputElement).value).toBe('15.000');
    type('SKU', 'VNM-1L');
    type('Mã vạch', '8934673123456');
    type('Tồn kho đầu kỳ', '24');
    type('Giá vốn ban đầu', '11.000');
    type('Mô tả', 'Sữa tươi tiệt trùng');
    const categoryLoads = categoryGets();
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // Số Sản phẩm của Danh mục đã đổi: danh sách Danh mục được tải lại
    expect(categoryGets()).toBeGreaterThan(categoryLoads);
    expect(posts()).toHaveLength(1);
    const [post] = posts();
    expect(bodyOf(post)).toEqual({
      name: 'Sữa tươi Vinamilk 1L',
      categoryId: 'c-1',
      unit: 'hộp',
      salePrice: 15000,
      sku: 'VNM-1L',
      barcode: '8934673123456',
      openingQuantity: 24,
      openingUnitCost: 11000,
      description: 'Sữa tươi tiệt trùng',
    });
    expect(keyOf(post)).toMatch(UUID);
    expect(await screen.findByText('Đã thêm Sản phẩm "Sữa tươi Vinamilk 1L"')).toBeTruthy();
    // Thêm xong bảng sắp mới nhất trước: Sản phẩm mới đứng đầu dù theo tên nó đứng cuối
    await waitFor(() => expect(rowNames()[0]).toBe('Sữa tươi Vinamilk 1L'));
    expect(rowNames()).toHaveLength(3);
    expect(lastProductQuery()).toEqual({ page: '0', size: '20', sort: 'createdAt,desc' });
  });

  it('chỉ nhập tên và giá: trường tùy chọn không gửi, tồn đầu kỳ mặc định 0', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Bút bi');
    type('Giá bán', '5000');
    type('SKU', '   ');
    submit();

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(bodyOf(posts()[0])).toEqual({ name: 'Bút bi', salePrice: 5000, openingQuantity: 0, openingUnitCost: 0 });
    await waitFor(() => expect(rowNames()[0]).toBe('Bút bi'));
  });

  it('kiểm tra phía client: thiếu tên, giá; có tồn đầu mà Giá vốn 0; mã vạch có ký tự lạ thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    submit();
    expect(await within(dialog()).findByText('Nhập tên Sản phẩm')).toBeTruthy();
    expect(within(dialog()).getByText('Nhập giá bán')).toBeTruthy();

    type('Tên Sản phẩm', 'a'.repeat(201));
    type('Giá bán', '1000');
    type('Tồn kho đầu kỳ', '5');
    type('Mã vạch', '893-456');
    submit();
    expect(await within(dialog()).findByText('Tên Sản phẩm tối đa 200 ký tự')).toBeTruthy();
    expect(within(dialog()).getByText('Có Tồn kho đầu kỳ thì nhập Giá vốn ban đầu lớn hơn 0')).toBeTruthy();
    expect(within(dialog()).getByText('Mã vạch chỉ gồm chữ cái không dấu và chữ số')).toBeTruthy();
    expect(posts()).toHaveLength(0);
  });

  it('ô số: dấu phẩy, dấu trừ bị báo lỗi chứ không bị bỏ; tồn đầu kỳ × Giá vốn quá lớn cũng bị chặn', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Hàng thử số');
    type('Giá bán', '12,5');
    type('Tồn kho đầu kỳ', '-5');
    submit();
    expect(await within(dialog()).findByText('Giá bán phải là số nguyên')).toBeTruthy();
    expect(within(dialog()).getByText('Tồn kho đầu kỳ không được âm')).toBeTruthy();
    // Không bị đổi thành 125 hay 5
    expect((field('Giá bán') as HTMLInputElement).value).toBe('12,5');
    expect((field('Tồn kho đầu kỳ') as HTMLInputElement).value).toBe('-5');

    type('Giá bán', '1000');
    type('Tồn kho đầu kỳ', '2000000');
    type('Giá vốn ban đầu', '5000000000');
    submit();
    expect(await within(dialog()).findByText('Tồn kho đầu kỳ × Giá vốn ban đầu quá lớn')).toBeTruthy();
    expect(posts()).toHaveLength(0);
  });

  it('tên gõ dạng tổ hợp NFD được đếm và gửi theo NFC, ký tự vô hình bị bỏ', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    const name = 'ệ'.repeat(200);
    const decomposed = 'ệ'.normalize('NFD');
    expect(decomposed).not.toBe('ệ');
    const zeroWidthSpace = String.fromCodePoint(0x200b);
    type('Tên Sản phẩm', `${decomposed.repeat(100)}${zeroWidthSpace}${decomposed.repeat(100)}`);
    type('Giá bán', '1000');
    submit();

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(bodyOf(posts()[0])).toMatchObject({ name });
  });

  it('trùng mã vạch: báo dưới ô Mã vạch, giữ form; gửi lại sau khi sửa dùng khóa mới', async () => {
    products = [product(3, { variants: [{ ...product(3).variants[0], barcode: '8935001800019' }] }), ...products];
    renderPage();
    await within(table()).findByText('Sản phẩm 03');

    await openAddDialog();
    type('Tên Sản phẩm', 'Bia Hà Nội');
    type('Giá bán', '12000');
    type('Mã vạch', '8935001800019');
    submit();

    expect(await within(dialog()).findByText('Cửa hàng đã có Sản phẩm dùng mã vạch này.')).toBeTruthy();
    expect(field('Mã vạch').getAttribute('aria-invalid')).toBe('true');
    expect(within(dialog()).queryByRole('alert')).toBeNull();

    type('Mã vạch', '8935001800026');
    submit();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const [first, second] = posts();
    expect(keyOf(second)).not.toBe(keyOf(first));
    await waitFor(() => expect(rowNames()[0]).toBe('Bia Hà Nội'));
  });

  it('trùng SKU: báo dưới ô SKU', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Hàng trùng SKU');
    type('Giá bán', '1000');
    type('SKU', 'SP-00000001');
    submit();

    expect(await within(dialog()).findByText('Cửa hàng đã có Sản phẩm dùng SKU này.')).toBeTruthy();
    expect(field('SKU').getAttribute('aria-invalid')).toBe('true');
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng', async () => {
    categories = [...categories, { id: 'c-deleted', name: 'Đã bị xóa', productCount: 0 }];
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Hàng thử');
    type('Giá bán', '1000');
    await chooseCategory('Đã bị xóa');
    submit();

    expect(await within(dialog()).findByText('Danh mục không tồn tại. Hãy chọn lại Danh mục.')).toBeTruthy();
    expect(within(dialog()).queryByRole('alert')).toBeNull();
    expect(screen.getByRole('dialog')).toBeTruthy();

    // Chọn Danh mục khác thì lỗi cũ biến mất
    await chooseCategory('Bánh kẹo');
    await waitFor(() =>
      expect(within(dialog()).queryByText('Danh mục không tồn tại. Hãy chọn lại Danh mục.')).toBeNull(),
    );
  });

  it('mất phản hồi rồi sửa form gửi lại (khóa cũ, nội dung khác): báo có thể đã tạo và tải lại bảng', async () => {
    loseNextPostResponse = true;
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Nước mắm');
    type('Giá bán', '35000');
    submit();
    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();

    type('Giá bán', '36000');
    submit();
    expect(
      await within(dialog()).findByText('Sản phẩm có thể đã được tạo ở lần gửi trước, hãy kiểm tra bảng.'),
    ).toBeTruthy();
    const [first, second] = posts();
    expect(keyOf(second)).toBe(keyOf(first));
    // Bảng phía sau đã tải lại và có Sản phẩm của lần gửi đầu
    await waitFor(() => expect(rowNames()[0]).toBe('Nước mắm'));
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('mất mạng: báo ở khung lỗi, gửi lại dùng cùng Idempotency-Key và không tạo trùng', async () => {
    failNextPostWithNetworkError = true;
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Gạo ST25');
    type('Giá bán', '180000');
    submit();
    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();

    submit();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const [first, second] = posts();
    expect(keyOf(first)).toMatch(UUID);
    expect(keyOf(second)).toBe(keyOf(first));
    await waitFor(() => expect(rowNames()[0]).toBe('Gạo ST25'));
    expect(products.filter((p) => p.name === 'Gạo ST25')).toHaveLength(1);
  });

  it('đang ở trang sau mà thêm Sản phẩm: bảng về trang đầu, Sản phẩm mới nằm đầu bảng', async () => {
    products = Array.from({ length: 25 }, (_, i) => product(25 - i));
    renderPage();
    await within(table()).findByText('Sản phẩm 01');

    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    expect(await within(table()).findByText('Sản phẩm 21')).toBeTruthy();

    await openAddDialog();
    type('Tên Sản phẩm', 'Hàng mới nhất');
    type('Giá bán', '1000');
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rowNames()[0]).toBe('Hàng mới nhất'));
    expect(rowNames()[1]).toBe('Sản phẩm 25');
    expect(lastProductQuery()).toMatchObject({ page: '0', sort: 'createdAt,desc' });
  });

  it('Chủ cửa hàng ngừng kinh doanh có xác nhận: dòng đổi sang "Ngừng kinh doanh" ngay, nút biến mất', async () => {
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 01');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.click(within(rowOf('Sản phẩm 01')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' }));
    await findConfirm('Ngừng kinh doanh "Sản phẩm 01"?');
    // Nói rõ hậu quả: ẩn khỏi màn bán hàng và tìm kiếm, lịch sử vẫn giữ
    expect(screen.getByText(/ẩn khỏi màn bán hàng và kết quả tìm kiếm/)).toBeTruthy();
    expect(screen.getByText(/Lịch sử Đơn hàng, Tồn kho và báo cáo vẫn được giữ/)).toBeTruthy();
    clickConfirmButton('Ngừng kinh doanh');

    expect(await screen.findByText('Đã ngừng kinh doanh "Sản phẩm 01"')).toBeTruthy();
    await waitFor(() => expect(within(rowOf('Sản phẩm 01')).getByText('Ngừng kinh doanh')).toBeTruthy());
    expect(within(rowOf('Sản phẩm 01')).queryByText('Đang bán')).toBeNull();
    expect(within(rowOf('Sản phẩm 01')).queryByRole('button')).toBeNull();
    // Dòng khác không đổi, vẫn ngừng được; bảng vẫn ở chỗ cũ
    expect(within(rowOf('Sản phẩm 02')).getByText('Đang bán')).toBeTruthy();
    expect(within(rowOf('Sản phẩm 02')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 02' })).toBeTruthy();
    expect(rowNames()).toEqual(['Sản phẩm 01', 'Sản phẩm 02']);

    expect(deactivations()).toHaveLength(1);
    const [call] = deactivations();
    expect(new URL(String(call[0]), 'http://localhost').pathname).toBe(`${PRODUCTS_PATH}/p-1/deactivate`);
    expect(call[1]?.body).toBeUndefined();
    expect(keyOf(call)).toBeNull();
    // Tồn kho và hàng bán được cũng tải lại: Sản phẩm ẩn khỏi màn bán hàng
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });
  });

  it('bấm "Không" ở hộp xác nhận thì không gửi gì, dòng giữ nguyên', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 01');

    fireEvent.click(screen.getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' }));
    await findConfirm('Ngừng kinh doanh "Sản phẩm 01"?');
    clickConfirmButton('Không');

    await waitFor(() => expect(within(rowOf('Sản phẩm 01')).getByText('Đang bán')).toBeTruthy());
    expect(deactivations()).toHaveLength(0);
    expect(within(rowOf('Sản phẩm 01')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' })).toBeTruthy();
  });

  it('Sản phẩm đã ngừng kinh doanh: hiện thẻ "Ngừng kinh doanh", không có nút', async () => {
    products = [product(2), product(1, { status: 'INACTIVE' })];
    renderPage();
    await within(table()).findByText('Sản phẩm 01');

    expect(within(rowOf('Sản phẩm 01')).getByText('Ngừng kinh doanh')).toBeTruthy();
    expect(within(rowOf('Sản phẩm 01')).queryByRole('button')).toBeNull();
    expect(within(rowOf('Sản phẩm 02')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 02' })).toBeTruthy();
  });

  it('mất mạng khi ngừng kinh doanh: báo ở khung lỗi, đóng được, dòng vẫn "Đang bán"', async () => {
    failNextDeactivateWithNetworkError = true;
    renderPage();
    await within(table()).findByText('Sản phẩm 01');

    fireEvent.click(screen.getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' }));
    await findConfirm('Ngừng kinh doanh "Sản phẩm 01"?');
    clickConfirmButton('Ngừng kinh doanh');

    expect(await screen.findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(within(rowOf('Sản phẩm 01')).getByText('Đang bán')).toBeTruthy();
    expect(within(rowOf('Sản phẩm 01')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' })).toBeTruthy();
    expect(products.find((p) => p.id === 'p-1')?.status).toBe('ACTIVE');

    const alert = screen.getAllByRole('alert').find((el) => el.textContent?.includes('Không kết nối được máy chủ.'));
    expect(alert).toBeTruthy();
    fireEvent.click(within(alert as HTMLElement).getByRole('button'));
    await waitFor(() => expect(screen.queryByText('Không kết nối được máy chủ.')).toBeNull());
  });

  it('mất phản hồi sau khi máy chủ đã ngừng: báo lỗi mạng, bảng tải lại và dòng thành "Ngừng kinh doanh"', async () => {
    loseNextDeactivateResponse = true;
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 01');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.click(screen.getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 01' }));
    await findConfirm('Ngừng kinh doanh "Sản phẩm 01"?');
    clickConfirmButton('Ngừng kinh doanh');

    expect(await screen.findByText('Không kết nối được máy chủ.')).toBeTruthy();
    await waitFor(() => expect(within(rowOf('Sản phẩm 01')).getByText('Ngừng kinh doanh')).toBeTruthy());
    expect(within(rowOf('Sản phẩm 01')).queryByText('Đang bán')).toBeNull();
    expect(within(rowOf('Sản phẩm 01')).queryByRole('button')).toBeNull();
    expect(deactivations()).toHaveLength(1);
    // Tồn kho và hàng bán được cũng tải lại, vì máy chủ có thể đã ngừng
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });
  });
});

/** Sản phẩm một Biến thể mặc định với giá, Danh mục, mã vạch, trạng thái cho trước. */
function shopItem(
  n: number,
  name: string,
  salePrice: number,
  categoryId: string | null,
  extra: { barcode?: string; status?: ProductStatus } = {},
): Product {
  const base = product(n);
  const status = extra.status ?? 'ACTIVE';
  return {
    ...base,
    name,
    categoryId,
    categoryName: categories.find((c) => c.id === categoryId)?.name ?? null,
    status,
    variants: [{ ...base.variants[0], salePrice, barcode: extra.barcode ?? null, status }],
  };
}

function searchBox(): HTMLInputElement {
  return within(filterBar()).getByLabelText('Tìm Sản phẩm') as HTMLInputElement;
}

function priceBox(label: 'Giá từ' | 'Giá đến'): HTMLInputElement {
  return within(filterBar()).getByLabelText(label) as HTMLInputElement;
}

function filterParams(router: ReturnType<typeof renderPage>['router']): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(router.state.location.search));
}

describe('ProductsPage — tìm kiếm, lọc (FR-5, SP-05)', () => {
  beforeEach(() => {
    categories = [
      { id: DRINKS, name: 'Đồ uống', productCount: 4 },
      { id: SNACKS, name: 'Bánh kẹo', productCount: 1 },
    ];
    products = [
      shopItem(1, 'Sữa tươi Vinamilk', 15000, DRINKS, { barcode: '8934673123456' }),
      shopItem(2, 'Sữa chua Ba Vì', 8000, DRINKS),
      shopItem(3, 'Nước suối Lavie', 5000, DRINKS),
      shopItem(4, 'Bánh quy bơ', 30000, SNACKS),
      shopItem(5, 'Sữa đặc ngừng bán', 25000, DRINKS, { status: 'INACTIVE' }),
    ];
  });

  it('Nhân viên gõ "sua": bảng chỉ còn Sản phẩm có "Sữa" trong tên, gửi một lần sau khi gõ xong, URL giữ từ khóa', async () => {
    const { router } = renderPage('STAFF');
    await within(table()).findByText('Nước suối Lavie');

    fireEvent.change(searchBox(), { target: { value: 's' } });
    fireEvent.change(searchBox(), { target: { value: 'su' } });
    fireEvent.change(searchBox(), { target: { value: ' sua ' } });

    await waitFor(() => expect(rowNames()).toEqual(['Sữa chua Ba Vì', 'Sữa đặc ngừng bán', 'Sữa tươi Vinamilk']));
    // Debounce: các lần gõ dồn thành một yêu cầu, từ khóa đã cắt khoảng trắng
    expect(productQueries().filter((query) => query.has('q')).map((query) => query.get('q'))).toEqual(['sua']);
    expect(lastProductQuery()).toEqual({ q: 'sua', page: '0', size: '20' });
    expect(filterParams(router)).toEqual({ q: 'sua' });
  });

  it('quét mã vạch (Enter) thì tìm theo đầu mã vạch', async () => {
    renderPage();
    await within(table()).findByText('Nước suối Lavie');

    fireEvent.change(searchBox(), { target: { value: '8934' } });
    fireEvent.keyDown(searchBox(), { key: 'Enter', code: 'Enter', keyCode: 13 });

    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk']));
    expect(lastProductQuery().q).toBe('8934');
  });

  it('lọc theo Danh mục, khoảng giá, trạng thái: gửi đúng tham số, ghi lên URL, không còn hàng thì báo khớp bộ lọc', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Bánh quy bơ');

    await chooseFilter('Lọc theo Danh mục', 'Đồ uống');
    await waitFor(() =>
      expect(rowNames()).toEqual(['Nước suối Lavie', 'Sữa chua Ba Vì', 'Sữa đặc ngừng bán', 'Sữa tươi Vinamilk']),
    );
    expect(lastProductQuery()).toEqual({ categoryId: DRINKS, page: '0', size: '20' });

    setPrice('Giá từ', '10000');
    // Tự nhóm nghìn khi gõ
    expect(priceBox('Giá từ').value).toBe('10.000');
    await waitFor(() => expect(rowNames()).toEqual(['Sữa đặc ngừng bán', 'Sữa tươi Vinamilk']));
    setPrice('Giá đến', '20.000');
    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk']));
    expect(lastProductQuery()).toEqual({
      categoryId: DRINKS,
      minPrice: '10000',
      maxPrice: '20000',
      page: '0',
      size: '20',
    });

    await chooseFilter('Lọc theo trạng thái', 'Ngừng kinh doanh');
    expect(await within(table()).findByText('Không có Sản phẩm nào khớp bộ lọc.')).toBeTruthy();
    expect(lastProductQuery().status).toBe('INACTIVE');
    expect(filterParams(router)).toEqual({
      categoryId: DRINKS,
      minPrice: '10000',
      maxPrice: '20000',
      status: 'INACTIVE',
    });
  });

  it('mở trang từ link có bộ lọc: gửi đủ tham số ngay lần đầu, các ô hiện đúng giá trị', async () => {
    renderPage('OWNER', `${PAGE_PATH}?q=sua&categoryId=${DRINKS}&status=ACTIVE&minPrice=10000&maxPrice=20000`);
    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk']));

    // Không có lần tải nào thiếu bộ lọc
    expect(productQueries().map((query) => Object.fromEntries(query))).toEqual([
      { q: 'sua', categoryId: DRINKS, status: 'ACTIVE', minPrice: '10000', maxPrice: '20000', page: '0', size: '20' },
    ]);
    expect(searchBox().value).toBe('sua');
    expect(priceBox('Giá từ').value).toBe('10.000');
    expect(priceBox('Giá đến').value).toBe('20.000');
    expect(await within(filterBar()).findByText('Đồ uống')).toBeTruthy();
    expect(within(filterBar()).getByText('Đang bán')).toBeTruthy();
  });

  it('giá trị sai trên URL bị bỏ qua thay vì gửi đi', async () => {
    renderPage('OWNER', `${PAGE_PATH}?q=%20%20&categoryId=c-1&status=DELETED&minPrice=abc&maxPrice=-5`);
    await within(table()).findByText('Bánh quy bơ');
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
  });

  it('khoảng giá ngược trên URL cũng bị bỏ qua', async () => {
    renderPage('OWNER', `${PAGE_PATH}?minPrice=5&maxPrice=1`);
    await within(table()).findByText('Bánh quy bơ');
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
  });

  it('ô giá: giá từ lớn hơn giá đến hay giá không phải số nguyên thì báo dưới ô và không gửi', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Bánh quy bơ');

    setPrice('Giá từ', '20000');
    await waitFor(() => expect(lastProductQuery().minPrice).toBe('20000'));
    setPrice('Giá đến', '10000');
    expect(await within(filterBar()).findByText('Giá từ không được lớn hơn giá đến')).toBeTruthy();
    expect(priceBox('Giá từ').getAttribute('aria-invalid')).toBe('true');

    setPrice('Giá đến', '12,5');
    expect(await within(filterBar()).findByText('Giá đến phải là số nguyên')).toBeTruthy();
    setPrice('Giá đến', '-3');
    expect(await within(filterBar()).findByText('Giá đến không được âm')).toBeTruthy();
    expect(productQueries().some((query) => query.has('maxPrice'))).toBe(false);
    expect(filterParams(router)).toEqual({ minPrice: '20000' });

    setPrice('Giá đến', '30000');
    await waitFor(() => expect(rowNames()).toEqual(['Bánh quy bơ', 'Sữa đặc ngừng bán']));
    expect(within(filterBar()).queryByText(/không được|phải là/)).toBeNull();
    expect(priceBox('Giá từ').getAttribute('aria-invalid')).toBe('false');
    expect(lastProductQuery()).toMatchObject({ minPrice: '20000', maxPrice: '30000' });
  });

  it('đổi bộ lọc khi đang ở trang sau thì bảng về trang đầu', async () => {
    products = Array.from({ length: 25 }, (_, i) =>
      shopItem(i + 1, `Hàng ${String(i + 1).padStart(2, '0')}`, 1000, DRINKS),
    );
    renderPage();
    await within(table()).findByText('Hàng 01');
    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    expect(await within(table()).findByText('Hàng 21')).toBeTruthy();

    await chooseFilter('Lọc theo trạng thái', 'Đang bán');
    await waitFor(() => expect(lastProductQuery()).toEqual({ status: 'ACTIVE', page: '0', size: '20' }));
    expect(await within(table()).findByText('Hàng 01')).toBeTruthy();
  });

  it('xóa chữ trong ô tìm kiếm thì bỏ từ khóa khỏi yêu cầu và URL', async () => {
    const { router } = renderPage('OWNER', `${PAGE_PATH}?q=sua`);
    await waitFor(() => expect(rowNames()).toHaveLength(3));

    fireEvent.change(searchBox(), { target: { value: '' } });
    await waitFor(() => expect(rowNames()).toHaveLength(5));
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
    expect(router.state.location.search).toBe('');
  });

  it('URL đổi từ nơi khác (bấm lại menu "Sản phẩm"): các ô về trống, bảng bỏ bộ lọc', async () => {
    const { router } = renderPage('OWNER', `${PAGE_PATH}?q=sua&minPrice=10000&status=ACTIVE`);
    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk']));

    await act(() => router.navigate(PAGE_PATH));

    await waitFor(() => expect(rowNames()).toHaveLength(5));
    expect(searchBox().value).toBe('');
    expect(priceBox('Giá từ').value).toBe('');
    expect(within(filterBar()).queryByText('Đang bán')).toBeNull();
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
  });

  it('thêm Sản phẩm khi đang lọc: bỏ bộ lọc, sắp mới nhất trước, Sản phẩm mới nằm đầu bảng', async () => {
    const { router } = renderPage('OWNER', `${PAGE_PATH}?q=sua&status=INACTIVE`);
    await waitFor(() => expect(rowNames()).toEqual(['Sữa đặc ngừng bán']));

    await openAddDialog();
    // Không khớp bộ lọc đang dùng
    type('Tên Sản phẩm', 'Bánh mới');
    type('Giá bán', '9000');
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rowNames()[0]).toBe('Bánh mới'));
    expect(lastProductQuery()).toEqual({ page: '0', size: '20', sort: 'createdAt,desc' });
    expect(router.state.location.search).toBe('');
    expect(searchBox().value).toBe('');
    // Cột Ngày tạo hiện đang sắp giảm dần
    expect(within(table()).getByRole('columnheader', { name: /Ngày tạo/ }).getAttribute('aria-sort')).toBe(
      'descending',
    );
  });

  it('bấm tiêu đề cột Tên, Giá bán, Ngày tạo thì sắp phía server', async () => {
    products = [
      { ...shopItem(1, 'Sữa tươi Vinamilk', 15000, DRINKS), createdAt: '2026-11-08T03:00:00Z' },
      { ...shopItem(2, 'Bánh quy bơ', 30000, SNACKS), createdAt: '2026-11-09T18:00:00Z' },
      { ...shopItem(3, 'Nước suối Lavie', 5000, DRINKS), createdAt: '2026-11-07T03:00:00Z' },
    ];
    renderPage();
    await waitFor(() => expect(rowNames()).toEqual(['Bánh quy bơ', 'Nước suối Lavie', 'Sữa tươi Vinamilk']));
    // Ngày theo giờ Việt Nam: 18:00 UTC ngày 9 là ngày 10
    expect(within(rowOf('Bánh quy bơ')).getByText('10/11/2026')).toBeTruthy();

    const header = (title: string) => within(table()).getByRole('columnheader', { name: new RegExp(title) });
    fireEvent.click(header('Tên Sản phẩm'));
    await waitFor(() => expect(lastProductQuery().sort).toBe('name,asc'));
    fireEvent.click(header('Tên Sản phẩm'));
    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk', 'Nước suối Lavie', 'Bánh quy bơ']));
    expect(lastProductQuery().sort).toBe('name,desc');

    fireEvent.click(header('Giá bán'));
    await waitFor(() => expect(rowNames()).toEqual(['Nước suối Lavie', 'Sữa tươi Vinamilk', 'Bánh quy bơ']));
    expect(lastProductQuery().sort).toBe('salePrice,asc');

    fireEvent.click(header('Ngày tạo'));
    // Cùng thứ tự với bước trước, nên chờ yêu cầu chứ không chờ dòng
    await waitFor(() => expect(lastProductQuery().sort).toBe('createdAt,asc'));
    await waitFor(() => expect(rowNames()).toEqual(['Nước suối Lavie', 'Sữa tươi Vinamilk', 'Bánh quy bơ']));
    fireEvent.click(header('Ngày tạo'));
    await waitFor(() => expect(rowNames()).toEqual(['Bánh quy bơ', 'Sữa tươi Vinamilk', 'Nước suối Lavie']));
  });

  it('Cửa hàng chưa có Sản phẩm, không lọc: hiện hướng dẫn thêm Sản phẩm', async () => {
    products = [];
    renderPage();
    expect(await within(table()).findByText('Chưa có Sản phẩm nào. Bấm "Thêm Sản phẩm" để tạo.')).toBeTruthy();
    expect(within(table()).queryByText('Không có Sản phẩm nào khớp bộ lọc.')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();
  });

  it('Cửa hàng chưa có Sản phẩm nhưng đang lọc (?status=ACTIVE): báo không khớp bộ lọc', async () => {
    products = [];
    renderPage('OWNER', `${PAGE_PATH}?status=ACTIVE`);
    expect(await within(table()).findByText('Không có Sản phẩm nào khớp bộ lọc.')).toBeTruthy();
    expect(within(table()).queryByText(/Bấm "Thêm Sản phẩm"/)).toBeNull();
  });

  it('nút "Xóa bộ lọc" ở bảng trống bỏ mọi bộ lọc', async () => {
    const { router } = renderPage('OWNER', `${PAGE_PATH}?q=khong%20co&status=ACTIVE`);
    expect(await within(table()).findByText('Không có Sản phẩm nào khớp bộ lọc.')).toBeTruthy();

    fireEvent.click(within(table()).getByRole('button', { name: 'Xóa bộ lọc' }));

    await waitFor(() => expect(rowNames()).toHaveLength(5));
    expect(router.state.location.search).toBe('');
    expect(searchBox().value).toBe('');
    expect(lastProductQuery()).toEqual({ page: '0', size: '20' });
  });

  it('nút "Xóa bộ lọc" trên thanh bộ lọc bỏ mọi bộ lọc và chữ trong các ô, chỉ hiện khi đang lọc', async () => {
    const { router } = renderPage('OWNER', `${PAGE_PATH}?q=sua&minPrice=10000&status=ACTIVE&categoryId=${DRINKS}`);
    await waitFor(() => expect(rowNames()).toEqual(['Sữa tươi Vinamilk']));

    fireEvent.click(within(filterBar()).getByRole('button', { name: 'Xóa bộ lọc' }));

    await waitFor(() => expect(rowNames()).toHaveLength(5));
    expect(router.state.location.search).toBe('');
    expect(searchBox().value).toBe('');
    expect(priceBox('Giá từ').value).toBe('');
    expect(within(filterBar()).queryByText('Đồ uống')).toBeNull();
    expect(within(filterBar()).queryByRole('button', { name: 'Xóa bộ lọc' })).toBeNull();
  });

  it('đổi bộ lọc thay URL hiện tại (REPLACE), không thêm bước "Quay lại"', async () => {
    const { router } = renderPage();
    await within(table()).findByText('Bánh quy bơ');

    await chooseFilter('Lọc theo trạng thái', 'Đang bán');
    await waitFor(() => expect(filterParams(router)).toEqual({ status: 'ACTIVE' }));
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('Danh mục trên URL viết hoa vẫn là Danh mục đó; gửi id chữ thường', async () => {
    renderPage('OWNER', `${PAGE_PATH}?categoryId=${DRINKS.toUpperCase()}`);
    await waitFor(() => expect(rowNames()).toHaveLength(4));
    expect(lastProductQuery().categoryId).toBe(DRINKS);
    expect(await within(filterBar()).findByText('Đồ uống')).toBeTruthy();
  });

  it('Danh mục trên URL không còn (đã xóa): hiện "Danh mục không còn", không hiện id thô', async () => {
    const missing = 'cccccccc-3333-4333-8333-333333333333';
    renderPage('OWNER', `${PAGE_PATH}?categoryId=${missing}`);
    expect(await within(table()).findByText('Không có Sản phẩm nào khớp bộ lọc.')).toBeTruthy();
    expect(await within(filterBar()).findByText('Danh mục không còn')).toBeTruthy();
    expect(within(filterBar()).queryByText(missing)).toBeNull();
  });

  it('tải danh sách Danh mục lỗi: ô Danh mục báo lỗi thay vì "Danh mục không còn"', async () => {
    failCategories = true;
    renderPage('OWNER', `${PAGE_PATH}?categoryId=${DRINKS}`);
    expect(await within(filterBar()).findByText('Không tải được Danh mục')).toBeTruthy();
    expect(within(filterBar()).queryByText('Danh mục không còn')).toBeNull();
    expect(within(filterBar()).queryByText(DRINKS)).toBeNull();
  });

  it('lỗi ô giá gắn với ô (aria-describedby) và được đọc ngay (role="alert")', async () => {
    renderPage();
    await within(table()).findByText('Bánh quy bơ');

    setPrice('Giá từ', '20000');
    setPrice('Giá đến', '10000');
    const alert = await within(filterBar()).findByRole('alert');
    expect(alert.textContent).toBe('Giá từ không được lớn hơn giá đến');
    expect(alert.id).not.toBe('');
    expect(priceBox('Giá từ').getAttribute('aria-describedby')).toBe(alert.id);
    expect(priceBox('Giá đến').getAttribute('aria-describedby')).toBeNull();

    setPrice('Giá đến', '30000');
    await waitFor(() => expect(within(filterBar()).queryByRole('alert')).toBeNull());
    expect(priceBox('Giá từ').getAttribute('aria-describedby')).toBeNull();
  });
});
