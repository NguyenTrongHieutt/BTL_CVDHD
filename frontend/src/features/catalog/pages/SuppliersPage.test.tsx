import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Supplier, SupplierRequest } from '../../../shared/types/catalog';
import { foldText } from '../components/textUtils';
import { SuppliersPage } from './SuppliersPage';

const SUPPLIERS_PATH = '/api/v1/catalog/suppliers';
const CODE_INVALID =
  'Mã Nhà cung cấp chỉ gồm chữ cái không dấu, chữ số, dấu - và dấu _, bắt đầu bằng chữ cái hoặc chữ số';
const PHONE_CHARS_INVALID = 'Số điện thoại chỉ gồm chữ số, khoảng trắng và các dấu + . - ( )';
const CODE_TAKEN_MESSAGE = 'Cửa hàng đã có Nhà cung cấp dùng mã này.';

type Field = 'Tên Nhà cung cấp' | 'Mã Nhà cung cấp' | 'Số điện thoại' | 'Địa chỉ' | 'Ghi chú';

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function supplier(
  id: string,
  name: string,
  code: string | null = null,
  phone: string | null = null,
  address: string | null = null,
  note: string | null = null,
): Supplier {
  return { supplierId: id, name, code, phone, address, note };
}

let suppliers: Supplier[];
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

/** Như backend: tên không dấu, rồi mã (chưa có mã xếp sau). */
function byNameThenCode(a: Supplier, b: Supplier): number {
  const [x, y] = [foldText(a.name), foldText(b.name)];
  if (x !== y) return x < y ? -1 : 1;
  if (a.code === b.code) return 0;
  if (a.code === null) return 1;
  if (b.code === null) return -1;
  return a.code < b.code ? -1 : 1;
}

/** Như backend: tên chứa `q` (không dấu), mã bắt đầu bằng `q`, hoặc số điện thoại chứa các chữ số của `q`. */
function matches(s: Supplier, q: string): boolean {
  const digits = /\p{L}/u.test(q) ? '' : q.replace(/\D/g, '');
  return (
    foldText(s.name).includes(foldText(q)) ||
    (s.code !== null && s.code.startsWith(q.toUpperCase())) ||
    (digits !== '' && s.phone !== null && s.phone.replace(/\D/g, '').includes(digits))
  );
}

const codeTaken = () =>
  json({ type: '/errors/catalog/supplier-code-taken', title: 'Mã Nhà cung cấp đã có', status: 409, requestId: 'r' }, 409);

/**
 * Backend giả giữ Nhà cung cấp trong bộ nhớ (theo thứ tự thêm): danh sách có tìm kiếm, sắp (mặc định theo tên;
 * `sort=createdAt,desc` thì mới thêm trước), phân trang; POST, PUT trả
 * `409 supplier-code-taken` khi trùng mã. Tên `!bad` bị từ chối (`400` trên `name`) để thấy lỗi theo trường của
 * backend.
 */
function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (method !== 'GET' && writeGate) await writeGate;
    const body = init?.body ? (JSON.parse(String(init.body)) as SupplierRequest) : undefined;
    const badName =
      body?.name === '!bad'
        ? json(
            {
              type: '/errors/common/validation',
              title: 'Dữ liệu không hợp lệ',
              status: 400,
              requestId: 'req-400',
              errors: [{ field: 'name', message: 'Tên Nhà cung cấp có ký tự không hợp lệ' }],
            },
            400,
          )
        : null;
    const fromBody = (id: string, b: SupplierRequest) =>
      supplier(id, b.name, b.code ?? null, b.phone ?? null, b.address ?? null, b.note ?? null);

    if (url.pathname === SUPPLIERS_PATH && method === 'GET') {
      const q = url.searchParams.get('q');
      const found = suppliers.filter((s) => q === null || matches(s, q));
      const matching =
        url.searchParams.get('sort') === 'createdAt,desc' ? found.reverse() : found.sort(byNameThenCode);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return json({ items: matching.slice(page * size, (page + 1) * size), total: matching.length, page, size });
    }
    if (url.pathname === SUPPLIERS_PATH && method === 'POST' && body) {
      if (badName) return badName;
      if (body.code && suppliers.some((s) => s.code === body.code)) return codeTaken();
      const created = fromBody(`n-${nextId++}`, body);
      suppliers = [...suppliers, created];
      return json(created, 201);
    }
    const id = url.pathname.startsWith(`${SUPPLIERS_PATH}/`) ? url.pathname.slice(SUPPLIERS_PATH.length + 1) : null;
    const found = suppliers.find((s) => s.supplierId === id);
    if (found && method === 'PUT' && body) {
      if (badName) return badName;
      if (body.code && suppliers.some((s) => s.code === body.code && s.supplierId !== found.supplierId)) {
        return codeTaken();
      }
      const updated = fromBody(found.supplierId, body);
      suppliers = suppliers.map((s) => (s.supplierId === found.supplierId ? updated : s));
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
        <SuppliersPage />
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
  const call = callsTo('GET', SUPPLIERS_PATH).at(-1);
  return Object.fromEntries(new URL(String(call?.[0]), 'http://localhost').searchParams);
}

