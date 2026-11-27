import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp, ConfigProvider } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ProductImportPreview, ProductImportPreviewRow, ProductImportResult } from '../api/products';
import { ProductImportModal } from './ProductImportModal';

vi.setConfig({ testTimeout: 15_000 });

const IMPORT_PATH = '/api/v1/catalog/products/import';
const PREVIEW_PATH = IMPORT_PATH + '/preview';
const TEMPLATE_PATH = '/api/v1/catalog/products/import-template';
const XLSX = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const fetchMock = vi.fn<typeof fetch>();

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

/** Kết quả của một lần nhập: 3 dòng đúng, dòng 4 thiếu giá, dòng 6 trùng mã vạch có sẵn và sai SKU. */
const RESULT: ProductImportResult = {
  imported: 3,
  failed: [
    { row: 4, column: 'Giá bán', message: 'Nhập giá bán' },
    { row: 6, column: 'SKU', message: 'SKU tối đa 50 ký tự' },
    { row: 6, column: 'Mã vạch', message: 'Mã vạch 8935001800019 đã có ở Sản phẩm khác của Cửa hàng' },
  ],
  createdCategories: ['Bánh kẹo'],
};

const previewRow: ProductImportPreviewRow = {
  row: 2, parentCode: null, productName: 'Sản phẩm mẫu', variantName: null, attributes: {},
  sku: 'SP-MAU', barcode: null, categoryName: 'Bánh kẹo', salePrice: 15000, openingQuantity: 0,
  openingUnitCost: null, valid: true,
};

const PREVIEW: ProductImportPreview = {
  totalRows: 6, validProducts: 3, validRows: 4, previewToken: 'preview-token',
  rows: [
    { ...previewRow, parentCode: 'TRA', productName: 'Trà nhài', variantName: 'Chai 350 ml',
      sku: 'TRA-S', attributes: { 'Dung tích': '350 ml' } },
    { ...previewRow, row: 3, parentCode: 'TRA', productName: 'Trà nhài', variantName: 'Chai 500 ml',
      sku: 'TRA-L', attributes: { 'Dung tích': '500 ml' } },
    { ...previewRow, row: 4, sku: null, salePrice: null, valid: false },
    { ...previewRow, row: 5, sku: 'SP-5' },
    { ...previewRow, row: 6, sku: 'SP-6', valid: false },
    { ...previewRow, row: 7, sku: 'SP-7' },
  ],
  errors: RESULT.failed,
};

/** Phản hồi lần lượt của các lần `POST …/import`; hết danh sách thì trả `RESULT`. */
let importResponses: (() => Response)[];
let previewResponses: (() => Response)[];

function backend() {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init?.method ?? 'GET';
    if (url.pathname === TEMPLATE_PATH && method === 'GET') {
      if (url.searchParams.get('format') === 'csv') {
        return new Response('Tên Sản phẩm,Giá bán', { status: 200, headers: { 'Content-Type': 'text/csv' } });
      }
      return new Response('PK-mau', { status: 200, headers: { 'Content-Type': XLSX } });
    }
    if (url.pathname === PREVIEW_PATH && method === 'POST') {
      const next = previewResponses.shift();
      return next ? next() : json(PREVIEW);
    }
    if (url.pathname === IMPORT_PATH && method === 'POST') {
      const next = importResponses.shift();
      return next ? next() : json(RESULT);
    }
    return json({ type: '/errors/common/not-found', title: 'Không tìm thấy', status: 404 }, 404);
  });
}

function renderModal() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  const onClose = vi.fn<() => void>();
  const onImported = vi.fn<(result: ProductImportResult) => void>();
  render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <AntdApp>
          <ProductImportModal onClose={onClose} onImported={onImported} />
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { invalidate, onClose, onImported };
}

function dialog(): HTMLElement {
  return screen.getByRole('dialog');
}

function xlsxFile(name = 'san-pham.xlsx', size = 4096): File {
  return new File([new Uint8Array(size)], name, { type: XLSX });
}

/** Chọn file ở ô chọn file của hộp thoại (antd Upload, ô ẩn). */
function chooseFile(file: File) {
  const input = dialog().querySelector<HTMLInputElement>('input[type="file"]');
  if (!input) throw new Error('không có ô chọn file');
  fireEvent.change(input, { target: { files: [file] } });
}

function previewButton(): HTMLButtonElement {
  return within(dialog()).getByRole('button', { name: 'Xem trước' });
}

