import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Customer, CustomerRequest } from '../../../shared/types/catalog';
import { foldText } from '../components/textUtils';
import { CustomersPage } from './CustomersPage';

const CUSTOMERS_PATH = '/api/v1/catalog/customers';
const PHONE_INVALID = 'Số điện thoại phải là 0 và 9 chữ số, hoặc +84 và 9 chữ số';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function customer(id: string, name: string | null, phone: string, note: string | null = null): Customer {
  return { customerId: id, name, phone, note, createdAt: '2026-11-10T03:00:00Z' };
}

let customers: Customer[];
let nextId: number;
/** Khi có, mọi yêu cầu ghi chờ tới lúc được thả (để thấy trạng thái đang gửi). */
let writeGate: Promise<void> | undefined;
/** `409` trùng số không kèm `existingCustomerId` (hiếm: số vừa được trả lại). */
let omitExistingId: boolean;

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

/** Chữ số của `q` để so với số điện thoại, như backend: có chữ cái thì không so; `+84` ở đầu là `0`. */
function phoneDigits(q: string): string | null {
  if (/\p{L}/u.test(q)) return null;
  const kept = q.replace(/[^0-9+]/g, '');
  const digits = kept.startsWith('+84') ? `0${kept.slice(3).replace(/\D/g, '')}` : kept.replace(/\D/g, '');
  return digits === '' ? null : digits;
}

/** Như backend: tên không dấu (chưa có tên xếp sau), rồi số điện thoại. */
function byNameThenPhone(a: Customer, b: Customer): number {
  if (a.name !== b.name) {
    if (a.name === null) return 1;
    if (b.name === null) return -1;
    const [x, y] = [foldText(a.name), foldText(b.name)];
    if (x !== y) return x < y ? -1 : 1;
  }
  return a.phone < b.phone ? -1 : a.phone > b.phone ? 1 : 0;
}

function phoneTaken(existing: Customer): Response {
  return json(
    {
      type: '/errors/catalog/customer-phone-taken',
      title: 'Số điện thoại đã có',
      status: 409,
      requestId: 'req-409',
      ...(omitExistingId ? {} : { existingCustomerId: existing.customerId }),
    },
    409,
  );
}

/**
 * Backend giả giữ Khách hàng trong bộ nhớ: danh sách có tìm kiếm (tên không dấu, số khớp một phần), sắp, phân trang;
 * POST, PUT trả `409 customer-phone-taken` kèm `existingCustomerId` khi trùng số. Tên `!bad` bị từ chối (`400` trên
 * `name`) để thấy lỗi theo trường của backend.
 */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (method !== 'GET' && writeGate) await writeGate;
    const body = init?.body ? (JSON.parse(String(init.body)) as CustomerRequest) : undefined;
    const badName =
      body?.name === '!bad'
        ? json(
            {
              type: '/errors/common/validation',
              title: 'Dữ liệu không hợp lệ',
              status: 400,
              requestId: 'req-400',
              errors: [{ field: 'name', message: 'Tên Khách hàng có ký tự không hợp lệ' }],
            },
            400,
          )
        : null;

    if (url.pathname === CUSTOMERS_PATH && method === 'GET') {
      const q = url.searchParams.get('q');
      const digits = q === null ? null : phoneDigits(q);
      const matching = customers
        .filter(
          (c) =>
            q === null ||
            (c.name !== null && foldText(c.name).includes(foldText(q))) ||
            (digits !== null && c.phone.includes(digits)),
        )
        .sort(byNameThenPhone);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return json({ items: matching.slice(page * size, (page + 1) * size), total: matching.length, page, size });
    }
    if (url.pathname === CUSTOMERS_PATH && method === 'POST' && body) {
      if (badName) return badName;
      const existing = customers.find((c) => c.phone === body.phone);
      if (existing) return phoneTaken(existing);
      const created = customer(`k-${nextId++}`, body.name ?? null, body.phone, body.note ?? null);
      customers = [...customers, created];
      return json(created, 201);
    }
    const id = url.pathname.startsWith(`${CUSTOMERS_PATH}/`) ? url.pathname.slice(CUSTOMERS_PATH.length + 1) : null;
    const found = customers.find((c) => c.customerId === id);
    if (found && method === 'GET') return json(found);
    if (found && method === 'PUT' && body) {
      if (badName) return badName;
      const other = customers.find((c) => c.phone === body.phone && c.customerId !== found.customerId);
      if (other) return phoneTaken(other);
      const updated: Customer = { ...found, phone: body.phone, name: body.name ?? null, note: body.note ?? null };
      customers = customers.map((c) => (c.customerId === found.customerId ? updated : c));
      return json(updated);
    }
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <CustomersPage />
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

