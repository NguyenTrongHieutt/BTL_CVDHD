import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RECEIPTS_PATH } from '../receiptImportApi';
import type { Receipt, ReceiptImportPreview } from '../receiptImportApi';
import { ReceiptImportModal } from './ReceiptImportModal';

const fetchMock = vi.fn<typeof fetch>();
const supplierId = '01234567-1234-4000-8000-0123456789ab';
const receipt = { receiptId: 'receipt', code: 'PN00001', receivedAt: '2026-11-26T07:00:00Z', totalAmount: 71_000 };
const preview: ReceiptImportPreview = {
  previewToken: 'checked', existingRows: 1, newProducts: 0, newVariants: 1, totalAmount: 71_000,
  rows: [
    {
      row: 2, sku: 'TRA-350', productName: 'Trà nhài', parentCode: 'TRA', variantName: 'Chai 350 ml',
      quantity: 2, unitCost: 11_000, valid: true, action: 'EXISTING',
      values: { sku: 'TRA-350', quantity: '2', unitCost: '11000', parentCode: 'TRA', variantName: 'Chai 350 ml' },
    },
    {
      row: 3, sku: 'TRA-500', productName: 'Trà nhài', parentCode: 'TRA', variantName: 'Chai 500 ml',
      quantity: 3, unitCost: 16_333, valid: true, action: 'NEW_VARIANT',
      values: { sku: 'TRA-500', quantity: '3', unitCost: '16333', parentCode: 'TRA', variantName: 'Chai 500 ml' },
    },
  ],
  errors: [],
};
const invalid: ReceiptImportPreview = {
  ...preview, previewToken: 'before-edit', totalAmount: null, newVariants: 0,
  rows: [preview.rows[0], { ...preview.rows[1], unitCost: null, values: { ...preview.rows[1].values, unitCost: 'sai' },
    valid: false }],
  errors: [{ row: 3, column: 'Giá nhập', message: 'Giá nhập phải là số nguyên dương' }],
};

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
}

type ReceiptTestOptions = {
  previews?: ReceiptImportPreview[];
  save?: () => Response;
};

function mockBackend({ previews = [preview], save = () => json(receipt, 201) }: ReceiptTestOptions = {}) {
  fetchMock.mockImplementation(async (input, options) => {
    const { pathname } = new URL(String(input), 'http://localhost');
    if (pathname === `${RECEIPTS_PATH}/import/preview` && options?.method === 'POST') {
      return json(previews.shift() ?? preview);
    }
    if (pathname === `${RECEIPTS_PATH}/import` && options?.method === 'POST') return save();
    if (pathname === '/api/v1/catalog/suppliers') return json({ items: [], page: 0, size: 30, total: 0 });
    return json({ status: 404 }, 404);
  });
}

function requests(path: string) {
  return fetchMock.mock.calls.filter(([input, init]) =>
    init?.method === 'POST' && new URL(String(input), 'http://localhost').pathname === `${RECEIPTS_PATH}/import${path}`);
}

function renderModal(hasSupplier = true) {
  const onImported = vi.fn<(result: Receipt) => void>();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ConfigProvider theme={{ token: { motion: false } }}>
        <ReceiptImportModal initialSupplier={hasSupplier ? { value: supplierId, label: 'Nhà cung cấp demo' } : undefined}
          initialNote="Giao buổi sáng" hasManualLines={false} onImported={onImported} onClose={vi.fn<() => void>()} />
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { onImported };
}

function modal() {
  return within(screen.getByRole('dialog'));
}

async function review(filename = 'hang-ve.csv') {
  const input = screen.getByRole('dialog').querySelector('input[type=file]');
  if (!input) throw new Error('Thiếu ô chọn file');
  fireEvent.change(input, { target: { files: [new File(['mẫu'], filename)] } });
  await modal().findByText(filename);
  fireEvent.click(modal().getByRole('button', { name: 'Xem trước' }));
  return modal().findByRole<HTMLButtonElement>('button', { name: 'Xác nhận nhập kho' });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => vi.unstubAllGlobals());

