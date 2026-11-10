import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import type {
  Category,
  CreateProductRequest,
  Product,
  ProductStatus,
  UpdateProductRequest,
} from '../../../shared/types/catalog';
import type { Me, Role } from '../../../shared/types/platform';
import { downscaleImage } from '../components/imageResize';
import { foldText } from '../components/textUtils';
import { ProductsPage } from './ProductsPage';

// jsdom không giải mã, không vẽ được ảnh: thay bộ thu nhỏ ảnh; mặc định trả nguyên tệp (beforeEach)
vi.mock('../components/imageResize', () => ({ downscaleImage: vi.fn<(file: File) => Promise<File>>() }));

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
    imageUrl: null,
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
/** Phiên bản ảnh đã tải của từng Sản phẩm (backend giả của SP-01b). */
let imageVersions: Map<string, number>;
/**
 * Lần tải ảnh tới: backend từ chối tệp (`415 image-type`), quá cỡ (`413 image-too-large`, hoặc `413 payload-too-large`
 * khi lố giới hạn máy chủ), mất mạng (fetch ném lỗi), hoặc lưu xong nhưng phản hồi bị mất.
 */
let failNextImageUpload: 'type' | 'too-large' | 'payload-too-large' | 'network' | 'lose-response' | null;
/** Lần gỡ ảnh tới được backend lưu nhưng phản hồi bị mất. */
let loseNextImageRemoval: boolean;
/** Lần sửa tới được backend lưu nhưng phản hồi bị mất (lỗi mạng sau khi đã lưu). */
let loseNextPutResponse: boolean;

const DEACTIVATE_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/deactivate$/;
const IMAGE_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/image$/;
const PRODUCT_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)$/;

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
 * Sửa như `PUT /api/v1/catalog/products/{id}` của SP-02: thay toàn bộ (thiếu `categoryId`, `description`, `barcode` thì
 * xóa, thiếu `unit` thì "cái", thiếu `sku` thì giữ); SKU, mã vạch chữ HOA; Sản phẩm đã ngừng `409 product-inactive`;
 * trùng mã với Sản phẩm khác `409`; id không có `404`.
 */
function updateProduct(id: string, body: UpdateProductRequest): Response {
  const existing = products.find((p) => p.id === id);
  if (!existing) return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  const problem = (problemType: string, title: string) =>
    json({ type: problemType, title, status: 409, detail: title, requestId: 'req-409' }, 409);
  if (existing.status === 'INACTIVE') return problem('/errors/catalog/product-inactive', 'Sản phẩm đã ngừng kinh doanh');
  const others = products.filter((p) => p.id !== id);
  const barcode = body.barcode?.toUpperCase() ?? null;
  const sku = body.sku?.toUpperCase() ?? defaultOf(existing).sku;
  if (barcode && others.some((p) => p.variants.some((v) => v.barcode === barcode))) {
    return problem('/errors/catalog/barcode-taken', 'Mã vạch đã có');
  }
  if (others.some((p) => p.variants.some((v) => v.sku === sku))) {
    return problem('/errors/catalog/sku-taken', 'SKU đã có');
  }
  const category = categories.find((c) => c.id === body.categoryId);
  const edited: Product = {
    ...existing,
    name: body.name,
    categoryId: category?.id ?? null,
    categoryName: category?.name ?? null,
    unit: body.unit ?? 'cái',
    description: body.description ?? null,
    variants: existing.variants.map((v) => (v.isDefault ? { ...v, salePrice: body.salePrice, sku, barcode } : v)),
  };
  products = products.map((p) => (p.id === id ? edited : p));
  if (loseNextPutResponse) {
    loseNextPutResponse = false;
    throw new TypeError('Failed to fetch');
  }
  return json(edited);
}

/**
 * Ảnh như `PUT`/`DELETE …/products/{id}/image` của SP-01b: tải lên tăng phiên bản (`imageUrl` có `?v=`), gỡ thì
 * `imageUrl` về `null`; Sản phẩm đã ngừng `409 product-inactive`; id không có `404`.
 */