function table(): HTMLElement {
  return screen.getByRole('table');
}

/** Mã, tên, số điện thoại của các dòng trong bảng. */
function rows(): string[] {
  return within(table())
    .getAllByRole('row')
    .slice(1)
    .map((row) => {
      const cells = within(row).getAllByRole('cell');
      return [0, 1, 2].map((i) => cells[i]?.textContent ?? '').join(' · ');
    });
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function field(label: Field): HTMLInputElement {
  return within(dialog()).getByLabelText(label) as HTMLInputElement;
}

function type(label: Field, value: string) {
  fireEvent.change(field(label), { target: { value } });
}

function submit(name: 'Thêm' | 'Lưu') {
  fireEvent.click(within(dialog()).getByRole('button', { name }));
}

function searchBox(): HTMLInputElement {
  return screen.getByLabelText('Tìm Nhà cung cấp') as HTMLInputElement;
}

function search(value: string) {
  fireEvent.change(searchBox(), { target: { value } });
  fireEvent.keyDown(searchBox(), { key: 'Enter', code: 'Enter', keyCode: 13 });
}

async function openAddDialog() {
  fireEvent.click(screen.getByRole('button', { name: /Thêm Nhà cung cấp/ }));
  await screen.findByRole('dialog');
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  suppliers = [
    supplier('n-1', 'Đại lý Sữa Hồng Hà', 'NCC-A02', '0283 711 0002', '88 Lý Thường Kiệt', 'Giao buổi sáng'),
    supplier('n-2', 'Chợ đầu mối Bình Điền'),
    supplier('n-3', 'Công ty Phú An', 'NCC-A01', '028 3711 0001'),
  ];
  nextId = 4;
  writeGate = undefined;
  backend();
});