describe('ReceiptImportModal', () => {
  it('tìm và chọn nhà cung cấp trong cùng một ô rồi xem trước', async () => {
    fetchMock.mockImplementation(async (input, init) => {
      const { pathname, searchParams } = new URL(String(input), 'http://localhost');
      if (pathname === '/api/v1/catalog/suppliers') {
        const items = searchParams.get('q') === 'tra sen'
          ? [{ supplierId, name: 'Trà sen', code: 'SEN-01' }] : [];
        return json({ items, page: 0, size: 30, total: items.length });
      }
      if (pathname.endsWith('/import/preview') && init?.method === 'POST') return json(preview);
      return json({ status: 404 }, 404);
    });
    renderModal(false);
    expect(modal().queryByRole('textbox', { name: 'Tìm nhà cung cấp' })).toBeNull();
    const supplier = modal().getByRole('combobox', { name: 'Nhà cung cấp' });
    fireEvent.mouseDown(supplier);
    fireEvent.change(supplier, { target: { value: 'tra sen' } });
    fireEvent.click(await screen.findByTitle('Trà sen · SEN-01'));
    await review();
    const form = requests('/preview')[0][1]?.body;
    expect(form instanceof FormData ? form.get('supplierId') : undefined).toBe(supplierId);
  });

  it('sửa lỗi qua bước kiểm tra lại, gom SKU cũ và mới rồi lưu đúng một phiếu', async () => {
    mockBackend({ previews: [invalid, preview] });
    const { onImported } = renderModal();
    expect((await review()).disabled).toBe(true);
    expect(requests('')).toHaveLength(0);
    const search = modal().getByRole('textbox', { name: 'Tìm dòng trong file' });
    fireEvent.change(search, { target: { value: 'khong ton tai' } });
    expect(modal().getByText('Không có dòng lỗi khớp từ khóa.')).toBeTruthy();
    expect(modal().getByRole<HTMLButtonElement>('button', { name: 'Xác nhận nhập kho' }).disabled).toBe(true);
    fireEvent.change(search, { target: { value: 'dong 3' } });
    fireEvent.click(modal().getByRole('button', { name: 'Sửa dòng 3' }));
    const cost = modal().getByRole('textbox', { name: 'Giá nhập' });
    expect(cost.getAttribute('aria-invalid')).toBe('true');
    fireEvent.change(cost, { target: { value: '16333' } });
    fireEvent.click(modal().getByRole('button', { name: 'Áp dụng và kiểm tra' }));
    const restored = await modal().findByRole<HTMLInputElement>('textbox', { name: 'Tìm dòng trong file' });
    expect(restored.value).toBe('dong 3');
    fireEvent.change(restored, { target: { value: '' } });
    await modal().findByText('Mã cha TRA · 2 SKU');
    expect(modal().getByText('Thêm biến thể')).toBeTruthy();
    const save = await modal().findByRole<HTMLButtonElement>('button', { name: 'Xác nhận nhập kho' });
    expect(save.disabled).toBe(false);
    expect(requests('')).toHaveLength(0);
    const recheck = requests('/preview')[1][1]?.body as FormData;
    expect(recheck.get('edits')).toBe(JSON.stringify([{ row: 3, values: { unitCost: '16333' } }]));
    fireEvent.click(save);
    await waitFor(() => expect(onImported).toHaveBeenCalledWith(receipt));
    expect(requests('')).toHaveLength(1);
    const commit = requests('')[0][1];
    expect(new Headers(commit?.headers).get('X-Import-Preview')).toBe('checked');
    const form = commit?.body;
    if (!(form instanceof FormData)) throw new Error('Thiếu multipart của Phiếu nhập');
    expect(form.get('supplierId')).toBe(supplierId);
    expect(form.get('note')).toBe('Giao buổi sáng');
    expect(form.get('edits')).toBe(recheck.get('edits'));
  });

  it('gửi lại nguyên yêu cầu khi mất kết nối sau lúc xác nhận Excel', async () => {
    let attempts = 0;
    mockBackend({ save: () => {
      if (attempts++ === 0) throw new TypeError('mất kết nối');
      return json(receipt, 200);
    } });
    const { onImported } = renderModal();
    fireEvent.click(await review('hang-ve.xlsx'));
    fireEvent.click(await modal().findByRole('button', { name: 'Gửi lại phiếu' }));
    await waitFor(() => expect(onImported).toHaveBeenCalledWith(receipt));
    const calls = requests('').map(([, init]) => new Headers(init?.headers));
    expect(calls).toHaveLength(2);
    expect(calls[0].get('Idempotency-Key')).toBeTruthy();
    expect(calls[1].get('Idempotency-Key')).toBe(calls[0].get('Idempotency-Key'));
    expect(calls[1].get('X-Import-Preview')).toBe(calls[0].get('X-Import-Preview'));
  });
});