function changeImage(id: string, method: string, body: unknown): Response {
  const existing = products.find((p) => p.id === id);
  if (!existing) return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  if (existing.status === 'INACTIVE') {
    return json({ type: '/errors/catalog/product-inactive', title: 'Ngừng kinh doanh', status: 409, requestId: 'r' }, 409);
  }
  if (method === 'DELETE') {
    products = products.map((p) => (p.id === id ? { ...p, imageUrl: null } : p));
    if (loseNextImageRemoval) {
      loseNextImageRemoval = false;
      throw new TypeError('Failed to fetch');
    }
    return new Response(null, { status: 204 });
  }
  if (!(body instanceof FormData) || !(body.get('file') instanceof File)) {
    return json({ type: '/errors/common/validation', title: 'Dữ liệu không hợp lệ', status: 400, requestId: 'r' }, 400);
  }
  const failure = failNextImageUpload;
  failNextImageUpload = null;
  if (failure === 'network') throw new TypeError('Failed to fetch');
  if (failure === 'type') {
    return json({ type: '/errors/catalog/image-type', title: 'Không phải ảnh', status: 415, requestId: 'r' }, 415);
  }
  if (failure === 'too-large' || failure === 'payload-too-large') {
    const problemType = failure === 'too-large' ? '/errors/catalog/image-too-large' : '/errors/common/payload-too-large';
    return json({ type: problemType, title: 'Quá lớn', status: 413, requestId: 'r' }, 413);
  }
  const version = (imageVersions.get(id) ?? 0) + 1;
  imageVersions.set(id, version);
  const withImage: Product = { ...existing, imageUrl: `${PRODUCTS_PATH}/${id}/image?v=${version}` };
  products = products.map((p) => (p.id === id ? withImage : p));
  if (failure === 'lose-response') throw new TypeError('Failed to fetch');
  return json(withImage);
}

