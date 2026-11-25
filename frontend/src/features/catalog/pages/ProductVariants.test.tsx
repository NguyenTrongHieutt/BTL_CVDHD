import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp, ConfigProvider } from 'antd';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type {
  AddVariantRequest,
  CreateProductRequest,
  CreateProductWithVariantsRequest,
  Product,
  ProductVariant,
  UpdateProductRequest,
  UpdateVariantRequest,
} from '../../../shared/types/catalog';
import type { Me } from '../../../shared/types/platform';
import { ProductsPage } from './ProductsPage';

vi.setConfig({ testTimeout: 15_000 });

// jsdom không giải mã ảnh được; form Sản phẩm chỉ cần bộ thu nhỏ ảnh tồn tại
vi.mock('../components/imageResize', () => ({
  downscaleImage: vi.fn<(file: File) => Promise<File>>(async (file) => file),
}));

const PRODUCTS_PATH = '/api/v1/catalog/products';
const CATEGORIES_PATH = '/api/v1/catalog/categories';
const PAGE_PATH = '/catalog/products';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const PRODUCT_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)$/;
const VARIANTS_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/variants$/;
const VARIANT_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/variants\/([^/]+)$/;
const VARIANT_DEACTIVATE_PATH = /^\/api\/v1\/catalog\/products\/([^/]+)\/variants\/([^/]+)\/deactivate$/;

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function problem(problemType: string, status: number, errors?: { field: string; message: string }[]): Response {
  return json({ type: problemType, title: problemType, status, detail: problemType, requestId: 'req-1', ...(errors ? { errors } : {}) }, status);
}

function variant(id: string, name: string | null, attributes: Record<string, string>, salePrice: number): ProductVariant {
  return {
    id,
    sku: `SKU-${id.toUpperCase()}`,
    barcode: null,
    name,
    attributes,
    salePrice,
    isDefault: false,
    status: 'ACTIVE',
  };
}

/** Như backend: khoảng giá của Biến thể đang bán; Biến thể mặc định đứng đầu. */
function normalized(p: Product): Product {
  const active = p.variants.filter((v) => v.status === 'ACTIVE').map((v) => v.salePrice);
  const variants = [...p.variants.filter((v) => v.isDefault), ...p.variants.filter((v) => !v.isDefault)];
  return {
    ...p,
    variants,
    priceRange: active.length === 0 ? null : { min: Math.min(...active), max: Math.max(...active) },
  };
}

function product(id: string, name: string, variants: ProductVariant[], createdAt = '2026-11-10T03:00:00Z'): Product {
  return normalized({
    id,
    name,
    categoryId: null,
    categoryName: null,
    unit: 'cái',
    description: null,
    imageUrl: null,
    hasVariants: variants.length > 1,
    status: 'ACTIVE',
    createdAt,
    updatedAt: createdAt,
    priceRange: null,
    variants: variants.map((v, i) => ({ ...v, isDefault: i === 0 })),
  });
}

function shirt(): Product {
  return product('p-shirt', 'Áo thun', [
    variant('v-red', 'Đỏ / L', { Màu: 'Đỏ', Size: 'L' }, 150000),
    variant('v-blue', 'Xanh / M', { Màu: 'Xanh', Size: 'M' }, 160000),
  ]);
}

function pen(): Product {
  return product('p-pen', 'Bút bi', [variant('v-pen', null, {}, 5000)]);
}

let products: Product[];
let nextId: number;
/** Lần tạo Sản phẩm tới trả lỗi này thay vì tạo. */
let failNextCreate: Response | null;
/** Lần thêm Biến thể tới trả lỗi mạng (fetch ném lỗi), không thêm gì. */
let failNextAddWithNetworkError: boolean;

function labelOf(name: string | undefined, attributes: Record<string, string> | undefined): string {
  return name ?? Object.values(attributes ?? {}).join(' / ');
}

function replace(updated: Product): Response {
  const result = normalized(updated);
  products = products.map((p) => (p.id === result.id ? result : p));
  return json(result);
}

