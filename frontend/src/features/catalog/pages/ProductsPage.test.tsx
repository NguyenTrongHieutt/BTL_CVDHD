import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import type { Category, CreateProductRequest, Product } from '../../../shared/types/catalog';
import type { Me, Role } from '../../../shared/types/platform';
import { ProductsPage } from './ProductsPage';

const PRODUCTS_PATH = '/api/v1/catalog/products';
const CATEGORIES_PATH = '/api/v1/catalog/categories';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

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

/** Mới nhất trước, như backend. */
let products: Product[];
let categories: Category[];
let nextId: number;
/** Lần POST tới trả lỗi mạng (fetch ném lỗi) thay vì gọi backend giả. */
let failNextPostWithNetworkError: boolean;
/** Lần POST tới được backend ghi nhưng phản hồi bị mất (lỗi mạng sau khi đã tạo). */
let loseNextPostResponse: boolean;

/**
 * Backend giả: danh sách có phân trang, POST trả đúng mã lỗi của SP-01 và phát lại theo `Idempotency-Key` (cùng
 * nội dung thì trả lại Sản phẩm cũ, khác nội dung thì `409 idempotency-key-reuse`).
 */
function backend() {
  const byKey = new Map<string, { body: string; product: Product }>();
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (url.pathname === CATEGORIES_PATH && method === 'GET') {
      return json({ items: categories, total: categories.length, page: 0, size: 100 });
    }
    if (url.pathname === PRODUCTS_PATH && method === 'GET') {
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return json({ items: products.slice(page * size, (page + 1) * size), total: products.length, page, size });
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
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

function me(role: Role): Me {
  return { userId: 'u-1', storeId: 's-1', storeName: 'Cửa hàng thử', role, displayName: 'Người thử' };
}

function renderPage(role: Role = 'OWNER') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <CurrentUserContext value={me(role)}>
          <ProductsPage />
        </CurrentUserContext>
      </AntdApp>
    </QueryClientProvider>,
  );
}

function posts() {
  return fetchMock.mock.calls.filter(
    ([input, init]) =>
      init?.method === 'POST' && new URL(String(input), 'http://localhost').pathname === PRODUCTS_PATH,
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
  backend();
});

describe('ProductsPage', () => {
  it('hiện bảng mới nhất trước: tên, Danh mục, SKU, giá bán; không có Giá vốn', async () => {
    renderPage();
    await within(table()).findByText('Sản phẩm 02');

    expect(rowNames()).toEqual(['Sản phẩm 02', 'Sản phẩm 01']);
    expect(within(table()).getByText('Đồ uống')).toBeTruthy();
    expect(within(table()).getByText('SP-00000002')).toBeTruthy();
    expect(within(table()).getByText(/2\.000/)).toBeTruthy();
    expect(within(table()).getAllByText('Đang kinh doanh')).toHaveLength(2);
    expect(screen.queryByText(/Giá vốn/)).toBeNull();
  });

  it('Nhân viên thấy bảng nhưng không thấy nút "Thêm Sản phẩm"', async () => {
    renderPage('STAFF');
    expect(await within(table()).findByText('Sản phẩm 01')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Thêm Sản phẩm/ })).toBeNull();
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
    await waitFor(() => expect(rowNames()[0]).toBe('Sữa tươi Vinamilk 1L'));
    expect(rowNames()).toHaveLength(3);
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
    await within(table()).findByText('Sản phẩm 25');

    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    expect(await within(table()).findByText('Sản phẩm 05')).toBeTruthy();

    await openAddDialog();
    type('Tên Sản phẩm', 'Hàng mới nhất');
    type('Giá bán', '1000');
    submit();

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rowNames()[0]).toBe('Hàng mới nhất'));
    expect(rowNames()[1]).toBe('Sản phẩm 25');
  });
});