/**
 * Backend giả: danh sách có tìm kiếm, lọc, phân trang (SP-05); POST trả đúng mã lỗi của SP-01 và phát lại theo
 * `Idempotency-Key` (cùng nội dung thì trả lại Sản phẩm cũ, khác nội dung thì `409 idempotency-key-reuse`). Sửa
 * (SP-02) như `updateProduct`. Ngừng kinh doanh (SP-03) đổi Sản phẩm và Biến thể sang `INACTIVE`, gọi lại vẫn `200`.
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
    const editing = PRODUCT_PATH.exec(url.pathname);
    if (editing && method === 'PUT') {
      return updateProduct(decodeURIComponent(editing[1]), JSON.parse(String(init?.body)) as UpdateProductRequest);
    }
    const imaging = IMAGE_PATH.exec(url.pathname);
    if (imaging && (method === 'PUT' || method === 'DELETE')) {
      return changeImage(decodeURIComponent(imaging[1]), method, init?.body);
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

function productPuts() {
  return fetchMock.mock.calls.filter(
    ([input, init]) => init?.method === 'PUT' && PRODUCT_PATH.test(new URL(String(input), 'http://localhost').pathname),
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

function save() {
  fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));
}

function valueOf(label: string): string {
  return (field(label) as HTMLInputElement | HTMLTextAreaElement).value;
}

async function openEditDialog(name: string) {
  fireEvent.click(within(rowOf(name)).getByRole('button', { name: `Sửa ${name}` }));
  await screen.findByRole('dialog');
  expect(within(dialog()).getByText('Sửa Sản phẩm')).toBeTruthy();
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
  loseNextPutResponse = false;
  imageVersions = new Map();
  failNextImageUpload = null;
  loseNextImageRemoval = false;
  vi.mocked(downscaleImage).mockReset();
  vi.mocked(downscaleImage).mockImplementation(async (file) => file);
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
    expect(screen.queryByRole('button', { name: /^Sửa/ })).toBeNull();
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
    // Không có cả nút Sửa: Sản phẩm đã ngừng không sửa được, không mở bán lại
    expect(within(rowOf('Sản phẩm 01')).queryByRole('button')).toBeNull();
    expect(within(rowOf('Sản phẩm 02')).getByRole('button', { name: 'Ngừng kinh doanh Sản phẩm 02' })).toBeTruthy();
    expect(within(rowOf('Sản phẩm 02')).getByRole('button', { name: 'Sửa Sản phẩm 02' })).toBeTruthy();
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

describe('ProductsPage — sửa Sản phẩm (FR-2, SP-02)', () => {
  beforeEach(() => {
    products = [
      product(2, {
        categoryId: 'c-1',
        categoryName: 'Đồ uống',
        unit: 'chai',
        description: 'Uống lạnh ngon hơn',
        variants: [{ ...product(2).variants[0], barcode: '8935001800026' }],
      }),
      product(1, { variants: [{ ...product(1).variants[0], barcode: '8935001800019' }] }),
    ];
  });

  it('Chủ cửa hàng đổi giá: form có sẵn dữ liệu, không có Tồn kho, Giá vốn; PUT thay toàn bộ, bảng hiện giá mới ngay', async () => {
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 02');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    await openEditDialog('Sản phẩm 02');
    expect(valueOf('Tên Sản phẩm')).toBe('Sản phẩm 02');
    expect(within(dialog()).getByText('Đồ uống')).toBeTruthy();
    expect(valueOf('Đơn vị tính')).toBe('chai');
    expect(valueOf('Giá bán')).toBe('2.000');
    expect(valueOf('SKU')).toBe('SP-00000002');
    expect(valueOf('Mã vạch')).toBe('8935001800026');
    expect(valueOf('Mô tả')).toBe('Uống lạnh ngon hơn');
    // Giá vốn chỉ đổi qua nhập hàng (AD-15); Tồn kho không sửa ở đây
    expect(within(dialog()).queryByLabelText('Tồn kho đầu kỳ')).toBeNull();
    expect(within(dialog()).queryByLabelText('Giá vốn ban đầu')).toBeNull();
    expect(within(dialog()).queryByText(/Giá vốn/)).toBeNull();

    type('Giá bán', '2500');
    expect(valueOf('Giá bán')).toBe('2.500');
    save();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(productPuts()).toHaveLength(1);
    const [put] = productPuts();
    expect(new URL(String(put[0]), 'http://localhost').pathname).toBe(`${PRODUCTS_PATH}/p-2`);
    expect(bodyOf(put)).toEqual({
      name: 'Sản phẩm 02',
      salePrice: 2500,
      categoryId: 'c-1',
      unit: 'chai',
      description: 'Uống lạnh ngon hơn',
      sku: 'SP-00000002',
      barcode: '8935001800026',
    });
    // Không phải chứng từ: không có Idempotency-Key
    expect(keyOf(put)).toBeNull();
    expect(await screen.findByText('Đã lưu Sản phẩm "Sản phẩm 02"')).toBeTruthy();
    // Bảng tải lại tại chỗ, hiện giá mới ngay; thứ tự không đổi
    await waitFor(() => expect(within(rowOf('Sản phẩm 02')).getByText(/2\.500/)).toBeTruthy());
    expect(rowNames()).toEqual(['Sản phẩm 01', 'Sản phẩm 02']);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'categories'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });
  });

  it('đổi tên, Danh mục; bỏ trống mô tả, mã vạch, đơn vị: PUT không có các trường đó, bảng đổi theo', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    type('Tên Sản phẩm', '  Nước cam ép ');
    await chooseCategory('Bánh kẹo');
    type('Đơn vị tính', ' ');
    type('Mã vạch', '');
    type('Mô tả', '');
    save();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(bodyOf(productPuts()[0])).toEqual({
      name: 'Nước cam ép',
      salePrice: 2000,
      categoryId: 'c-2',
      sku: 'SP-00000002',
    });
    await waitFor(() => expect(rowNames()).toEqual(['Nước cam ép', 'Sản phẩm 01']));
    expect(within(rowOf('Nước cam ép')).getByText('Bánh kẹo')).toBeTruthy();
    expect(within(rowOf('Nước cam ép')).getByText('cái')).toBeTruthy();
  });

  it('không đổi gì (kể cả gõ SKU, mã vạch chữ thường, thêm khoảng trắng): không gửi PUT, đóng hộp thoại', async () => {
    products = [product(3, { variants: [{ ...product(3).variants[0], sku: 'VNM-1L', barcode: 'ABC123' }] })];
    renderPage();
    await within(table()).findByText('Sản phẩm 03');

    await openEditDialog('Sản phẩm 03');
    type('Tên Sản phẩm', ' Sản phẩm 03 ');
    type('SKU', 'vnm-1l');
    type('Mã vạch', 'abc123');
    type('Giá bán', '3000');
    save();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(productPuts()).toHaveLength(0);
  });

  it('bỏ trống SKU khi sửa: không gửi SKU (backend giữ SKU cũ)', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 01');

    await openEditDialog('Sản phẩm 01');
    type('SKU', '  ');
    type('Giá bán', '1200');
    save();

    await waitFor(() => expect(productPuts()).toHaveLength(1));
    expect(bodyOf(productPuts()[0])).toEqual({
      name: 'Sản phẩm 01',
      salePrice: 1200,
      unit: 'cái',
      barcode: '8935001800019',
    });
    await waitFor(() => expect(within(rowOf('Sản phẩm 01')).getByText(/1\.200/)).toBeTruthy());
    expect(within(rowOf('Sản phẩm 01')).getByText('SP-00000001')).toBeTruthy();
  });

  it('kiểm tra phía client như khi thêm: thiếu tên, giá sai, mã vạch có ký tự lạ thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    type('Tên Sản phẩm', '   ');
    type('Giá bán', '12,5');
    type('Mã vạch', '893-456');
    save();

    expect(await within(dialog()).findByText('Nhập tên Sản phẩm')).toBeTruthy();
    expect(within(dialog()).getByText('Giá bán phải là số nguyên')).toBeTruthy();
    expect(within(dialog()).getByText('Mã vạch chỉ gồm chữ cái không dấu và chữ số')).toBeTruthy();
    expect(productPuts()).toHaveLength(0);
  });

  it('trùng mã vạch của Sản phẩm khác: báo dưới ô Mã vạch, giữ form', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    type('Mã vạch', '8935001800019');
    save();

    expect(await within(dialog()).findByText('Cửa hàng đã có Sản phẩm dùng mã vạch này.')).toBeTruthy();
    expect(field('Mã vạch').getAttribute('aria-invalid')).toBe('true');
    expect(within(dialog()).queryByRole('alert')).toBeNull();
    expect(defaultOf(products.find((p) => p.id === 'p-2') as Product).barcode).toBe('8935001800026');
  });

  it('Sản phẩm vừa bị ngừng kinh doanh ở nơi khác: báo ở khung lỗi, bảng tải lại, dòng không còn nút Sửa', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');
    // Máy khác ngừng kinh doanh sau khi bảng đã tải
    products = products.map((p): Product => (p.id === 'p-2' ? { ...p, status: 'INACTIVE' } : p));

    await openEditDialog('Sản phẩm 02');
    type('Giá bán', '2500');
    save();

    expect(await within(dialog()).findByText('Sản phẩm này đã ngừng kinh doanh nên không sửa được.')).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
    await waitFor(() => expect(within(rowOf('Sản phẩm 02')).getByText('Ngừng kinh doanh')).toBeTruthy());
    expect(within(rowOf('Sản phẩm 02')).queryByRole('button', { name: 'Sửa Sản phẩm 02' })).toBeNull();
    expect(defaultOf(products.find((p) => p.id === 'p-2') as Product).salePrice).toBe(2000);
    // Chỉ còn đóng được: form và nút "Lưu" bị khóa, khung lỗi vẫn hiện
    expect((within(dialog()).getByRole('button', { name: 'Lưu' }) as HTMLButtonElement).disabled).toBe(true);
    expect((field('Giá bán') as HTMLInputElement).disabled).toBe(true);
    expect((field('Tên Sản phẩm') as HTMLInputElement).disabled).toBe(true);
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đóng' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(productPuts()).toHaveLength(1);
  });

  it('Sản phẩm không còn (404): báo ở khung lỗi, hộp thoại chỉ còn đóng được', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');
    // Đã bị xóa ở nơi khác sau khi bảng đã tải
    products = products.filter((p) => p.id !== 'p-2');

    await openEditDialog('Sản phẩm 02');
    type('Giá bán', '2500');
    save();

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    expect((within(dialog()).getByRole('button', { name: 'Lưu' }) as HTMLButtonElement).disabled).toBe(true);
    expect((field('Giá bán') as HTMLInputElement).disabled).toBe(true);
    expect(within(dialog()).getByRole('button', { name: 'Đóng' })).toBeTruthy();
  });

  it('trùng SKU khi sửa: báo dưới ô SKU, hướng dẫn giữ SKU hiện tại (không phải tự sinh)', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    type('SKU', 'sp-00000001');
    save();

    expect(await within(dialog()).findByText('Cửa hàng đã có Sản phẩm dùng SKU này.')).toBeTruthy();
    expect(within(dialog()).getByText('Nhập SKU khác, hoặc để trống để giữ SKU hiện tại.')).toBeTruthy();
    expect(within(dialog()).queryByText(/tự sinh/)).toBeNull();
    expect(field('SKU').getAttribute('aria-invalid')).toBe('true');
    expect(within(dialog()).queryByRole('alert')).toBeNull();
  });

  it('không tải được Danh mục: ô Danh mục vẫn hiện tên Danh mục hiện tại, không hiện id', async () => {
    failCategories = true;
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');

    expect(
      await within(dialog()).findByText('Không tải được danh sách Danh mục. Đóng hộp thoại rồi mở lại.'),
    ).toBeTruthy();
    expect(within(dialog()).getByText('Đồ uống')).toBeTruthy();
    expect(within(dialog()).queryByText('c-1')).toBeNull();
  });

  it('mất phản hồi sau khi máy chủ đã lưu: báo lỗi mạng, mọi bảng liên quan tải lại; gõ lại giá cũ vẫn gửi', async () => {
    loseNextPutResponse = true;
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 02');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    await openEditDialog('Sản phẩm 02');
    type('Giá bán', '2800');
    save();

    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    await waitFor(() => expect(within(rowOf('Sản phẩm 02')).getByText(/2\.800/)).toBeTruthy());
    // Máy chủ có thể đã lưu: tải lại cả Danh mục, Tồn kho và hàng bán được
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'categories'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });

    // Giống dữ liệu lúc mở hộp thoại nhưng máy chủ đã khác: vẫn gửi PUT
    type('Giá bán', '2000');
    save();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(productPuts()).toHaveLength(2);
    expect(bodyOf(productPuts()[1])).toMatchObject({ salePrice: 2000 });
    await waitFor(() => expect(within(rowOf('Sản phẩm 02')).getByText(/2\.000/)).toBeTruthy());
  });
});

describe('ProductsPage — ảnh Sản phẩm (FR-1, SP-01b)', () => {
  let previews: number;

  beforeEach(() => {
    // jsdom không có URL.createObjectURL: URL xem trước giả, đếm theo lần chọn
    previews = 0;
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn<(file: Blob) => string>(() => `blob:xem-truoc-${++previews}`),
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn<(url: string) => void>(),
    });
  });

  afterEach(() => {
    Reflect.deleteProperty(URL, 'createObjectURL');
    Reflect.deleteProperty(URL, 'revokeObjectURL');
  });

  function imageFile(name: string, mimeType: string, size = 2048): File {
    return new File([new Uint8Array(size)], name, { type: mimeType });
  }

  /** Chọn tệp ở ô Ảnh của hộp thoại (antd Upload, ô chọn tệp ẩn). */
  function chooseImage(file: File) {
    const input = dialog().querySelector<HTMLInputElement>('input[type="file"]');
    if (!input) throw new Error('không có ô chọn ảnh');
    fireEvent.change(input, { target: { files: [file] } });
  }

  /** Ảnh xem trước trong hộp thoại; `null` khi đang hiện biểu tượng "chưa có ảnh". */
  function previewSrc(): string | null {
    return within(dialog()).queryByRole('img', { name: 'Ảnh Sản phẩm' })?.getAttribute('src') ?? null;
  }

  /** Ảnh thu nhỏ ở dòng của Sản phẩm; `null` khi chưa có ảnh (biểu tượng). */
  function thumbnailOf(name: string): string | null {
    return rowOf(name).querySelector('img')?.getAttribute('src') ?? null;
  }

  function imageCalls(method: 'PUT' | 'DELETE') {
    return fetchMock.mock.calls.filter(
      ([input, init]) => init?.method === method && IMAGE_PATH.test(new URL(String(input), 'http://localhost').pathname),
    );
  }

  /** Thứ tự các lần ghi (bỏ các lần đọc): `POST …/products`, `PUT …/image`… */
  function writes(): string[] {
    return fetchMock.mock.calls
      .filter(([, init]) => (init?.method ?? 'GET') !== 'GET')
      .map(([input, init]) => `${init?.method} ${new URL(String(input), 'http://localhost').pathname}`);
  }

  it('bảng có ảnh thu nhỏ ở cột tên, Nhân viên cũng thấy; chưa có ảnh thì hiện biểu tượng', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=3` }), product(1)];
    renderPage('STAFF');
    await within(table()).findByText('Sản phẩm 02');

    expect(thumbnailOf('Sản phẩm 02')).toBe('/api/v1/catalog/products/p-2/image?v=3');
    expect(thumbnailOf('Sản phẩm 01')).toBeNull();
    // Ô tên vẫn chỉ có chữ là tên Sản phẩm
    expect(rowNames()).toEqual(['Sản phẩm 01', 'Sản phẩm 02']);
  });

  it('thêm Sản phẩm kèm ảnh: xem trước ngay, tạo xong mới tải ảnh (multipart), ảnh hiện ở bảng ngay', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    expect(previewSrc()).toBeNull();
    type('Tên Sản phẩm', 'Bánh quy bơ');
    type('Giá bán', '45000');
    const png = imageFile('banh-quy.png', 'image/png', 200 * 1024);
    chooseImage(png);
    // Xem trước ngay, chưa gửi gì
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    expect(within(dialog()).getByRole('button', { name: /Đổi ảnh/ })).toBeTruthy();
    expect(writes()).toEqual([]);
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // Hai bước: tạo Sản phẩm rồi mới tải ảnh lên Sản phẩm vừa tạo
    expect(writes()).toEqual(['POST /api/v1/catalog/products', 'PUT /api/v1/catalog/products/p-100/image']);
    const [upload] = imageCalls('PUT');
    const body = upload[1]?.body;
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(png);
    // Trình duyệt tự đặt multipart kèm boundary; không phải JSON
    expect(new Headers(upload[1]?.headers).has('Content-Type')).toBe(false);
    // Body POST không có ảnh
    expect(bodyOf(posts()[0])).toEqual({ name: 'Bánh quy bơ', salePrice: 45000, openingQuantity: 0, openingUnitCost: 0 });
    expect(await screen.findByText('Đã thêm Sản phẩm "Bánh quy bơ"')).toBeTruthy();
    // Ảnh hiện ở bảng ngay, Sản phẩm mới đứng đầu
    await waitFor(() => expect(rowNames()[0]).toBe('Bánh quy bơ'));
    await waitFor(() => expect(thumbnailOf('Bánh quy bơ')).toBe('/api/v1/catalog/products/p-100/image?v=1'));
    // URL xem trước được thu hồi khi đóng hộp thoại
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:xem-truoc-1');
  });

  it('chọn ảnh sai kiểu hay quá 1 MB: báo dưới ô Ảnh, không xem trước; thêm vẫn được, không tải ảnh', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Kẹo dẻo');
    type('Giá bán', '10000');
    chooseImage(imageFile('anh.gif', 'image/gif'));
    expect(await within(dialog()).findByText('Ảnh phải là JPEG, PNG hoặc WebP.')).toBeTruthy();
    expect(previewSrc()).toBeNull();
    chooseImage(imageFile('anh-lon.webp', 'image/webp', 1_048_577));
    expect(await within(dialog()).findByText('Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn.')).toBeTruthy();
    expect(previewSrc()).toBeNull();
    // Đúng 1 MB thì được
    chooseImage(imageFile('anh-vua.jpg', 'image/jpeg', 1_048_576));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    await waitFor(() => expect(within(dialog()).queryByText('Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn.')).toBeNull());
    // Bỏ ảnh vừa chọn rồi thêm: không tải ảnh
    fireEvent.click(within(dialog()).getByRole('button', { name: /Bỏ ảnh vừa chọn/ }));
    expect(previewSrc()).toBeNull();
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(writes()).toEqual(['POST /api/v1/catalog/products']);
    await waitFor(() => expect(rowNames()[0]).toBe('Kẹo dẻo'));
    expect(thumbnailOf('Kẹo dẻo')).toBeNull();
  });

  it('tải ảnh lỗi sau khi đã thêm: vẫn đóng hộp thoại, báo chưa tải được ảnh, không tạo trùng', async () => {
    failNextImageUpload = 'type';
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Sổ tay');
    type('Giá bán', '20000');
    // Tệp PDF đổi đuôi .png: trình duyệt báo image/png nên qua được kiểm phía client, backend kiểm nội dung và từ chối
    chooseImage(imageFile('so-tay.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(
      await screen.findByText(
        'Đã thêm Sản phẩm "Sổ tay" nhưng chưa tải được ảnh: Tệp này không phải ảnh JPEG, PNG hay WebP. ' +
          'Bấm "Sửa" ở dòng Sản phẩm để tải lại ảnh.',
      ),
    ).toBeTruthy();
    expect(posts()).toHaveLength(1);
    await waitFor(() => expect(rowNames()[0]).toBe('Sổ tay'));
    expect(thumbnailOf('Sổ tay')).toBeNull();
  });

  it('sửa chỉ đổi ảnh: không gửi PUT Sản phẩm, tải ảnh mới, bảng hiện ảnh mới', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=1` }), product(1)];
    imageVersions.set('p-2', 1);
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    // Ảnh hiện có
    expect(previewSrc()).toBe('/api/v1/catalog/products/p-2/image?v=1');
    chooseImage(imageFile('moi.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    // Bỏ tệp vừa chọn thì về ảnh hiện có; chọn lại
    fireEvent.click(within(dialog()).getByRole('button', { name: /Bỏ ảnh vừa chọn/ }));
    expect(previewSrc()).toBe('/api/v1/catalog/products/p-2/image?v=1');
    const webp = imageFile('moi.webp', 'image/webp');
    chooseImage(webp);
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-2'));
    save();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(writes()).toEqual(['PUT /api/v1/catalog/products/p-2/image']);
    const [upload] = imageCalls('PUT');
    expect((upload[1]?.body as FormData | undefined)?.get('file')).toBe(webp);
    expect(await screen.findByText('Đã lưu Sản phẩm "Sản phẩm 02"')).toBeTruthy();
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBe('/api/v1/catalog/products/p-2/image?v=2'));
  });

  it('sửa gỡ ảnh: gửi DELETE, bảng hiện biểu tượng', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=1` }), product(1)];
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    fireEvent.click(within(dialog()).getByRole('button', { name: /Gỡ ảnh/ }));
    expect(previewSrc()).toBeNull();
    expect(within(dialog()).getByRole('button', { name: /Chọn ảnh/ })).toBeTruthy();
    save();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(writes()).toEqual(['DELETE /api/v1/catalog/products/p-2/image']);
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBeNull());
  });

  it('sửa trường và ảnh: lưu Sản phẩm trước rồi tải ảnh; backend từ chối tệp thì báo dưới ô Ảnh, gửi lại được', async () => {
    failNextImageUpload = 'type';
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    type('Giá bán', '2500');
    chooseImage(imageFile('la.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    save();

    expect(await within(dialog()).findByText('Tệp này không phải ảnh JPEG, PNG hay WebP.')).toBeTruthy();
    expect(writes()).toEqual(['PUT /api/v1/catalog/products/p-2', 'PUT /api/v1/catalog/products/p-2/image']);
    // Nói rõ phần đã lưu
    expect(
      await screen.findByText(
        'Đã lưu thông tin, chưa lưu được ảnh của Sản phẩm "Sản phẩm 02": Tệp này không phải ảnh JPEG, PNG hay WebP.',
      ),
    ).toBeTruthy();
    // Câu chỉ có một chỗ (findByText ở trên hỏng nếu có hai): dưới ô Ảnh, không ở khung báo lỗi chung. Hộp thoại vẫn
    // mở, giá mới đã lưu
    await waitFor(() => expect(within(rowOf('Sản phẩm 02')).getByText(/2\.500/)).toBeTruthy());

    chooseImage(imageFile('dung.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-2'));
    await waitFor(() => expect(within(dialog()).queryByText('Tệp này không phải ảnh JPEG, PNG hay WebP.')).toBeNull());
    save();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(imageCalls('PUT')).toHaveLength(2);
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBe('/api/v1/catalog/products/p-2/image?v=1'));
  });

  it('mất mạng khi tải ảnh lúc sửa: báo ở khung lỗi, hộp thoại vẫn mở', async () => {
    failNextImageUpload = 'network';
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    chooseImage(imageFile('anh.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    save();

    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(productPuts()).toHaveLength(0);
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('chọn ảnh hợp lệ rồi chọn tệp sai: bỏ cả ảnh đã chọn, không gửi ảnh; tệp không rõ kiểu thì để backend kiểm', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openAddDialog();
    type('Tên Sản phẩm', 'Bút chì');
    type('Giá bán', '3000');
    chooseImage(imageFile('dung.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    chooseImage(imageFile('tai-lieu.pdf', 'application/pdf'));
    expect(await within(dialog()).findByText('Ảnh phải là JPEG, PNG hoặc WebP.')).toBeTruthy();
    // Ảnh đã chọn trước đó cũng bị bỏ: không xem trước, "Thêm" không gửi ảnh nào
    expect(previewSrc()).toBeNull();
    submit();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(writes()).toEqual(['POST /api/v1/catalog/products']);

    // Tệp không đuôi (trình duyệt không biết kiểu): không chặn ở client, gửi để backend kiểm theo nội dung
    await openAddDialog();
    type('Tên Sản phẩm', 'Bút mực');
    type('Giá bán', '5000');
    const unknown = imageFile('anh-khong-duoi', '');
    chooseImage(unknown);
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-2'));
    expect(within(dialog()).queryByText('Ảnh phải là JPEG, PNG hoặc WebP.')).toBeNull();
    submit();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const uploads = imageCalls('PUT');
    expect(uploads).toHaveLength(1);
    expect((uploads[0][1]?.body as FormData | undefined)?.get('file')).toBe(unknown);
  });

  it('ảnh lớn được thu nhỏ ở trình duyệt trước khi tải; thu nhỏ rồi vẫn quá 1 MB thì báo dưới ô Ảnh', async () => {
    const small = imageFile('chup.jpg', 'image/jpeg', 250 * 1024);
    vi.mocked(downscaleImage).mockImplementation(async (file) => (file.name === 'chup.png' ? small : file));
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    await openEditDialog('Sản phẩm 02');
    // Ảnh chụp điện thoại 4 MB: tự thu nhỏ, không báo lỗi 1 MB
    const photo = imageFile('chup.png', 'image/png', 4 * 1_048_576);
    chooseImage(photo);
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    expect(downscaleImage).toHaveBeenCalledWith(photo);
    expect(URL.createObjectURL).toHaveBeenCalledWith(small);
    expect(within(dialog()).queryByText('Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn.')).toBeNull();

    // Không thu nhỏ được (bộ thu nhỏ trả nguyên tệp) mà vẫn quá 1 MB: báo, bỏ ảnh đã chọn
    const huge = imageFile('khong-thu-nho.webp', 'image/webp', 3 * 1_048_576);
    chooseImage(huge);
    expect(await within(dialog()).findByText('Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn.')).toBeTruthy();
    expect(downscaleImage).toHaveBeenCalledWith(huge);
    expect(previewSrc()).toBeNull();

    chooseImage(photo);
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-2'));
    save();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect((imageCalls('PUT')[0]?.[1]?.body as FormData | undefined)?.get('file')).toBe(small);
  });

  it('backend báo ảnh quá cỡ (413 của ảnh hay của máy chủ): báo dưới ô Ảnh, không ở khung lỗi chung', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');
    await openEditDialog('Sản phẩm 02');

    const failures = ['payload-too-large', 'too-large'] as const;
    for (const [index, failure] of failures.entries()) {
      failNextImageUpload = failure;
      chooseImage(imageFile(`${failure}.png`, 'image/png'));
      await waitFor(() => expect(previewSrc()).toBe(`blob:xem-truoc-${index + 1}`));
      // Chọn ảnh mới thì lỗi cũ dưới ô Ảnh biến mất
      await waitFor(() => expect(within(dialog()).queryByText('Ảnh lớn hơn 1 MB.')).toBeNull());
      save();
      expect(await within(dialog()).findByText('Ảnh lớn hơn 1 MB.')).toBeTruthy();
      expect(within(dialog()).queryByText('Tệp gửi lên quá lớn.')).toBeNull();
      expect(screen.getByRole('dialog')).toBeTruthy();
    }
    expect(productPuts()).toHaveLength(0);
  });

  it('sửa chỉ ảnh mà Sản phẩm vừa ngừng kinh doanh (409): khung lỗi, hộp thoại chỉ còn đóng được, không PUT Sản phẩm', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');
    await openEditDialog('Sản phẩm 02');
    // Bị ngừng kinh doanh ở nơi khác sau khi đã mở hộp thoại
    products = products.map((p) => (p.id === 'p-2' ? { ...p, status: 'INACTIVE' as ProductStatus } : p));

    chooseImage(imageFile('anh.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    save();

    expect(await within(dialog()).findByText('Sản phẩm này đã ngừng kinh doanh nên không sửa được.')).toBeTruthy();
    expect((within(dialog()).getByRole('button', { name: 'Lưu' }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(dialog()).getByRole('button', { name: 'Đóng' })).toBeTruthy();
    expect(productPuts()).toHaveLength(0);
    expect(writes()).toEqual(['PUT /api/v1/catalog/products/p-2/image']);
  });

  it('gỡ ảnh mà Sản phẩm không còn (404): khung lỗi, hộp thoại chỉ còn đóng được, không PUT Sản phẩm', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=1` }), product(1)];
    renderPage();
    await within(table()).findByText('Sản phẩm 02');
    await openEditDialog('Sản phẩm 02');
    products = products.filter((p) => p.id !== 'p-2');

    fireEvent.click(within(dialog()).getByRole('button', { name: /Gỡ ảnh/ }));
    save();

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    expect((within(dialog()).getByRole('button', { name: 'Lưu' }) as HTMLButtonElement).disabled).toBe(true);
    expect(within(dialog()).getByRole('button', { name: 'Đóng' })).toBeTruthy();
    expect(productPuts()).toHaveLength(0);
    expect(writes()).toEqual(['DELETE /api/v1/catalog/products/p-2/image']);
  });

  it('mất phản hồi sau khi máy chủ đã lưu ảnh: báo lỗi mạng, bảng tải lại và hiện ảnh mới', async () => {
    failNextImageUpload = 'lose-response';
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 02');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    await openEditDialog('Sản phẩm 02');
    chooseImage(imageFile('anh.png', 'image/png'));
    await waitFor(() => expect(previewSrc()).toBe('blob:xem-truoc-1'));
    save();

    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBe('/api/v1/catalog/products/p-2/image?v=1'));
  });

  it('mất phản hồi sau khi máy chủ đã gỡ ảnh: báo lỗi mạng, bảng tải lại và không còn ảnh', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=1` }), product(1)];
    loseNextImageRemoval = true;
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 02');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    await openEditDialog('Sản phẩm 02');
    fireEvent.click(within(dialog()).getByRole('button', { name: /Gỡ ảnh/ }));
    save();

    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['catalog', 'products'] });
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBeNull());
  });

  it('ảnh thu nhỏ không tải được thì hiện biểu tượng; imageUrl mới thì hiện lại ảnh', async () => {
    products = [product(2, { imageUrl: `${PRODUCTS_PATH}/p-2/image?v=1` }), product(1)];
    const { queryClient } = renderPage();
    await within(table()).findByText('Sản phẩm 02');

    const img = rowOf('Sản phẩm 02').querySelector('img');
    if (!img) throw new Error('chưa có ảnh thu nhỏ');
    fireEvent.error(img);
    expect(thumbnailOf('Sản phẩm 02')).toBeNull();
    expect(rowNames()).toEqual(['Sản phẩm 01', 'Sản phẩm 02']);

    // Ảnh được tải lại ở nơi khác: URL mới
    products = products.map((p) => (p.id === 'p-2' ? { ...p, imageUrl: `${PRODUCTS_PATH}/p-2/image?v=2` } : p));
    await act(() => queryClient.invalidateQueries({ queryKey: ['catalog', 'products'] }));
    await waitFor(() => expect(thumbnailOf('Sản phẩm 02')).toBe('/api/v1/catalog/products/p-2/image?v=2'));
  });
});