function lastListQuery(): Record<string, string> {
  const call = callsTo('GET', CUSTOMERS_PATH).at(-1);
  return Object.fromEntries(new URL(String(call?.[0]), 'http://localhost').searchParams);
}

function table(): HTMLElement {
  return screen.getByRole('table');
}

/** Tên, số điện thoại của các dòng trong bảng. */
function rows(): string[] {
  return within(table())
    .getAllByRole('row')
    .slice(1)
    .map((row) => {
      const cells = within(row).getAllByRole('cell');
      return `${cells[0]?.textContent ?? ''} · ${cells[1]?.textContent ?? ''}`;
    });
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function field(label: 'Số điện thoại' | 'Tên Khách hàng' | 'Ghi chú'): HTMLInputElement {
  return within(dialog()).getByLabelText(label) as HTMLInputElement;
}

function type(label: 'Số điện thoại' | 'Tên Khách hàng' | 'Ghi chú', value: string) {
  fireEvent.change(field(label), { target: { value } });
}

function searchBox(): HTMLInputElement {
  return screen.getByLabelText('Tìm Khách hàng') as HTMLInputElement;
}

function search(value: string) {
  fireEvent.change(searchBox(), { target: { value } });
  fireEvent.keyDown(searchBox(), { key: 'Enter', code: 'Enter', keyCode: 13 });
}

async function openAddDialog() {
  fireEvent.click(screen.getByRole('button', { name: /Thêm Khách hàng/ }));
  await screen.findByRole('dialog');
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  customers = [
    customer('k-1', 'Chị Hoa', '0912345678', 'Khách quen'),
    customer('k-2', 'Anh Ba', '0987654321'),
    customer('k-3', null, '0933000111'),
  ];
  nextId = 4;
  writeGate = undefined;
  omitExistingId = false;
  backend();
});

