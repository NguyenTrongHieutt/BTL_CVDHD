import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { SellableItem } from '../types/sales';
import { ProductPicker } from './ProductPicker';

it('giữ tìm từ khóa và dùng luồng riêng khi quét mã đầy đủ', async () => {
  const requests: URL[] = [];
  const onPick = vi.fn<(item: SellableItem) => void>();
  const item = {
    productId: 'coffee', variantId: 'coffee-dark', productName: 'Hộp cà phê',
    variantName: 'Đen', sku: 'CF-001-D', barcode: '00123450', salePrice: 30000, quantity: 2, belowMin: false,
  };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const url = new URL(String(input), 'http://localhost');
    requests.push(url);
    const results = url.searchParams.get('code') === '0012' ? [] : [item];
    return Response.json({ items: results, total: results.length, page: 0, size: 20 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><ProductPicker exactCodeLookup onPick={onPick} /></QueryClientProvider>);

  try {
    fireEvent.change(screen.getByLabelText('Tìm hàng'), { target: { value: 'ca phe' } });
    fireEvent.click(screen.getByRole('button', { name: /^Tìm$/ }));
    expect(await screen.findByRole('button', { name: 'Thêm Hộp cà phê · Đen' })).toBeTruthy();
    expect(requests.at(-1)?.pathname).toBe('/api/v1/sales/sellable');
    expect(requests.at(-1)?.searchParams.get('q')).toBe('ca phe');

    fireEvent.click(screen.getByText('Quét mã', { exact: true }));
    expect(screen.queryByRole('button', { name: 'Thêm Hộp cà phê · Đen' })).toBeNull();
    const scanner = screen.getByLabelText<HTMLInputElement>('SKU hoặc mã vạch');
    fireEvent.change(scanner, { target: { value: '00123450' } });
    fireEvent.keyDown(scanner, { key: 'Enter', code: 'Enter', keyCode: 13 });
    expect(await screen.findByText('CF-001-D', { exact: true })).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: 'Thêm Hộp cà phê · Đen' }));
    expect(scanner.value).toBe('');
    expect(requests.at(-1)?.pathname).toBe('/api/v1/sales/sellable/exact');
    expect(requests.at(-1)?.searchParams.get('code')).toBe('00123450');
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ variantId: 'coffee-dark', stockQuantity: 2 }));

    fireEvent.change(scanner, { target: { value: '0012' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tra mã' }));
    expect(await screen.findByText('Không có hàng đang bán khớp mã 0012.')).toBeTruthy();
  } finally {
    view.unmount();
    client.clear();
    vi.unstubAllGlobals();
  }
});