async function openPreview(products = 3): Promise<HTMLButtonElement> {
  fireEvent.click(previewButton());
  return within(dialog()).findByRole('button', { name: `Lưu ${products} sản phẩm` });
}

function importCalls() {
  return fetchMock.mock.calls.filter(
    ([input, init]) => init?.method === 'POST' && new URL(String(input), 'http://localhost').pathname === IMPORT_PATH,
  );
}

function previewCalls() {
  return fetchMock.mock.calls.filter(
    ([input, init]) => init?.method === 'POST' && new URL(String(input), 'http://localhost').pathname === PREVIEW_PATH,
  );
}

function keyOf(call: Parameters<typeof fetch>): string | null {
  return new Headers(call[1]?.headers).get('Idempotency-Key');
}

/** Các dòng của bảng lỗi: "dòng | cột | lý do". */
function failedRows(): string[] {
  return within(within(dialog()).getByRole('table'))
    .getAllByRole('row')
    .slice(1)
    .map((row) =>
      within(row)
        .getAllByRole('cell')
        .map((cell) => cell.textContent)
        .join(' | '),
    );
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  importResponses = [];
  previewResponses = [];
  backend();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ProductImportModal', () => {
  it('tải file mẫu về máy với đúng tên file', async () => {
    const created: Blob[] = [];
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn<(blob: Blob) => string>((blob) => {
        created.push(blob);
        return 'blob:mau';
      }),
    });
    const revoke = vi.fn<(url: string) => void>();
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: revoke });
    const clicked: { href: string; download: string }[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.getAttribute('href') ?? '', download: this.download });
    });
    try {
      renderModal();
      fireEvent.click(within(dialog()).getByRole('button', { name: 'Tải mẫu Excel' }));

      await waitFor(() => expect(clicked).toEqual([{ href: 'blob:mau', download: 'mau-nhap-san-pham.xlsx' }]));
      expect(await created[0].text()).toBe('PK-mau');
      await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:mau'));
      const [input, init] = fetchMock.mock.calls[0];
      expect(new URL(String(input), 'http://localhost').pathname).toBe(TEMPLATE_PATH);
      expect(init?.method).toBe('GET');
      expect(previewButton().disabled).toBe(true);

      fireEvent.click(within(dialog()).getByRole('button', { name: 'Tải mẫu CSV' }));
      await waitFor(() => expect(clicked.at(-1)).toEqual({ href: 'blob:mau', download: 'mau-nhap-san-pham.csv' }));
      expect(await created[1].text()).toBe('Tên Sản phẩm,Giá bán');
      expect(new URL(String(fetchMock.mock.calls[1][0]), 'http://localhost').searchParams.get('format')).toBe('csv');
    } finally {
      click.mockRestore();
      Reflect.deleteProperty(URL, 'createObjectURL');
      Reflect.deleteProperty(URL, 'revokeObjectURL');
    }
  });

  it('nhập file: gửi multipart kèm Idempotency-Key, hiện số đã nhập, Danh mục mới, bảng dòng lỗi; tải lại các bảng', async () => {
    const { invalidate, onImported } = renderModal();
    const file = new File(['Tên Sản phẩm,Giá bán\nTrà nhài,15000'], 'san-pham.csv', { type: 'text/csv' });
    chooseFile(file);
    expect(await within(dialog()).findByText('san-pham.csv')).toBeTruthy();
    const save = await openPreview();
    expect(within(dialog()).getByText('Mã cha TRA · 2 biến thể')).toBeTruthy();
    expect(within(dialog()).getByText('Chai 350 ml')).toBeTruthy();
    expect(within(dialog()).getByText('Chai 500 ml')).toBeTruthy();
    expect(importCalls()).toHaveLength(0);
    expect(onImported).not.toHaveBeenCalled();
    fireEvent.click(within(dialog()).getByRole('tab', { name: 'Dòng có lỗi (2)' }));
    expect(within(dialog()).getByText('Nhập giá bán')).toBeTruthy();
    fireEvent.click(save);

    expect(await within(dialog()).findByText('Đã nhập 3 Sản phẩm. 2 dòng lỗi chưa được nhập.')).toBeTruthy();
    expect(within(dialog()).getByText('Danh mục mới: Bánh kẹo.')).toBeTruthy();
    expect(failedRows()).toEqual([
      '4 | Giá bán | Nhập giá bán',
      '6 | SKU | SKU tối đa 50 ký tự',
      '6 | Mã vạch | Mã vạch 8935001800019 đã có ở Sản phẩm khác của Cửa hàng',
    ]);
    expect(onImported).toHaveBeenCalledWith(RESULT);

    const calls = importCalls();
    expect(calls).toHaveLength(1);
    expect(keyOf(calls[0])).toMatch(UUID);
    expect(new Headers(calls[0][1]?.headers).get('X-Import-Preview')).toBe(PREVIEW.previewToken);
    const body = calls[0][1]?.body;
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(file);
    await waitFor(() => {
      const keys = invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey));
      expect(keys).toEqual(
        expect.arrayContaining([
          '["catalog","products"]',
          '["catalog","categories"]',
          '["inventory","stock"]',
          '["inventory","ledger"]',
        ]),
      );
    });

    expect(within(dialog()).queryByRole('button', { name: /^Lưu/ })).toBeNull();
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Nhập file khác' }));
    expect(previewButton().disabled).toBe(true);
  });

  it('mọi dòng đều đúng: chỉ báo số Sản phẩm đã nhập, không có bảng lỗi', async () => {
    importResponses = [() => json({ imported: 2, failed: [], createdCategories: [] })];
    renderModal();
    chooseFile(xlsxFile());
    await within(dialog()).findByText('san-pham.xlsx');
    fireEvent.click(await openPreview());
    expect(await within(dialog()).findByText('Đã nhập 2 Sản phẩm.')).toBeTruthy();
    expect(within(dialog()).queryByRole('table')).toBeNull();
  });

  it('file ngoài CSV/Excel hoặc lớn hơn 2 MB bị từ chối ngay', async () => {
    renderModal();
    chooseFile(new File(['Tên,Giá'], 'san-pham.txt', { type: 'text/plain' }));
    expect(await within(dialog()).findByText(/Chọn file Excel/)).toBeTruthy();
    expect(previewButton().disabled).toBe(true);

    chooseFile(xlsxFile('lon.xlsx', 2 * 1024 * 1024 + 1));
    expect(await within(dialog()).findByText(/File tối đa 2 MB/)).toBeTruthy();
    expect(previewButton().disabled).toBe(true);

    // Chọn lại file đúng thì hết lỗi
    chooseFile(xlsxFile());
    await within(dialog()).findByText('san-pham.xlsx');
    expect(within(dialog()).queryByText(/File tối đa 2 MB/)).toBeNull();
    expect(previewButton().disabled).toBe(false);
    expect(importCalls()).toHaveLength(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('xem trước file sai mẫu hiển thị lý do và cho chọn file khác', async () => {
    previewResponses = [
      () =>
        json(
          {
            type: '/errors/catalog/import-invalid-file',
            title: 'File nhập không hợp lệ',
            status: 400,
            detail: 'File thiếu cột bắt buộc: Giá bán. Dòng 1 phải là dòng tiêu đề như file mẫu (dữ liệu từ dòng 2).',
            requestId: 'req-400',
          },
          400,
        ),
    ];
    renderModal();
    chooseFile(xlsxFile());
    await within(dialog()).findByText('san-pham.xlsx');
    fireEvent.click(previewButton());

    expect(await within(dialog()).findByText('File nhập không đọc được hoặc không đúng mẫu.')).toBeTruthy();
    expect(within(dialog()).getByText(/File thiếu cột bắt buộc: Giá bán/)).toBeTruthy();
    expect(importCalls()).toHaveLength(0);

    chooseFile(xlsxFile('da-sua.xlsx'));
    await within(dialog()).findByText('da-sua.xlsx');
    expect(within(dialog()).queryByText(/File thiếu cột bắt buộc/)).toBeNull();
    fireEvent.click(await openPreview());
    await within(dialog()).findByText(/Đã nhập 3 Sản phẩm/);
    expect(importCalls()).toHaveLength(1);
  });

  it('giữ file và cùng khóa yêu cầu khi xem trước bị gián đoạn, đồng thời chỉ dẫn thử lại ngay tại file', async () => {
    previewResponses = [
      () => {
        throw new TypeError('Failed to fetch');
      },
      () => json(PREVIEW),
    ];
    renderModal();
    chooseFile(xlsxFile('can-kiem-tra.xlsx'));
    await within(dialog()).findByText('can-kiem-tra.xlsx');
    fireEvent.click(previewButton());

    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(within(dialog()).getByText(/File đã chọn vẫn được giữ.*Xem trước.*thử lại cùng yêu cầu/)).toBeTruthy();
    expect(within(dialog()).getByText('can-kiem-tra.xlsx')).toBeTruthy();
    const first = previewCalls();
    expect(first).toHaveLength(1);
    expect(keyOf(first[0])).toMatch(UUID);

    fireEvent.click(previewButton());
    await within(dialog()).findByRole('button', { name: 'Lưu 3 sản phẩm' });
    const calls = previewCalls();
    expect(calls).toHaveLength(2);
    expect(keyOf(calls[1])).toBe(keyOf(calls[0]));
  });

  it('trùng mã do người khác vừa tạo: báo dòng nào, không dòng nào được nhập', async () => {
    importResponses = [
      () =>
        json(
          {
            type: '/errors/catalog/barcode-taken',
            title: 'Mã vạch đã có',
            status: 409,
            detail: 'Dòng 3: Cửa hàng đã có Sản phẩm dùng mã vạch này. Không dòng nào được nhập; hãy sửa file rồi nhập lại.',
            requestId: 'req-409',
          },
          409,
        ),
    ];
    renderModal();
    chooseFile(xlsxFile());
    await within(dialog()).findByText('san-pham.xlsx');
    fireEvent.click(await openPreview());
    expect(await within(dialog()).findByText(/^Dòng 3: /)).toBeTruthy();
    expect(within(dialog()).getByText('Cửa hàng đã có Sản phẩm dùng mã vạch này.')).toBeTruthy();
  });

  it('mất mạng: gửi lại cùng file dùng lại khóa (không nhập hai lần); đang gửi thì không đóng được', async () => {
    let finish: (response: Response) => void = () => {};
    importResponses = [
      () => {
        throw new TypeError('Failed to fetch');
      },
    ];
    const { onClose } = renderModal();
    const file = xlsxFile();
    chooseFile(file);
    await within(dialog()).findByText('san-pham.xlsx');
    const save = await openPreview();
    fireEvent.click(save);
    expect(await within(dialog()).findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(within(dialog()).getByText(/gửi lại cùng yêu cầu/)).toBeTruthy();

    // Lần gửi lại treo tới khi `finish`: trong lúc đó không đóng được hộp thoại
    fetchMock.mockImplementationOnce(
      () =>
        new Promise<Response>((resolve) => {
          finish = resolve;
        }),
    );
    fireEvent.click(save);
    await waitFor(() => expect(importCalls()).toHaveLength(2));
    expect(within(dialog()).getByRole('button', { name: 'Quay lại' }).hasAttribute('disabled')).toBe(true);
    fireEvent.keyDown(dialog(), { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
    finish(json(RESULT, 200, { 'Idempotent-Replay': 'true' }));
    expect(await within(dialog()).findByText(/Đã nhập 3 Sản phẩm/)).toBeTruthy();

    const [first, second] = importCalls();
    expect(keyOf(second)).toBe(keyOf(first));
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Đóng' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('bản xem trước cũ phải được kiểm tra lại trước khi lưu', async () => {
    previewResponses = [() => json(PREVIEW), () => json({ ...PREVIEW, previewToken: 'new-preview-token' })];
    importResponses = [() => json({
      type: '/errors/catalog/import-preview-stale', status: 409, detail: 'SKU đã thay đổi.',
    }, 409)];
    renderModal();
    chooseFile(xlsxFile());
    await within(dialog()).findByText('san-pham.xlsx');
    fireEvent.click(await openPreview());
    expect(await within(dialog()).findByText('Dữ liệu đã thay đổi từ lúc xem trước.')).toBeTruthy();
    expect(within(dialog()).queryByRole('button', { name: /^Lưu/ })).toBeNull();
    fireEvent.click(within(dialog()).getByRole('button', { name: 'Xem trước lại' }));
    const save = await within(dialog()).findByRole('button', { name: 'Lưu 3 sản phẩm' });
    expect(importCalls()).toHaveLength(1);
    fireEvent.click(save);
    await within(dialog()).findByText(/Đã nhập 3 Sản phẩm/);
    const [first, second] = importCalls();
    expect(keyOf(second)).not.toBe(keyOf(first));
    expect(new Headers(second[1]?.headers).get('X-Import-Preview')).toBe('new-preview-token');
  });
});
