import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { SellableItem } from '../types/sales';
import { ProductPicker } from './ProductPicker';

function setup(ai: Response) {
  const hit = {
    productId: 'product-b', variantId: 'variant-b', productName: 'Trà xanh', variantName: 'Ít đường',
    barcode: null, salePrice: 15000, quantity: 5, belowMin: false,
  };
  const requests: URL[] = [];
  const onPick = vi.fn<(item: SellableItem) => void>();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const url = new URL(input, 'http://shop.test');
    requests.push(url);
    if (url.pathname === '/ai/search') return ai;
    expect(url.pathname).toBe('/api/v1/sales/sellable');
    return Response.json({ items: [hit], total: 1, page: 0, size: 20 });
  }));
  render(<QueryClientProvider client={client}><ProductPicker semantic onPick={onPick} /></QueryClientProvider>);
  fireEvent.click(screen.getByRole('radio', { name: 'Theo mô tả (AI)' }));
  const input = screen.getByRole('searchbox', { name: 'Tìm hàng' });
  fireEvent.change(input, { target: { value: 'đồ uống dễ uống' } });
  fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
  return { requests, onPick };
}

it('loads and selects active variants in the suggested product order', async () => {
  const { requests, onPick } = setup(Response.json({ items: [{ productId: 'product-b' }, { productId: 'product-a' }] }));
  fireEvent.click(await screen.findByRole('button', { name: /^Thêm/ }));
  expect(requests[0].searchParams.get('q')).toBe('đồ uống dễ uống');
  expect(requests[1].searchParams.getAll('ids')).toEqual(['product-b', 'product-a']);
  expect(requests[1].searchParams.has('q')).toBe(false);
  expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ variantId: 'variant-b', unitPrice: 15000 }));
});

it('falls back to regular search after an AI service error', async () => {
  const { requests } = setup(Response.json({ type: '/errors/ai/unavailable', status: 503 }, { status: 503 }));
  expect(await screen.findByText('AI đang gián đoạn. Đang dùng tìm kiếm theo tên hoặc mã hàng.')).toBeTruthy();
  expect(await screen.findByText('Trà xanh · Ít đường')).toBeTruthy();
  expect(requests[1].searchParams.get('q')).toBe('đồ uống dễ uống');
  expect(requests[1].searchParams.has('ids')).toBe(false);
});
