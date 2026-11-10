import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Category } from '../../../shared/types/catalog';
import { CategoriesPage } from './CategoriesPage';

const CATEGORIES_PATH = '/api/v1/catalog/categories';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

let categories: Category[];
let nextId: number;
/** Khi có, mọi yêu cầu ghi chờ tới lúc được thả (để thấy trạng thái đang gửi). */
let writeGate: Promise<void> | undefined;

function holdWrites(): () => void {
  let release = () => {};
  writeGate = new Promise<void>((resolve) => {
    release = resolve;
  });
  return () => {
    writeGate = undefined;
    release();
  };
}

/** Backend giả giữ Danh mục trong bộ nhớ, có phân trang, trả đúng mã lỗi của SP-04. */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (method !== 'GET' && writeGate) await writeGate;
    const body = init?.body ? (JSON.parse(String(init.body)) as { name: string }) : undefined;
    const taken = (name: string, exceptId?: string) =>
      categories.some((c) => c.id !== exceptId && c.name.toLowerCase() === name.toLowerCase());
    const nameTaken = json(
      { type: '/errors/catalog/category-name-taken', title: 'Tên Danh mục đã có', status: 409, requestId: 'req-1' },
      409,
    );

    if (url.pathname === CATEGORIES_PATH && method === 'GET') {
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return json({ items: categories.slice(page * size, (page + 1) * size), total: categories.length, page, size });
    }
    if (url.pathname === CATEGORIES_PATH && method === 'POST' && body) {
      if (taken(body.name)) return nameTaken;
      const created: Category = { id: `c-${nextId++}`, name: body.name, productCount: 0 };
      categories = [...categories, created];
      return json(created, 201);
    }
    const id = url.pathname.startsWith(`${CATEGORIES_PATH}/`) ? url.pathname.slice(CATEGORIES_PATH.length + 1) : null;
    const existing = categories.find((c) => c.id === id);
    if (existing && method === 'PUT' && body) {
      if (taken(body.name, existing.id)) return nameTaken;
      const renamed = { ...existing, name: body.name };
      categories = categories.map((c) => (c.id === existing.id ? renamed : c));
      return json(renamed);
    }
    if (existing && method === 'DELETE') {
      if (existing.productCount > 0) {
        return json(
          { type: '/errors/catalog/category-in-use', title: 'Danh mục còn Sản phẩm', status: 409, requestId: 'req-2' },
          409,
        );
      }
      categories = categories.filter((c) => c.id !== existing.id);
      return new Response(null, { status: 204 });
    }
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <CategoriesPage />
      </AntdApp>
    </QueryClientProvider>,
  );
}

function callsTo(method: string, path: string) {
  return fetchMock.mock.calls.filter(
    ([input, init]) => (init?.method ?? 'GET') === method && new URL(String(input), 'http://localhost').pathname === path,
  );
}

function bodyOf(call: Parameters<typeof fetch>): unknown {
  return JSON.parse(String(call[1]?.body));
}