/** Backend giả của SP-07: thêm kèm Biến thể, thêm, sửa, ngừng từng Biến thể, sửa Sản phẩm nhiều Biến thể. */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    const body = init?.body === undefined ? undefined : (JSON.parse(String(init.body)) as unknown);
    if (url.pathname === CATEGORIES_PATH) return json({ items: [], total: 0, page: 0, size: 100 });
    if (url.pathname === PRODUCTS_PATH && method === 'GET') {
      const newestFirst = url.searchParams.get('sort') === 'createdAt,desc';
      const items = [...products].sort((a, b) =>
        newestFirst ? b.createdAt.localeCompare(a.createdAt) : a.name.localeCompare(b.name),
      );
      return json({ items, total: items.length, page: 0, size: 20 });
    }
    if (url.pathname === PRODUCTS_PATH && method === 'POST') {
      if (failNextCreate) {
        const failure = failNextCreate;
        failNextCreate = null;
        return failure;
      }
      const request = body as CreateProductRequest | CreateProductWithVariantsRequest;
      const id = `p-${nextId++}`;
      const createdAt = new Date(Date.UTC(2026, 10, 11, 0, 0, nextId)).toISOString();
      const variants =
        'variants' in request
          ? request.variants.map((v, i) => ({
              ...variant(`${id}-v${i}`, labelOf(v.name, v.attributes), v.attributes ?? {}, v.salePrice),
              sku: v.sku?.toUpperCase() ?? `SP-${id}-${i}`.toUpperCase(),
            }))
          : [variant(`${id}-v0`, null, {}, request.salePrice)];
      const created = product(id, request.name, variants, createdAt);
      products = [created, ...products];
      return json(created, 201);
    }
    const deactivating = VARIANT_DEACTIVATE_PATH.exec(url.pathname);
    if (deactivating && method === 'POST') {
      const existing = products.find((p) => p.id === deactivating[1]);
      if (!existing) return problem('/errors/common/not-found', 404);
      const target = existing.variants.find((v) => v.id === deactivating[2]);
      const others = existing.variants.filter((v) => v.id !== deactivating[2] && v.status === 'ACTIVE');
      if (!target) return problem('/errors/common/not-found', 404);
      if (target.status === 'ACTIVE' && others.length === 0) return problem('/errors/catalog/last-active-variant', 409);
      const successor = target.isDefault ? others[0]?.id : undefined;
      return replace({
        ...existing,
        variants: existing.variants.map((v) => {
          if (v.id === target.id) return { ...v, status: 'INACTIVE', isDefault: false };
          return v.id === successor ? { ...v, isDefault: true } : v;
        }),
      });
    }
    const variantEditing = VARIANT_PATH.exec(url.pathname);
    if (variantEditing && method === 'PUT') {
      const existing = products.find((p) => p.id === variantEditing[1]);
      if (!existing) return problem('/errors/common/not-found', 404);
      const request = body as UpdateVariantRequest;
      return replace({
        ...existing,
        variants: existing.variants.map((v) =>
          v.id === variantEditing[2]
            ? {
                ...v,
                name: labelOf(request.name, request.attributes),
                attributes: request.attributes ?? {},
                sku: request.sku?.toUpperCase() ?? v.sku,
                barcode: request.barcode?.toUpperCase() ?? null,
                salePrice: request.salePrice,
              }
            : v,
        ),
      });
    }
    const adding = VARIANTS_PATH.exec(url.pathname);
    if (adding && method === 'POST') {
      if (failNextAddWithNetworkError) {
        failNextAddWithNetworkError = false;
        throw new TypeError('Failed to fetch');
      }
      const existing = products.find((p) => p.id === adding[1]);
      if (!existing) return problem('/errors/common/not-found', 404);
      const request = body as AddVariantRequest;
      if (!existing.hasVariants && !request.existingVariantName) {
        return problem('/errors/common/validation', 400, [
          { field: 'existingVariantName', message: 'Nhập nhãn cho Biến thể hiện có' },
        ]);
      }
      const label = labelOf(request.name, request.attributes);
      if (existing.variants.some((v) => v.name?.toLowerCase() === label.toLowerCase())) {
        return problem('/errors/catalog/variant-name-taken', 409);
      }
      const added = variant(`${existing.id}-v${existing.variants.length}`, label, request.attributes ?? {}, request.salePrice);
      return replace({
        ...existing,
        hasVariants: true,
        variants: [
          ...existing.variants.map((v) => (existing.hasVariants ? v : { ...v, name: request.existingVariantName ?? null })),
          added,
        ],
      });
    }
    const editing = PRODUCT_PATH.exec(url.pathname);
    if (editing && method === 'PUT') {
      const existing = products.find((p) => p.id === editing[1]);
      if (!existing) return problem('/errors/common/not-found', 404);
      const request = body as UpdateProductRequest;
      return replace({
        ...existing,
        name: request.name,
        unit: request.unit ?? 'cái',
        description: request.description ?? null,
        variants: existing.hasVariants
          ? existing.variants
          : existing.variants.map((v) => ({ ...v, salePrice: request.salePrice ?? v.salePrice })),
      });
    }
    return problem('/errors/common/not-found', 404);
  });
}