describe('Nhà cung cấp (FR-23, KHO-02)', () => {
  it('hiện bảng Nhà cung cấp sắp theo tên, trường trống hiện "—"', async () => {
    renderPage();
    expect(await within(table()).findByText('Công ty Phú An')).toBeTruthy();
    expect(rows()).toEqual([
      '— · Chợ đầu mối Bình Điền · —',
      'NCC-A01 · Công ty Phú An · 028 3711 0001',
      'NCC-A02 · Đại lý Sữa Hồng Hà · 0283 711 0002',
    ]);
    expect(within(table()).getByText('88 Lý Thường Kiệt')).toBeTruthy();
    expect(within(table()).getByText('Giao buổi sáng')).toBeTruthy();
    expect(lastListQuery()).toEqual({ page: '0', size: '20' });
  });

  it('Chủ cửa hàng thêm Nhà cung cấp: mã gửi chữ HOA, Nhà cung cấp hiện trong bảng ngay', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    // Đang tìm "VNM" và chưa có ai khớp. Chờ debounce chạy xong (không bấm Enter), để không còn lượt tìm nào đang
    // chờ phát lại "VNM" sau khi thêm.
    fireEvent.change(searchBox(), { target: { value: 'VNM' } });
    await waitFor(() => expect(lastListQuery().q).toBe('VNM'));
    expect(await screen.findByText('Không có Nhà cung cấp nào khớp "VNM".')).toBeTruthy();

    await openAddDialog();
    type('Tên Nhà cung cấp', '  Công ty Vinamilk ');
    type('Mã Nhà cung cấp', ' vnm ');
    type('Số điện thoại', '028 1234 5678');
    submit('Thêm');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(rows()[0]).toBe('VNM · Công ty Vinamilk · 028 1234 5678'));
    const [post] = callsTo('POST', SUPPLIERS_PATH);
    expect(bodyOf(post)).toEqual({ name: 'Công ty Vinamilk', code: 'VNM', phone: '028 1234 5678' });
    expect(new Headers(post[1]?.headers).get('Idempotency-Key')).toBeNull();
    // Như màn Sản phẩm: bỏ tìm kiếm, mới thêm trước, trang đầu
    expect(searchBox().value).toBe('');
    expect(lastListQuery()).toEqual({ page: '0', size: '20', sort: 'createdAt,desc' });
    expect(rows()).toHaveLength(4);
    expect(await screen.findByText('Đã thêm Nhà cung cấp "Công ty Vinamilk"')).toBeTruthy();
  });

  it('mã ngắn khớp hơn 20 Nhà cung cấp: Nhà cung cấp vừa thêm vẫn nằm đầu trang đầu', async () => {
    // 25 Nhà cung cấp có tên chứa "TH" và mã bắt đầu bằng "TH": tìm "TH" theo tên thì Nhà cung cấp mới ở trang 2
    suppliers = Array.from({ length: 25 }, (_, i) => {
      const n = String(i).padStart(2, '0');
      return supplier(`n-${100 + i}`, `Công ty TH Food ${n}`, `TH-${n}`);
    });
    renderPage();
    await within(table()).findByText('Công ty TH Food 00');

    await openAddDialog();
    type('Tên Nhà cung cấp', 'Công ty TH True Milk');
    type('Mã Nhà cung cấp', 'th');
    submit('Thêm');

    await waitFor(() => expect(rows()[0]).toBe('TH · Công ty TH True Milk · —'));
    expect(lastListQuery()).toEqual({ page: '0', size: '20', sort: 'createdAt,desc' });
    expect(rows()).toHaveLength(20);

    // Tìm kiếm lại thì về thứ tự theo tên
    search('th');
    await waitFor(() => expect(lastListQuery()).toEqual({ page: '0', size: '20', q: 'th' }));
    await waitFor(() => expect(rows()[0]).toBe('TH-00 · Công ty TH Food 00 · —'));
  });

  it('thêm chỉ với tên: không gửi trường trống, Nhà cung cấp mới nằm đầu bảng', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    await openAddDialog();
    type('Tên Nhà cung cấp', 'Chợ Bến Thành');
    type('Ghi chú', 'Giao thứ Hai\r\nvà thứ Năm ');
    submit('Thêm');

    await waitFor(() => expect(rows()[0]).toBe('— · Chợ Bến Thành · —'));
    expect(bodyOf(callsTo('POST', SUPPLIERS_PATH)[0])).toEqual({
      name: 'Chợ Bến Thành',
      note: 'Giao thứ Hai\nvà thứ Năm',
    });
  });

  it('kiểm tra phía client: thiếu tên, mã có ký tự lạ, quá dài thì không gửi', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    await openAddDialog();
    submit('Thêm');
    expect(await within(dialog()).findByText('Nhập tên Nhà cung cấp')).toBeTruthy();

    type('Tên Nhà cung cấp', 'Công ty Vinamilk');
    for (const code of ['VN M!', 'MÃ01', 'NCC.01', '-', '_01', '---']) {
      type('Mã Nhà cung cấp', code);
      submit('Thêm');
      expect(await within(dialog()).findByText(CODE_INVALID)).toBeTruthy();
    }

    type('Mã Nhà cung cấp', 'C'.repeat(31));
    type('Số điện thoại', '9'.repeat(21));
    submit('Thêm');
    expect(await within(dialog()).findByText('Mã Nhà cung cấp tối đa 30 ký tự')).toBeTruthy();
    expect(await within(dialog()).findByText('Số điện thoại tối đa 20 ký tự')).toBeTruthy();

    expect(callsTo('POST', SUPPLIERS_PATH)).toHaveLength(0);
  });

  it('kiểm tra số điện thoại phía client như backend: chỉ chữ số, khoảng trắng, + . - ( ), phải có chữ số', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    await openAddDialog();
    type('Tên Nhà cung cấp', 'Công ty Vinamilk');
    for (const phone of ['028 1234 abc', 'Liên hệ chị Hoa', '028 1234 😀', '028/1234']) {
      type('Số điện thoại', phone);
      submit('Thêm');
      expect(await within(dialog()).findByText(PHONE_CHARS_INVALID)).toBeTruthy();
    }
    type('Số điện thoại', '+ - . ( )');
    submit('Thêm');
    expect(await within(dialog()).findByText('Số điện thoại phải có chữ số')).toBeTruthy();
    expect(callsTo('POST', SUPPLIERS_PATH)).toHaveLength(0);

    type('Số điện thoại', '(028) 1234-5678');
    submit('Thêm');
    await waitFor(() => expect(callsTo('POST', SUPPLIERS_PATH)).toHaveLength(1));
    expect(bodyOf(callsTo('POST', SUPPLIERS_PATH)[0])).toEqual({ name: 'Công ty Vinamilk', phone: '(028) 1234-5678' });
  });

  it('trùng mã: báo dưới ô mã, giữ hộp thoại, không thêm, bảng tải lại', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');
    const listCallsBefore = callsTo('GET', SUPPLIERS_PATH).length;

    await openAddDialog();
    type('Tên Nhà cung cấp', 'Nhà cung cấp khác');
    type('Mã Nhà cung cấp', 'ncc-a01');
    submit('Thêm');

    expect(await within(dialog()).findByText(CODE_TAKEN_MESSAGE)).toBeTruthy();
    expect(field('Mã Nhà cung cấp').getAttribute('aria-invalid')).toBe('true');
    expect(bodyOf(callsTo('POST', SUPPLIERS_PATH)[0])).toEqual({ name: 'Nhà cung cấp khác', code: 'NCC-A01' });
    expect(suppliers).toHaveLength(3);
    expect(screen.getByRole('dialog')).toBeTruthy();
    // Khóa của bảng không đổi: chỉ invalidate sau lỗi mới làm bảng gọi lại danh sách
    await waitFor(() => expect(callsTo('GET', SUPPLIERS_PATH).length).toBeGreaterThan(listCallsBefore));
    expect(lastListQuery()).toEqual({ page: '0', size: '20' });
  });

  it('sửa: hộp thoại có sẵn dữ liệu, PUT thay toàn bộ, bảng đổi ngay', async () => {
    renderPage();
    await within(table()).findByText('Đại lý Sữa Hồng Hà');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Đại lý Sữa Hồng Hà' }));
    await screen.findByRole('dialog');
    expect(within(dialog()).getByText('Sửa Nhà cung cấp')).toBeTruthy();
    expect(field('Mã Nhà cung cấp').value).toBe('NCC-A02');
    expect(field('Số điện thoại').value).toBe('0283 711 0002');
    expect(field('Địa chỉ').value).toBe('88 Lý Thường Kiệt');
    type('Tên Nhà cung cấp', 'Đại lý Sữa Hồng Hà (chi nhánh 2)');
    type('Địa chỉ', '');
    submit('Lưu');

    expect(await within(table()).findByText('Đại lý Sữa Hồng Hà (chi nhánh 2)')).toBeTruthy();
    const [put] = callsTo('PUT', `${SUPPLIERS_PATH}/n-1`);
    // Địa chỉ để trống thì không gửi (backend xóa giá trị cũ)
    expect(bodyOf(put)).toEqual({
      name: 'Đại lý Sữa Hồng Hà (chi nhánh 2)',
      code: 'NCC-A02',
      phone: '0283 711 0002',
      note: 'Giao buổi sáng',
    });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('sửa bỏ mã: PUT không có code, dòng trong bảng không còn mã', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Công ty Phú An' }));
    await screen.findByRole('dialog');
    type('Mã Nhà cung cấp', '  ');
    submit('Lưu');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    const [put] = callsTo('PUT', `${SUPPLIERS_PATH}/n-3`);
    expect(bodyOf(put)).toEqual({ name: 'Công ty Phú An', phone: '028 3711 0001' });
    await waitFor(() =>
      expect(rows()).toEqual([
        '— · Chợ đầu mối Bình Điền · —',
        '— · Công ty Phú An · 028 3711 0001',
        'NCC-A02 · Đại lý Sữa Hồng Hà · 0283 711 0002',
      ]),
    );
  });

  it('sửa mà không đổi gì (kể cả gõ mã chữ thường): không gửi PUT, đóng hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Công ty Phú An' }));
    await screen.findByRole('dialog');
    type('Tên Nhà cung cấp', ' Công ty Phú An ');
    type('Mã Nhà cung cấp', 'ncc-a01');
    submit('Lưu');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(callsTo('PUT', `${SUPPLIERS_PATH}/n-3`)).toHaveLength(0);
  });

  it('sửa sang mã Nhà cung cấp khác đang dùng: báo dưới ô mã, không đổi', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Công ty Phú An' }));
    await screen.findByRole('dialog');
    type('Mã Nhà cung cấp', 'NCC-A02');
    submit('Lưu');

    expect(await within(dialog()).findByText(CODE_TAKEN_MESSAGE)).toBeTruthy();
    expect(suppliers.find((s) => s.supplierId === 'n-3')?.code).toBe('NCC-A01');
  });

  it('lỗi theo trường của backend hiện dưới ô tương ứng', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    await openAddDialog();
    type('Tên Nhà cung cấp', '!bad');
    submit('Thêm');

    expect(await within(dialog()).findByText('Tên Nhà cung cấp có ký tự không hợp lệ')).toBeTruthy();
    expect(field('Tên Nhà cung cấp').getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('tìm theo tên không dấu, mã, số điện thoại; không khớp thì xóa tìm kiếm được', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    search('sua hong ha');
    await waitFor(() => expect(rows()).toEqual(['NCC-A02 · Đại lý Sữa Hồng Hà · 0283 711 0002']));
    expect(lastListQuery()).toEqual({ page: '0', size: '20', q: 'sua hong ha' });

    search('ncc-a01');
    await waitFor(() => expect(rows()).toEqual(['NCC-A01 · Công ty Phú An · 028 3711 0001']));

    search('711 0002');
    await waitFor(() => expect(lastListQuery().q).toBe('711 0002'));
    await waitFor(() => expect(rows()).toEqual(['NCC-A02 · Đại lý Sữa Hồng Hà · 0283 711 0002']));

    search('không có ai');
    expect(await screen.findByText('Không có Nhà cung cấp nào khớp "không có ai".')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Xóa tìm kiếm' }));
    await waitFor(() => expect(rows()).toHaveLength(3));
    expect(searchBox().value).toBe('');
  });

  it('sửa Nhà cung cấp không còn (404): báo trong hộp thoại, giữ hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    fireEvent.click(screen.getByRole('button', { name: 'Sửa Công ty Phú An' }));
    await screen.findByRole('dialog');
    suppliers = suppliers.filter((s) => s.supplierId !== 'n-3');
    type('Tên Nhà cung cấp', 'Công ty Phú An mới');
    submit('Lưu');

    expect(await within(dialog()).findByText('Không tìm thấy dữ liệu.')).toBeTruthy();
    await waitFor(() => expect(within(table()).queryByText('Công ty Phú An')).toBeNull());
    expect(screen.getByRole('dialog')).toBeTruthy();
  });

  it('đang gửi thì không đóng được hộp thoại', async () => {
    renderPage();
    await within(table()).findByText('Công ty Phú An');

    const release = holdWrites();
    await openAddDialog();
    type('Tên Nhà cung cấp', 'Công ty Vinamilk');
    submit('Thêm');
    await waitFor(() =>
      expect((within(dialog()).getByRole('button', { name: 'Hủy' }) as HTMLButtonElement).disabled).toBe(true),
    );
    expect(within(dialog()).queryByRole('button', { name: /close/i })).toBeNull();
    fireEvent.keyDown(dialog(), { key: 'Escape' });
    expect(screen.getByRole('dialog')).toBeTruthy();
    release();
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(suppliers.map((s) => s.name)).toContain('Công ty Vinamilk');
  });
});