function table(): HTMLElement {
  return screen.getByRole('table');
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function typeName(value: string) {
  fireEvent.change(within(dialog()).getByLabelText('Tên Danh mục'), { target: { value } });
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

async function openAddDialog() {
  fireEvent.click(screen.getByRole('button', { name: /Thêm Danh mục/ }));
  await screen.findByRole('dialog');
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  categories = [
    { id: 'c-1', name: 'Bia', productCount: 0 },
    { id: 'c-2', name: 'Đồ uống', productCount: 12 },
  ];
  nextId = 3;
  writeGate = undefined;
  backend();
});

describe('Danh mục (FR-4, SP-04)', () => {
  it('hiện bảng Danh mục kèm số Sản phẩm', async () => {
    renderPage();
    expect(await within(table()).findByText('Đồ uống')).toBeTruthy();
    expect(within(table()).getByText('Bia')).toBeTruthy();
    expect(within(table()).getByText('12')).toBeTruthy();
    const [call] = callsTo('GET', CATEGORIES_PATH);
    const query = new URL(String(call[0]), 'http://localhost').searchParams;
    expect(query.get('page')).toBe('0');
    expect(query.get('size')).toBe('20');
  });

  it('thêm Danh mục: gửi tên đã cắt khoảng trắng, đóng hộp thoại, bảng có dòng mới ngay', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    await openAddDialog();
    typeName('  Bánh kẹo  ');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(table()).findByText('Bánh kẹo')).toBeTruthy();
    const [post] = callsTo('POST', CATEGORIES_PATH);
    expect(bodyOf(post)).toEqual({ name: 'Bánh kẹo' });
    expect(new Headers(post[1]?.headers).get('Idempotency-Key')).toBeNull();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(callsTo('GET', CATEGORIES_PATH).length).toBeGreaterThanOrEqual(2);
  });

  it('kiểm tra phía client: tên trống hoặc quá 100 ký tự thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    await openAddDialog();
    typeName('   ');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(dialog()).findByText('Nhập tên Danh mục')).toBeTruthy();

    typeName('a'.repeat(101));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(dialog()).findByText('Tên Danh mục tối đa 100 ký tự')).toBeTruthy();

    expect(callsTo('POST', CATEGORIES_PATH)).toHaveLength(0);
  });

  it('trùng tên: báo dưới ô bằng câu tiếng Việt và giữ hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    await openAddDialog();
    typeName('BIA');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Danh mục tên này.')).toBeTruthy();
    expect(within(dialog()).getByText(/không phân biệt chữ hoa, chữ thường/)).toBeTruthy();
    expect(within(dialog()).getByLabelText('Tên Danh mục').getAttribute('aria-invalid')).toBe('true');
    expect(categories).toHaveLength(2);
  });

  it('đổi tên: hộp thoại có sẵn tên cũ, lưu xong bảng đổi ngay', async () => {
    renderPage();
    await within(table()).findByText('Đồ uống');

    fireEvent.click(screen.getByRole('button', { name: 'Đổi tên Đồ uống' }));
    await screen.findByRole('dialog');
    expect((within(dialog()).getByLabelText('Tên Danh mục') as HTMLInputElement).value).toBe('Đồ uống');
    typeName('Nước giải khát ');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(table()).findByText('Nước giải khát')).toBeTruthy();
    expect(within(table()).queryByText('Đồ uống')).toBeNull();
    const [put] = callsTo('PUT', `${CATEGORIES_PATH}/c-2`);
    expect(bodyOf(put)).toEqual({ name: 'Nước giải khát' });
  });

  it('xóa có xác nhận: bấm Không thì không gửi, đồng ý thì dòng biến mất', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    fireEvent.click(screen.getByRole('button', { name: 'Xóa Bia' }));
    await findConfirm('Xóa Danh mục "Bia"?');
    clickConfirmButton('Không');
    expect(callsTo('DELETE', `${CATEGORIES_PATH}/c-1`)).toHaveLength(0);
    expect(within(table()).getByText('Bia')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Xóa Bia' }));
    await findConfirm('Xóa Danh mục "Bia"?');
    clickConfirmButton('Xóa');

    await waitFor(() => expect(within(table()).queryByText('Bia')).toBeNull());
    expect(callsTo('DELETE', `${CATEGORIES_PATH}/c-1`)).toHaveLength(1);
    expect(within(table()).getByText('Đồ uống')).toBeTruthy();
  });

  it('Danh mục còn Sản phẩm: nút Xóa tắt, di chuột thấy lý do', async () => {
    renderPage();
    await within(table()).findByText('Đồ uống');

    const button = screen.getByRole('button', { name: 'Xóa Đồ uống' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true);
    expect((screen.getByRole('button', { name: 'Xóa Bia' }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.mouseOver(button.parentElement ?? button);
    expect(await screen.findByText('Còn 12 Sản phẩm, không xóa được')).toBeTruthy();
  });

  it('bảng cũ mà Danh mục vừa có Sản phẩm: hiện câu tiếng Việt, đóng được, bảng tải lại', async () => {
    renderPage();
    await within(table()).findByText('Bia');
    // Có Sản phẩm mới thêm vào "Bia" ở nơi khác sau khi bảng đã tải
    categories = categories.map((c) => (c.id === 'c-1' ? { ...c, productCount: 3 } : c));

    fireEvent.click(screen.getByRole('button', { name: 'Xóa Bia' }));
    await findConfirm('Xóa Danh mục "Bia"?');
    clickConfirmButton('Xóa');

    expect(await screen.findByText('Danh mục này còn Sản phẩm nên không xóa được.')).toBeTruthy();
    expect(screen.getByText(/Chuyển các Sản phẩm sang Danh mục khác/)).toBeTruthy();
    expect(categories).toHaveLength(2);
    // Lỗi cũng làm bảng tải lại: số Sản phẩm mới hiện ra và nút Xóa tắt
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Xóa Bia' }) as HTMLButtonElement).disabled).toBe(true),
    );
    expect(within(table()).getByText('3')).toBeTruthy();

    const alert = screen
      .getAllByRole('alert')
      .find((el) => el.textContent?.includes('Danh mục này còn Sản phẩm nên không xóa được.'));
    expect(alert).toBeTruthy();
    fireEvent.click(within(alert as HTMLElement).getByRole('button'));
    await waitFor(() => expect(screen.queryByText('Danh mục này còn Sản phẩm nên không xóa được.')).toBeNull());
  });

  it('đang xóa thì mọi nút Xóa tắt, không bắt đầu được lần xóa thứ hai', async () => {
    categories = [...categories, { id: 'c-3', name: 'Cà phê', productCount: 0 }];
    const release = holdWrites();
    renderPage();
    await within(table()).findByText('Cà phê');

    fireEvent.click(screen.getByRole('button', { name: 'Xóa Bia' }));
    await findConfirm('Xóa Danh mục "Bia"?');
    clickConfirmButton('Xóa');
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Xóa Cà phê' }) as HTMLButtonElement).disabled).toBe(true),
    );

    release();
    await waitFor(() => expect(within(table()).queryByText('Bia')).toBeNull());
    expect((screen.getByRole('button', { name: 'Xóa Cà phê' }) as HTMLButtonElement).disabled).toBe(false);
    expect(callsTo('DELETE', `${CATEGORIES_PATH}/c-1`)).toHaveLength(1);
  });

  it('xóa dòng cuối cùng của trang sau thì lùi về trang trước', async () => {
    categories = Array.from({ length: 21 }, (_, i) => ({
      id: `c-${i + 1}`,
      name: `Danh mục ${String(i + 1).padStart(2, '0')}`,
      productCount: 0,
    }));
    renderPage();
    await within(table()).findByText('Danh mục 01');

    fireEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    expect(await within(table()).findByText('Danh mục 21')).toBeTruthy();
    expect(within(table()).queryByText('Danh mục 01')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Xóa Danh mục 21' }));
    await findConfirm('Xóa Danh mục "Danh mục 21"?');
    clickConfirmButton('Xóa');

    expect(await within(table()).findByText('Danh mục 01')).toBeTruthy();
    expect(within(table()).getByText('Danh mục 20')).toBeTruthy();
    const lastGet = callsTo('GET', CATEGORIES_PATH).at(-1);
    expect(new URL(String(lastGet?.[0]), 'http://localhost').searchParams.get('page')).toBe('0');
  });

  it('đổi tên trùng Danh mục khác: báo dưới ô, giữ hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    fireEvent.click(screen.getByRole('button', { name: 'Đổi tên Bia' }));
    await screen.findByRole('dialog');
    typeName('đồ uống');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Danh mục tên này.')).toBeTruthy();
    expect(within(dialog()).getByLabelText('Tên Danh mục').getAttribute('aria-invalid')).toBe('true');
    expect(categories.find((c) => c.id === 'c-1')?.name).toBe('Bia');
  });

  it('đổi tên mà tên không đổi (kể cả gõ dạng tổ hợp NFD, thừa khoảng trắng): không gửi PUT, đóng hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Đồ uống');

    fireEvent.click(screen.getByRole('button', { name: 'Đổi tên Đồ uống' }));
    await screen.findByRole('dialog');
    const decomposed = 'Đồ uống'.normalize('NFD');
    expect(decomposed).not.toBe('Đồ uống');
    typeName(` ${decomposed} `);
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(callsTo('PUT', `${CATEGORIES_PATH}/c-2`)).toHaveLength(0);
  });

  it('tên gõ dạng tổ hợp NFD được đếm và gửi theo NFC', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    await openAddDialog();
    const name = 'ệ'.repeat(100);
    typeName(name.normalize('NFD'));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    await waitFor(() => expect(callsTo('POST', CATEGORIES_PATH)).toHaveLength(1));
    expect(bodyOf(callsTo('POST', CATEGORIES_PATH)[0])).toEqual({ name });
  });

  it('đổi tên Danh mục đã bị xóa ở nơi khác: báo không tìm thấy trong hộp thoại, bảng tải lại', async () => {
    renderPage();
    await within(table()).findByText('Bia');

    fireEvent.click(screen.getByRole('button', { name: 'Đổi tên Bia' }));
    await screen.findByRole('dialog');
    categories = categories.filter((c) => c.id !== 'c-1');
    typeName('Bia hơi');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    await waitFor(() => expect(within(table()).queryByText('Bia')).toBeNull());
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('đang gửi thì không đóng được hộp thoại', async () => {
    const release = holdWrites();
    renderPage();
    await within(table()).findByText('Bia');

    await openAddDialog();
    typeName('Bánh kẹo');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    await waitFor(() =>
      expect((within(dialog()).getByRole('button', { name: 'Hủy' }) as HTMLButtonElement).disabled).toBe(true),
    );
    expect(within(dialog()).queryByRole('button', { name: /close/i })).toBeNull();
    fireEvent.keyDown(dialog(), { key: 'Escape' });
    expect(screen.getByRole('dialog')).toBeTruthy();

    release();
    expect(await within(table()).findByText('Bánh kẹo')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });
});