function me(): Me {
  return { userId: 'u-1', storeId: 's-1', storeName: 'Cửa hàng thử', role: 'OWNER', displayName: 'Chủ thử' };
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const router = createMemoryRouter([{ path: PAGE_PATH, Component: ProductsPage }], { initialEntries: [PAGE_PATH] });
  render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <AntdApp>
          <CurrentUserContext value={me()}>
            <RouterProvider router={router} />
          </CurrentUserContext>
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { queryClient };
}

function calls(method: string, path: RegExp | string) {
  return fetchMock.mock.calls.filter(([input, init]) => {
    const pathname = new URL(String(input), 'http://localhost').pathname;
    return (init?.method ?? 'GET') === method && (typeof path === 'string' ? pathname === path : path.test(pathname));
  });
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

function keyOf(call: Parameters<typeof fetch>): string | null {
  return new Headers(call[1]?.headers).get('Idempotency-Key');
}

/** Bảng Sản phẩm của màn (bảng đầu tiên; form sửa có thêm bảng Biến thể). */
function productsTable(): HTMLElement {
  return screen.getAllByRole('table')[0];
}

function rowOf(name: string): HTMLElement {
  const row = within(productsTable())
    .getAllByRole('row')
    .find((r) => within(r).queryAllByRole('cell')[0]?.textContent === name);
  if (!row) throw new Error(`không có dòng ${name}`);
  return row;
}

/** Hộp thoại mở sau cùng (hộp thoại Biến thể nằm trên form Sản phẩm). */
function topDialog(): HTMLElement {
  const dialogs = screen.getAllByRole('dialog');
  return dialogs[dialogs.length - 1];
}

function productDialog(): HTMLElement {
  return screen.getAllByRole('dialog')[0];
}

function type(scope: HTMLElement, label: string, value: string) {
  fireEvent.change(within(scope).getByLabelText(label), { target: { value } });
}

function variantRow(n: number): HTMLElement {
  return within(productDialog()).getByRole('region', { name: `Biến thể ${n}` });
}

function variantsSection(): HTMLElement {
  return within(productDialog()).getByRole('region', { name: 'Biến thể' });
}

/** Dòng của bảng Biến thể (trong form sửa) có nhãn này. */
function variantTableRow(label: string): HTMLElement {
  const row = within(variantsSection())
    .getAllByRole('row')
    .find((r) => within(r).queryAllByRole('cell')[0]?.textContent?.startsWith(label));
  if (!row) throw new Error(`không có Biến thể ${label}`);
  return row;
}

async function openEditDialog(name: string) {
  fireEvent.click(within(rowOf(name)).getByRole('button', { name: `Sửa ${name}` }));
  await screen.findByRole('dialog');
}

function clickLast(name: string) {
  const buttons = screen.getAllByRole('button', { name });
  fireEvent.click(buttons[buttons.length - 1]);
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  products = [shirt(), pen()];
  nextId = 100;
  failNextCreate = null;
  failNextAddWithNetworkError = false;
  backend();
});