describe('Khách hàng (FR-18 KH-01, FR-19 KH-02)', () => {
  it('hiện bảng Khách hàng: sắp theo tên, số điện thoại nhóm cho dễ đọc, chưa có tên thì ghi rõ', async () => {
    renderPage();
    expect(await within(table()).findByText('Chị Hoa')).toBeTruthy();
    expect(rows()).toEqual(['Anh Ba · 0987 654 321', 'Chị Hoa · 0912 345 678', 'Chưa có tên · 0933 000 111']);
    expect(within(table()).getByText('Khách quen')).toBeTruthy();
    expect(lastListQuery()).toEqual({ page: '0', size: '20' });
  });

  it('Nhân viên thêm Khách hàng chỉ với số điện thoại: gửi số đã chuẩn hóa, Khách hàng hiện trong bảng ngay', async () => {
    // Đủ nhiều Khách hàng có tên để Khách hàng chưa có tên rơi xuống trang sau nếu không lọc
    customers = Array.from({ length: 25 }, (_, i) =>
      customer(`k-${100 + i}`, `Khách ${String(i).padStart(2, '0')}`, `09000000${String(i).padStart(2, '0')}`),
    );
    renderPage();
    await within(table()).findByText('Khách 00');

    await openAddDialog();
    type('Số điện thoại', '0901 234 567');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rows()).toEqual(['Chưa có tên · 0901 234 567']));
    const [post] = callsTo('POST', CUSTOMERS_PATH);
    expect(bodyOf(post)).toEqual({ phone: '0901234567' });
    expect(new Headers(post[1]?.headers).get('Idempotency-Key')).toBeNull();
    // Ô tìm kiếm điền số vừa thêm; xóa đi thì về danh sách đầy đủ
    expect(searchBox().value).toBe('0901234567');
    expect(lastListQuery()).toEqual({ page: '0', size: '20', q: '0901234567' });
    expect(await screen.findByText('Đã thêm Khách hàng "0901 234 567"')).toBeTruthy();
  });

  it('thêm đủ tên, ghi chú: số +84 gửi dạng 0…, chữ được chuẩn hóa', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '+84 912-345-679');
    type('Tên Khách hàng', '  Cô Lan ');
    type('Ghi chú', 'Hay mua\r\nbuổi sáng ');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    await waitFor(() => expect(callsTo('POST', CUSTOMERS_PATH)).toHaveLength(1));
    expect(bodyOf(callsTo('POST', CUSTOMERS_PATH)[0])).toEqual({
      phone: '0912345679',
      name: 'Cô Lan',
      note: 'Hay mua\nbuổi sáng',
    });
    expect(await within(table()).findByText('Cô Lan')).toBeTruthy();
  });

  it('kiểm tra phía client: số trống hoặc sai, tên quá dài thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(dialog()).findByText('Nhập số điện thoại')).toBeTruthy();

    for (const phone of ['123', '0812', '+84 0912 345 678', '0912_345_678']) {
      type('Số điện thoại', phone);
      fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
      expect(await within(dialog()).findByText(PHONE_INVALID)).toBeTruthy();
    }

    type('Số điện thoại', '0912 000 001');
    type('Tên Khách hàng', 'a'.repeat(101));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(dialog()).findByText('Tên Khách hàng tối đa 100 ký tự')).toBeTruthy();

    expect(callsTo('POST', CUSTOMERS_PATH)).toHaveLength(0);
  });

  it('trùng số: báo dưới ô số điện thoại, bấm "Mở Khách hàng đã có" thì chuyển sang sửa Khách hàng đó', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '+84 912 345 678');
    type('Tên Khách hàng', 'Người khác');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Khách hàng dùng số điện thoại này.')).toBeTruthy();
    expect(field('Số điện thoại').getAttribute('aria-invalid')).toBe('true');
    expect(customers).toHaveLength(3);

    fireEvent.click(within(dialog()).getByRole('button', { name: 'Mở Khách hàng đã có' }));
    expect(await screen.findByText('Sửa Khách hàng')).toBeTruthy();
    expect(callsTo('GET', `${CUSTOMERS_PATH}/k-1`)).toHaveLength(1);
    expect(field('Số điện thoại').value).toBe('0912 345 678');
    expect(field('Tên Khách hàng').value).toBe('Chị Hoa');
    expect(field('Ghi chú').value).toBe('Khách quen');
  });

  it('sửa số điện thoại thì nút "Mở Khách hàng đã có" biến mất', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '0912345678');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    expect(await within(dialog()).findByRole('button', { name: 'Mở Khách hàng đã có' })).toBeTruthy();

    type('Số điện thoại', '0912345670');
    await waitFor(() => expect(within(dialog()).queryByRole('button', { name: 'Mở Khách hàng đã có' })).toBeNull());
  });

  it('sửa: hộp thoại có sẵn dữ liệu, lưu xong bảng đổi ngay', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Chị Hoa' }));
    await screen.findByRole('dialog');
    expect(within(dialog()).getByText('Sửa Khách hàng')).toBeTruthy();
    expect(field('Số điện thoại').value).toBe('0912 345 678');
    type('Tên Khách hàng', 'Chị Hoa (chợ Bến Thành)');
    type('Ghi chú', '');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(table()).findByText('Chị Hoa (chợ Bến Thành)')).toBeTruthy();
    const [put] = callsTo('PUT', `${CUSTOMERS_PATH}/k-1`);
    // PUT thay toàn bộ: ghi chú để trống thì không gửi (backend xóa giá trị cũ)
    expect(bodyOf(put)).toEqual({ phone: '0912345678', name: 'Chị Hoa (chợ Bến Thành)' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('sửa mà không đổi gì (kể cả gõ lại số theo cách khác): không gửi PUT, đóng hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Chị Hoa' }));
    await screen.findByRole('dialog');
    type('Số điện thoại', '+84912345678');
    type('Tên Khách hàng', ' Chị Hoa ');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(callsTo('PUT', `${CUSTOMERS_PATH}/k-1`)).toHaveLength(0);
  });

  it('sửa sang số Khách hàng khác đang dùng: báo dưới ô, mở được Khách hàng đó', async () => {
    renderPage();
    await within(table()).findByText('Anh Ba');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Anh Ba' }));
    await screen.findByRole('dialog');
    type('Số điện thoại', '0912 345 678');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Khách hàng dùng số điện thoại này.')).toBeTruthy();
    expect(customers.find((c) => c.customerId === 'k-2')?.phone).toBe('0987654321');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Mở Khách hàng đã có' }));
    await waitFor(() => expect(field('Tên Khách hàng').value).toBe('Chị Hoa'));
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '0912000002');
    type('Tên Khách hàng', '!bad');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(dialog()).findByText('Tên Khách hàng có ký tự không hợp lệ')).toBeTruthy();
    expect(field('Tên Khách hàng').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('tìm theo tên không dấu, theo số khớp một phần; không khớp thì xóa tìm kiếm được', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    search('chi hoa');
    await waitFor(() => expect(rows()).toEqual(['Chị Hoa · 0912 345 678']));
    expect(lastListQuery()).toEqual({ page: '0', size: '20', q: 'chi hoa' });

    search('345 678');
    await waitFor(() => expect(lastListQuery().q).toBe('345 678'));
    await waitFor(() => expect(rows()).toEqual(['Chị Hoa · 0912 345 678']));

    search('+84 987');
    await waitFor(() => expect(rows()).toEqual(['Anh Ba · 0987 654 321']));

    search('không có ai');
    expect(await screen.findByText('Không có Khách hàng nào khớp "không có ai".')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Thêm Khách hàng với số này/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa tìm kiếm' }));
    await waitFor(() => expect(rows()).toHaveLength(3));
    expect(searchBox().value).toBe('');
  });

  it('tìm một số đầy đủ không ra ai: "Thêm Khách hàng với số này" mở hộp thoại điền sẵn số', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    // Số chưa đầy đủ thì không mời thêm
    search('111 222');
    expect(await screen.findByText('Không có Khách hàng nào khớp "111 222".')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Thêm Khách hàng với số này/ })).toBeNull();

    search('+84 909 111 222');
    fireEvent.click(await screen.findByRole('button', { name: /Thêm Khách hàng với số này/ }));
    await screen.findByRole('dialog');
    expect(within(dialog()).getByText('Thêm Khách hàng')).toBeTruthy();
    expect(field('Số điện thoại').value).toBe('0909 111 222');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    await waitFor(() => expect(rows()).toEqual(['Chưa có tên · 0909 111 222']));
    expect(bodyOf(callsTo('POST', CUSTOMERS_PATH)[0])).toEqual({ phone: '0909111222' });
  });

  it('trùng số: bảng tải lại sau lỗi và hiện dữ liệu đã đổi ở nơi khác', async () => {
    renderPage();
    await within(table()).findByText('Anh Ba');
    // Ở thiết bị khác: Anh Ba vừa được đổi tên
    customers = customers.map((c) => (c.customerId === 'k-2' ? { ...c, name: 'Anh Ba (mới)' } : c));

    await openAddDialog();
    type('Số điện thoại', '0987 654 321');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Khách hàng dùng số điện thoại này.')).toBeTruthy();
    expect(await within(table()).findByText('Anh Ba (mới)')).toBeTruthy();
  });

  it('trùng số mà không có existingCustomerId: chỉ báo trùng, không có gợi ý và nút mở', async () => {
    omitExistingId = true;
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '0912345678');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));

    expect(await within(dialog()).findByText('Cửa hàng đã có Khách hàng dùng số điện thoại này.')).toBeTruthy();
    expect(within(dialog()).queryByText(/Mở Khách hàng đã có để xem hoặc sửa/)).toBeNull();
    expect(within(dialog()).queryByRole('button', { name: 'Mở Khách hàng đã có' })).toBeNull();
  });

  it('sửa Khách hàng không còn (404): báo trong hộp thoại, giữ hộp thoại, bảng tải lại', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Chị Hoa' }));
    await screen.findByRole('dialog');
    customers = customers.filter((c) => c.customerId !== 'k-1');
    type('Tên Khách hàng', 'Chị Hoa mới');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    await waitFor(() => expect(within(table()).queryByText('Chị Hoa')).toBeNull());
    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(callsTo('PUT', `${CUSTOMERS_PATH}/k-1`)).toHaveLength(1);
  });

  it('"Mở Khách hàng đã có" mà tải lỗi: báo trong hộp thoại, vẫn đóng được', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    await openAddDialog();
    type('Số điện thoại', '0912345678');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    const open = await within(dialog()).findByRole('button', { name: 'Mở Khách hàng đã có' });
    // Khách hàng đó không còn khi bấm mở
    customers = customers.filter((c) => c.customerId !== 'k-1');
    fireEvent.click(open);

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    expect(within(dialog()).getByText('Thêm Khách hàng')).toBeTruthy();
    const cancel = within(dialog()).getByRole('button', { name: 'Hủy' }) as HTMLButtonElement;
    await waitFor(() => expect(cancel.disabled).toBe(false));
    fireEvent.click(cancel);
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('đang gửi thêm hoặc sửa thì không đóng được hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Chị Hoa');

    let release = holdWrites();
    await openAddDialog();
    type('Số điện thoại', '0909 000 111');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Thêm' }));
    await waitFor(() =>
      expect((within(dialog()).getByRole('button', { name: 'Hủy' }) as HTMLButtonElement).disabled).toBe(true),
    );
    expect(within(dialog()).queryByRole('button', { name: /close/i })).toBeNull();
    fireEvent.keyDown(dialog(), { key: 'Escape' });
    expect(screen.getByRole('dialog')).toBeTruthy();
    release();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    // Bảng đang lọc theo số vừa thêm: bỏ lọc để thấy lại Chị Hoa
    search('');
    fireEvent.click(await screen.findByRole('button', { name: 'Sửa Chị Hoa' }));
    await screen.findByRole('dialog');
    release = holdWrites();
    type('Ghi chú', 'Ghi chú mới');
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Lưu' }));
    await waitFor(() =>
      expect((within(dialog()).getByRole('button', { name: 'Hủy' }) as HTMLButtonElement).disabled).toBe(true),
    );
    expect(within(dialog()).queryByRole('button', { name: /close/i })).toBeNull();
    release();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(customers.find((c) => c.customerId === 'k-1')?.note).toBe('Ghi chú mới');
  });
});