describe('ProductsPage — Biến thể (FR-7, SP-07)', () => {
  it('bảng: Sản phẩm nhiều Biến thể hiện số Biến thể và khoảng giá; Sản phẩm một Biến thể vẫn hiện SKU và giá', async () => {
    renderPage();
    await within(productsTable()).findByText('Áo thun');

    expect(within(rowOf('Áo thun')).getByText('2 Biến thể')).toBeTruthy();
    expect(within(rowOf('Áo thun')).getByText(/150\.000\s₫\s–\s160\.000\s₫/)).toBeTruthy();
    expect(within(rowOf('Bút bi')).getByText('SKU-V-PEN')).toBeTruthy();
    expect(within(rowOf('Bút bi')).getByText(/5\.000\s₫/)).toBeTruthy();
  });

  it('thêm "Áo thun" với Đỏ/L và Xanh/M: POST có variants, không có giá cấp Sản phẩm; bảng hiện "2 Biến thể", khoảng giá', async () => {
    products = [pen()];
    const { queryClient } = renderPage();
    await within(productsTable()).findByText('Bút bi');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');

    fireEvent.click(screen.getByRole('button', { name: /Thêm Sản phẩm/ }));
    await screen.findByRole('dialog');
    type(productDialog(), 'Tên Sản phẩm', 'Áo thun');
    fireEvent.click(within(productDialog()).getByRole('checkbox', { name: /Có nhiều Biến thể/ }));
    await waitFor(() => expect(within(productDialog()).getAllByLabelText('Giá bán')).toHaveLength(2));
    type(productDialog(), 'Thuộc tính 1', 'Màu');
    type(productDialog(), 'Thuộc tính 2', ' Size ');
    await waitFor(() => expect(within(variantRow(1)).getByLabelText('Màu')).toBeTruthy());

    type(variantRow(1), 'Màu', 'Đỏ');
    type(variantRow(1), 'Size', 'L');
    type(variantRow(1), 'Giá bán', '150000');
    type(variantRow(1), 'SKU', 'ao-do-l');
    type(variantRow(1), 'Tồn kho đầu kỳ', '5');
    type(variantRow(1), 'Giá vốn ban đầu', '70.000');
    type(variantRow(2), 'Màu', 'Xanh');
    type(variantRow(2), 'Size', 'M');
    type(variantRow(2), 'Giá bán', '160000');
    type(variantRow(2), 'Tồn kho đầu kỳ', '3');
    type(variantRow(2), 'Giá vốn ban đầu', '72000');
    // Nhãn để trống: gợi ý nhãn ghép từ giá trị thuộc tính
    await waitFor(() =>
      expect((within(variantRow(2)).getByLabelText('Nhãn') as HTMLInputElement).placeholder).toBe('Xanh / M'),
    );
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Thêm' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const posts = calls('POST', PRODUCTS_PATH);
    expect(posts).toHaveLength(1);
    expect(bodyOf(posts[0])).toEqual({
      name: 'Áo thun',
      variants: [
        {
          attributes: { Màu: 'Đỏ', Size: 'L' },
          sku: 'ao-do-l',
          salePrice: 150000,
          openingQuantity: 5,
          openingUnitCost: 70000,
        },
        { attributes: { Màu: 'Xanh', Size: 'M' }, salePrice: 160000, openingQuantity: 3, openingUnitCost: 72000 },
      ],
    });
    expect(keyOf(posts[0])).toMatch(UUID);
    // Tồn kho có hai dòng riêng: màn Tồn kho tải lại
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    await waitFor(() => expect(within(rowOf('Áo thun')).getByText('2 Biến thể')).toBeTruthy());
    expect(within(rowOf('Áo thun')).getByText(/150\.000\s₫\s–\s160\.000\s₫/)).toBeTruthy();
  });

  it('kiểm phía client: trùng nhãn giữa hai dòng, dòng không có nhãn lẫn giá trị: không gửi; luôn còn ít nhất 2 dòng', async () => {
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    fireEvent.click(screen.getByRole('button', { name: /Thêm Sản phẩm/ }));
    await screen.findByRole('dialog');
    type(productDialog(), 'Tên Sản phẩm', 'Áo khoác');
    fireEvent.click(within(productDialog()).getByRole('checkbox', { name: /Có nhiều Biến thể/ }));
    await waitFor(() => expect(variantRow(1)).toBeTruthy());
    expect(within(variantRow(1)).getByRole('button', { name: 'Xóa Biến thể 1' }).hasAttribute('disabled')).toBe(true);

    type(productDialog(), 'Thuộc tính 1', 'Màu');
    await waitFor(() => expect(within(variantRow(1)).getByLabelText('Màu')).toBeTruthy());
    type(variantRow(1), 'Màu', 'Đỏ');
    type(variantRow(1), 'Giá bán', '1000');
    type(variantRow(2), 'Nhãn', ' đỏ ');
    type(variantRow(2), 'Giá bán', '1000');
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(variantRow(2)).findByText('Trùng nhãn với Biến thể khác ở trên')).toBeTruthy();

    fireEvent.click(within(productDialog()).getByRole('button', { name: /Thêm dòng Biến thể/ }));
    await waitFor(() => expect(variantRow(3)).toBeTruthy());
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(variantRow(3)).findByText('Nhập nhãn hoặc giá trị thuộc tính')).toBeTruthy();
    expect(within(variantRow(3)).getByText('Nhập giá bán')).toBeTruthy();
    expect(calls('POST', PRODUCTS_PATH)).toHaveLength(0);

    // Có 3 dòng thì xóa được; xóa xong về 2 dòng
    fireEvent.click(within(variantRow(3)).getByRole('button', { name: 'Xóa Biến thể 3' }));
    await waitFor(() => expect(within(productDialog()).queryByRole('region', { name: 'Biến thể 3' })).toBeNull());
  });

  it('lỗi theo trường variants[i] của backend hiện dưới ô của dòng đó; trùng nhãn (409) hiện ở khung báo lỗi', async () => {
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    fireEvent.click(screen.getByRole('button', { name: /Thêm Sản phẩm/ }));
    await screen.findByRole('dialog');
    type(productDialog(), 'Tên Sản phẩm', 'Quần');
    fireEvent.click(within(productDialog()).getByRole('checkbox', { name: /Có nhiều Biến thể/ }));
    await waitFor(() => expect(variantRow(2)).toBeTruthy());
    type(variantRow(1), 'Nhãn', '28');
    type(variantRow(1), 'Giá bán', '200000');
    type(variantRow(2), 'Nhãn', '30');
    type(variantRow(2), 'Giá bán', '200000');
    type(variantRow(2), 'SKU', 'Q-30');

    failNextCreate = problem('/errors/common/validation', 400, [
      { field: 'variants[1].sku', message: 'SKU có ký tự không hợp lệ' },
    ]);
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(variantRow(2)).findByText('SKU có ký tự không hợp lệ')).toBeTruthy();

    failNextCreate = problem('/errors/catalog/variant-name-taken', 409);
    type(variantRow(2), 'SKU', 'Q-31');
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Thêm' }));
    expect(
      await within(productDialog()).findByText(PROBLEM_MESSAGES['/errors/catalog/variant-name-taken'].message),
    ).toBeTruthy();
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('sửa Sản phẩm nhiều Biến thể: không có ô giá, SKU, mã vạch; PUT chỉ gửi trường cấp Sản phẩm', async () => {
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    await openEditDialog('Áo thun');

    expect(within(productDialog()).queryByLabelText('Giá bán')).toBeNull();
    expect(within(productDialog()).queryByLabelText('SKU')).toBeNull();
    expect(within(variantTableRow('Đỏ / L')).getByText('Mặc định')).toBeTruthy();
    expect(within(variantTableRow('Xanh / M')).getByText(/160\.000/)).toBeTruthy();

    type(productDialog(), 'Tên Sản phẩm', 'Áo thun cổ tròn');
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const puts = calls('PUT', PRODUCT_PATH);
    expect(puts).toHaveLength(1);
    expect(bodyOf(puts[0])).toEqual({ name: 'Áo thun cổ tròn', unit: 'cái' });
    await waitFor(() => expect(within(rowOf('Áo thun cổ tròn')).getByText('2 Biến thể')).toBeTruthy());
  });

  it('sửa giá một Biến thể: PUT …/variants/{id} lưu ngay, bảng Biến thể và khoảng giá đổi theo', async () => {
    const { queryClient } = renderPage();
    await within(productsTable()).findByText('Áo thun');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await openEditDialog('Áo thun');

    fireEvent.click(within(variantsSection()).getByRole('button', { name: 'Sửa Biến thể Xanh / M' }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    expect(within(topDialog()).getByText('Sửa Biến thể')).toBeTruthy();
    expect((within(topDialog()).getByLabelText('Nhãn Biến thể') as HTMLInputElement).value).toBe('Xanh / M');
    expect((within(topDialog()).getByLabelText('Giá bán') as HTMLInputElement).value).toBe('160.000');
    // Không sửa Tồn kho, Giá vốn ở đây
    expect(within(topDialog()).queryByLabelText('Tồn kho đầu kỳ')).toBeNull();
    type(topDialog(), 'Giá bán', '170000');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Lưu Biến thể' }));

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));
    const puts = calls('PUT', VARIANT_PATH);
    expect(puts).toHaveLength(1);
    expect(new URL(String(puts[0][0]), 'http://localhost').pathname).toBe(`${PRODUCTS_PATH}/p-shirt/variants/v-blue`);
    expect(bodyOf(puts[0])).toEqual({
      name: 'Xanh / M',
      attributes: { Màu: 'Xanh', Size: 'M' },
      sku: 'SKU-V-BLUE',
      salePrice: 170000,
    });
    expect(within(variantTableRow('Xanh / M')).getByText(/170\.000/)).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });

    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Hủy' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(within(rowOf('Áo thun')).getByText(/150\.000\s₫\s–\s170\.000\s₫/)).toBeTruthy());
    // Không đổi gì ở Sản phẩm: không gửi PUT Sản phẩm
    expect(calls('PUT', PRODUCT_PATH)).toHaveLength(0);
  });

  it('thêm Biến thể cho Sản phẩm một Biến thể: phải đặt nhãn cho Biến thể hiện có; xong thì form ẩn ô giá', async () => {
    const { queryClient } = renderPage();
    await within(productsTable()).findByText('Bút bi');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await openEditDialog('Bút bi');
    expect(within(productDialog()).getByLabelText('Giá bán')).toBeTruthy();

    fireEvent.click(within(variantsSection()).getByRole('button', { name: /Thêm Biến thể/ }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    type(topDialog(), 'Nhãn Biến thể', 'Mực đỏ');
    type(topDialog(), 'Giá bán', '6000');
    type(topDialog(), 'Tồn kho đầu kỳ', '10');
    type(topDialog(), 'Giá vốn ban đầu', '3000');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Thêm Biến thể' }));
    expect(await within(topDialog()).findByText('Nhập nhãn cho Biến thể hiện có')).toBeTruthy();
    expect(calls('POST', VARIANTS_PATH)).toHaveLength(0);

    type(topDialog(), 'Nhãn cho Biến thể hiện có', ' Mực xanh ');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Thêm Biến thể' }));

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));
    const posts = calls('POST', VARIANTS_PATH);
    expect(posts).toHaveLength(1);
    expect(bodyOf(posts[0])).toEqual({
      existingVariantName: 'Mực xanh',
      name: 'Mực đỏ',
      salePrice: 6000,
      openingQuantity: 10,
      openingUnitCost: 3000,
    });
    expect(keyOf(posts[0])).toMatch(UUID);
    // Sản phẩm đã thành nhiều Biến thể: giá, mã sửa ở từng Biến thể
    await waitFor(() => expect(within(productDialog()).queryByLabelText('Giá bán')).toBeNull());
    expect(within(variantTableRow('Mực xanh')).getByText('Mặc định')).toBeTruthy();
    expect(within(variantTableRow('Mực đỏ')).getByText(/6\.000/)).toBeTruthy();
    // Thêm Biến thể tải lại các bảng của module khác; bảng Sản phẩm phía sau đã đổi ngay, chưa cần "Lưu"
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });
    await waitFor(() => expect(within(rowOf('Bút bi')).getByText('2 Biến thể')).toBeTruthy());

    // Lưu form Sản phẩm sau khi đã thành nhiều Biến thể: chỉ gửi các trường cấp Sản phẩm
    type(productDialog(), 'Tên Sản phẩm', 'Bút bi hai màu');
    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Lưu' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const puts = calls('PUT', PRODUCT_PATH);
    expect(puts).toHaveLength(1);
    expect(bodyOf(puts[0])).toEqual({ name: 'Bút bi hai màu', unit: 'cái' });
    await waitFor(() => expect(within(rowOf('Bút bi hai màu')).getByText('2 Biến thể')).toBeTruthy());
  });

  it('còn thay đổi Giá bán, SKU, Mã vạch chưa lưu thì chưa thêm được Biến thể; trả lại như cũ thì thêm được', async () => {
    renderPage();
    await within(productsTable()).findByText('Bút bi');
    await openEditDialog('Bút bi');
    const addButton = () => within(variantsSection()).getByRole('button', { name: /Thêm Biến thể/ });
    const hint = 'Lưu thay đổi Giá bán, SKU, Mã vạch trước khi thêm Biến thể';
    expect(addButton().hasAttribute('disabled')).toBe(false);

    type(productDialog(), 'Giá bán', '5500');
    await waitFor(() => expect(addButton().hasAttribute('disabled')).toBe(true));
    expect(within(variantsSection()).getByText(hint)).toBeTruthy();
    type(productDialog(), 'Giá bán', '5000');
    await waitFor(() => expect(addButton().hasAttribute('disabled')).toBe(false));
    expect(within(variantsSection()).queryByText(hint)).toBeNull();

    // SKU, mã vạch: so như khi lưu (không phân biệt hoa thường; SKU trống là giữ SKU cũ)
    type(productDialog(), 'SKU', 'sku-v-pen');
    type(productDialog(), 'Mã vạch', '8930001');
    await waitFor(() => expect(addButton().hasAttribute('disabled')).toBe(true));
    type(productDialog(), 'Mã vạch', '');
    type(productDialog(), 'SKU', '');
    await waitFor(() => expect(addButton().hasAttribute('disabled')).toBe(false));
  });

  it('mất mạng khi thêm Biến thể: báo ở khung lỗi, gửi lại dùng cùng Idempotency-Key và không thêm trùng', async () => {
    failNextAddWithNetworkError = true;
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    await openEditDialog('Áo thun');

    fireEvent.click(within(variantsSection()).getByRole('button', { name: /Thêm Biến thể/ }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    type(topDialog(), 'Nhãn Biến thể', 'Vàng / S');
    type(topDialog(), 'Giá bán', '150000');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Thêm Biến thể' }));
    expect(await within(topDialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();

    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Thêm Biến thể' }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));
    const [first, second] = calls('POST', VARIANTS_PATH);
    expect(keyOf(first)).toMatch(UUID);
    expect(keyOf(second)).toBe(keyOf(first));
    expect(within(variantTableRow('Vàng / S')).getByText(/150\.000/)).toBeTruthy();
    expect(products.find((p) => p.id === 'p-shirt')?.variants.filter((v) => v.name === 'Vàng / S')).toHaveLength(1);
  });

  it('tên thuộc tính điền sẵn theo thứ tự của nhãn (jsonb không giữ thứ tự khóa), cả khi thêm lẫn khi sửa', async () => {
    products = [
      product('p-basic', 'Áo basic', [
        variant('v-s', 'S / Đen', { Màu: 'Đen', Size: 'S' }, 99000),
        variant('v-m', 'M / Đen', { Màu: 'Đen', Size: 'M' }, 99000),
      ]),
    ];
    renderPage();
    await within(productsTable()).findByText('Áo basic');
    await openEditDialog('Áo basic');
    const valueIn = (label: string) => (within(topDialog()).getByLabelText(label) as HTMLInputElement).value;

    fireEvent.click(within(variantsSection()).getByRole('button', { name: /Thêm Biến thể/ }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    expect(valueIn('Tên thuộc tính 1')).toBe('Size');
    expect(valueIn('Tên thuộc tính 2')).toBe('Màu');
    expect(valueIn('Giá trị 1')).toBe('');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Hủy' }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));

    fireEvent.click(within(variantsSection()).getByRole('button', { name: 'Sửa Biến thể S / Đen' }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    expect(valueIn('Tên thuộc tính 1')).toBe('Size');
    expect(valueIn('Giá trị 1')).toBe('S');
    expect(valueIn('Tên thuộc tính 2')).toBe('Màu');
    expect(valueIn('Giá trị 2')).toBe('Đen');
    // Bỏ trống nhãn: ghép lại đúng thứ tự cũ
    type(topDialog(), 'Nhãn Biến thể', '');
    expect(within(topDialog()).getByText('Để trống: "S / Đen"')).toBeTruthy();
  });

  it('Sản phẩm chuyển từ một Biến thể: tên thuộc tính lấy từ Biến thể đang bán đầu tiên có thuộc tính', async () => {
    products = [
      product('p-jacket', 'Áo khoác', [
        variant('v-old', 'Size S', {}, 200000),
        { ...variant('v-stopped', 'Đỏ', { Màu: 'Đỏ' }, 200000), status: 'INACTIVE' },
        variant('v-m', 'M', { Size: 'M' }, 210000),
      ]),
    ];
    renderPage();
    await within(productsTable()).findByText('Áo khoác');
    await openEditDialog('Áo khoác');

    fireEvent.click(within(variantsSection()).getByRole('button', { name: /Thêm Biến thể/ }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    expect((within(topDialog()).getByLabelText('Tên thuộc tính 1') as HTMLInputElement).value).toBe('Size');
    expect((within(topDialog()).getByLabelText('Tên thuộc tính 2') as HTMLInputElement).value).toBe('');
  });

  it('Biến thể đã ngừng vẫn có "Sửa" (không có "Ngừng kinh doanh"): đổi nhãn để nhường lại nhãn cũ', async () => {
    products = [
      product('p-shirt', 'Áo thun', [
        variant('v-red', 'Đỏ / L', { Màu: 'Đỏ', Size: 'L' }, 150000),
        { ...variant('v-blue', 'Xanh / M', { Màu: 'Xanh', Size: 'M' }, 160000), status: 'INACTIVE' },
      ]),
    ];
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    // Chỉ đếm Biến thể đang bán
    expect(within(rowOf('Áo thun')).getByText('1 Biến thể')).toBeTruthy();
    await openEditDialog('Áo thun');

    const stopped = variantTableRow('Xanh / M');
    expect(within(stopped).queryByRole('button', { name: /Ngừng kinh doanh Biến thể/ })).toBeNull();
    fireEvent.click(within(stopped).getByRole('button', { name: 'Sửa Biến thể Xanh / M' }));
    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(2));
    type(topDialog(), 'Nhãn Biến thể', 'Xanh / M (lô cũ)');
    fireEvent.click(within(topDialog()).getByRole('button', { name: 'Lưu Biến thể' }));

    await waitFor(() => expect(screen.getAllByRole('dialog')).toHaveLength(1));
    const puts = calls('PUT', VARIANT_PATH);
    expect(puts).toHaveLength(1);
    expect(new URL(String(puts[0][0]), 'http://localhost').pathname).toBe(`${PRODUCTS_PATH}/p-shirt/variants/v-blue`);
    expect(bodyOf(puts[0])).toMatchObject({ name: 'Xanh / M (lô cũ)', salePrice: 160000 });
    expect(within(variantTableRow('Xanh / M (lô cũ)')).getByText('Ngừng kinh doanh')).toBeTruthy();
  });

  it('Sản phẩm nhiều Biến thể: dòng của bảng không có "Lịch sử kho"; mỗi Biến thể có link lọc sẵn theo chính nó', async () => {
    renderPage();
    await within(productsTable()).findByText('Áo thun');
    expect(within(rowOf('Áo thun')).queryByRole('link', { name: /Lịch sử kho/ })).toBeNull();
    expect(within(rowOf('Bút bi')).getByRole('link', { name: 'Lịch sử kho Bút bi' }).getAttribute('href')).toBe(
      '/catalog/stock-ledger?productId=p-pen&variantId=v-pen',
    );

    await openEditDialog('Áo thun');
    const link = within(variantTableRow('Xanh / M')).getByRole('link', { name: 'Lịch sử kho Biến thể Xanh / M' });
    expect(link.getAttribute('href')).toBe('/catalog/stock-ledger?productId=p-shirt&variantId=v-blue');
  });

  it('ngừng kinh doanh một Biến thể có xác nhận; Biến thể đang bán cuối cùng thì báo lỗi, không đổi gì', async () => {
    const { queryClient } = renderPage();
    await within(productsTable()).findByText('Áo thun');
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await openEditDialog('Áo thun');

    fireEvent.click(within(variantsSection()).getByRole('button', { name: 'Ngừng kinh doanh Biến thể Đỏ / L' }));
    await screen.findAllByText('Ngừng kinh doanh Biến thể "Đỏ / L"?');
    clickLast('Ngừng kinh doanh');

    await waitFor(() => expect(calls('POST', VARIANT_DEACTIVATE_PATH)).toHaveLength(1));
    await waitFor(() =>
      expect(within(variantTableRow('Đỏ / L')).queryByRole('button', { name: /Ngừng kinh doanh Biến thể/ })).toBeNull(),
    );
    expect(within(variantTableRow('Đỏ / L')).getByText('Ngừng kinh doanh')).toBeTruthy();
    // Biến thể đang bán còn lại thành mặc định
    expect(within(variantTableRow('Xanh / M')).getByText('Mặc định')).toBeTruthy();

    fireEvent.click(within(variantsSection()).getByRole('button', { name: 'Ngừng kinh doanh Biến thể Xanh / M' }));
    await screen.findAllByText('Ngừng kinh doanh Biến thể "Xanh / M"?');
    clickLast('Ngừng kinh doanh');
    expect(
      await within(variantsSection()).findByText(PROBLEM_MESSAGES['/errors/catalog/last-active-variant'].message),
    ).toBeTruthy();
    expect(within(variantTableRow('Xanh / M')).getByText('Đang bán')).toBeTruthy();
    // Biến thể ẩn khỏi màn bán hàng, Tồn kho đặt ngừng: các bảng của module khác tải lại
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sales', 'sellable'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['inventory', 'stock'] });

    fireEvent.click(within(productDialog()).getByRole('button', { name: 'Hủy' }));
    // Hộp xác nhận đã đóng có thể còn trong DOM (jsdom không chạy hiệu ứng đóng): chỉ chờ form Sản phẩm đóng
    await waitFor(() =>
      expect(screen.queryAllByRole('dialog').filter((d) => d.textContent?.includes('Sửa Sản phẩm'))).toHaveLength(0),
    );
    // Chỉ còn một Biến thể đang bán: giá của nó thay cho khoảng giá
    await waitFor(() => expect(within(rowOf('Áo thun')).getByText('1 Biến thể')).toBeTruthy());
    expect(within(rowOf('Áo thun')).getByText(/^160\.000\s₫$/)).toBeTruthy();
    expect(within(rowOf('Áo thun')).queryByText(/–/)).toBeNull();
  });
});
