import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
  const view = render(<QueryClientProvider client={client}>
    <ProductPicker semantic exactCodeLookup onPick={onPick} />
  </QueryClientProvider>);

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
    expect(requests.some((url) => url.pathname.startsWith('/ai/'))).toBe(false);
  } finally {
    view.unmount();
    client.clear();
    vi.unstubAllGlobals();
  }
});

it('tìm theo mô tả, giữ thứ tự AI và không tự gợi ý cho kết quả mơ hồ', async () => {
  const requests: URL[] = [];
  const onPick = vi.fn<(item: SellableItem) => void>();
  const soldOut = {
    productId: 'tea', variantId: 'tea-can', productName: 'Trà xanh', variantName: 'Lon',
    sku: 'TEA-CAN', barcode: null, salePrice: 12000, quantity: 0, belowMin: true,
  };
  const available = {
    productId: 'juice', variantId: 'juice-bottle', productName: 'Nước cam', variantName: 'Chai',
    sku: 'JUICE-BOTTLE', barcode: null, salePrice: 18000, quantity: 7, belowMin: false,
  };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const url = new URL(String(input), 'http://localhost');
    requests.push(url);
    if (url.pathname === '/ai/search') {
      return Response.json({ items: [{ productId: 'tea', score: 0.9 }, { productId: 'juice', score: 0.8 }] });
    }
    return Response.json({ items: [soldOut, available], total: 2, page: 0, size: 20 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}>
    <ProductPicker semantic exactCodeLookup onPick={onPick} />
  </QueryClientProvider>);

  try {
    fireEvent.click(screen.getByText('Theo mô tả (AI)', { exact: true }));
    fireEvent.change(screen.getByLabelText('Mô tả sản phẩm'), { target: { value: 'đồ uống vị thanh' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm theo mô tả' }));

    expect(await screen.findByRole('button', { name: 'Tìm sản phẩm tương tự Trà xanh · Lon' })).toBeTruthy();
    expect(screen.queryByText('Sản phẩm tương tự còn hàng')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Thêm Nước cam · Chai' }));

    expect(requests[0].pathname).toBe('/ai/search');
    expect(requests[0].searchParams.get('q')).toBe('đồ uống vị thanh');
    expect(requests[1].pathname).toBe('/api/v1/sales/sellable');
    expect(requests[1].searchParams.getAll('ids')).toEqual(['tea', 'juice']);
    expect(requests[1].searchParams.has('q')).toBe(false);
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ productId: 'juice', variantId: 'juice-bottle' }));
  } finally {
    view.unmount();
    client.clear();
    vi.unstubAllGlobals();
  }
});

it('tự chuyển về tìm thường nếu AI gián đoạn hoặc quá 1,5 giây', async () => {
  const requests: URL[] = [];
  const item = {
    productId: 'toothpaste', variantId: 'toothpaste-mint', productName: 'Kem đánh răng',
    variantName: 'Bạc hà', sku: 'KDR-01', barcode: null, salePrice: 25000, quantity: 4, belowMin: false,
  };
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const url = new URL(String(input), 'http://localhost');
    requests.push(url);
    if (url.pathname === '/ai/search') return Response.json({ status: 503 }, { status: 503 });
    return Response.json({ items: [item], total: 1, page: 0, size: 20 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}>
    <ProductPicker semantic onPick={vi.fn<(item: SellableItem) => void>()} />
  </QueryClientProvider>);

  try {
    fireEvent.click(screen.getByText('Theo mô tả (AI)', { exact: true }));
    fireEvent.change(screen.getByLabelText('Mô tả sản phẩm'), { target: { value: 'chăm sóc răng miệng' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tìm theo mô tả' }));

    expect(await screen.findByText(/chưa phản hồi sau 1,5 giây/u)).toBeTruthy();
    expect(await screen.findByText('Kem đánh răng · Bạc hà')).toBeTruthy();
    expect(requests[1].pathname).toBe('/api/v1/sales/sellable');
    expect(requests[1].searchParams.get('q')).toBe('chăm sóc răng miệng');
    expect(requests[1].searchParams.has('ids')).toBe(false);
  } finally {
    view.unmount();
    client.clear();
    vi.unstubAllGlobals();
  }
});

it('tự gợi ý tối đa 3 Sản phẩm còn hàng khi quét trúng duy nhất hàng đã hết', async () => {
  const requests: URL[] = [];
  const onPick = vi.fn<(item: SellableItem) => void>();
  const soldOut = {
    productId: 'milk', variantId: 'milk-180', productName: 'Sữa tươi', variantName: '180 ml',
    sku: 'MILK-180', barcode: '8930001', salePrice: 9000, quantity: 0, belowMin: true,
  };
  const suggestions = [
    { productId: 'soy', variantId: 'soy-box', productName: 'Sữa đậu nành', variantName: 'Hộp',
      sku: 'SOY-BOX', barcode: null, salePrice: 10000, quantity: 8, belowMin: false },
    { productId: 'soy', variantId: 'soy-can', productName: 'Sữa đậu nành', variantName: 'Lon',
      sku: 'SOY-CAN', barcode: null, salePrice: 11000, quantity: 5, belowMin: false },
    { productId: 'oat', variantId: 'oat-box', productName: 'Sữa yến mạch', variantName: 'Hộp',
      sku: 'OAT-BOX', barcode: null, salePrice: 16000, quantity: 3, belowMin: false },
    { productId: 'almond', variantId: 'almond-box', productName: 'Sữa hạnh nhân', variantName: 'Hộp',
      sku: 'ALMOND-BOX', barcode: null, salePrice: 19000, quantity: 2, belowMin: false },
  ];
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const url = new URL(String(input), 'http://localhost');
    requests.push(url);
    if (url.pathname === '/api/v1/sales/sellable/exact') {
      return Response.json({ items: [soldOut], total: 1, page: 0, size: 20 });
    }
    if (url.pathname === '/ai/search/alternatives') {
      return Response.json({ items: [
        { productId: 'milk', score: 1 }, { productId: 'soy', score: 0.9 },
        { productId: 'soy', score: 0.8 }, { productId: 'oat', score: 0.7 }, { productId: 'almond', score: 0.6 },
      ] });
    }
    return Response.json({ items: suggestions, total: suggestions.length, page: 0, size: 3 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(<QueryClientProvider client={client}>
    <ProductPicker semantic exactCodeLookup onPick={onPick} />
  </QueryClientProvider>);

  try {
    fireEvent.click(screen.getByText('Quét mã', { exact: true }));
    const scanner = screen.getByLabelText('SKU hoặc mã vạch');
    fireEvent.change(scanner, { target: { value: '8930001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Tra mã' }));

    expect(await screen.findByText('Sản phẩm tương tự còn hàng')).toBeTruthy();
    await waitFor(() => expect(requests.some((url) => url.pathname === '/api/v1/sales/sellable' &&
      url.searchParams.get('inStock') === 'true')).toBe(true));
    const alternativeRequest = requests.find((url) => url.pathname === '/ai/search/alternatives');
    const hydrationRequest = requests.find((url) => url.pathname === '/api/v1/sales/sellable' &&
      url.searchParams.get('inStock') === 'true');
    expect(alternativeRequest?.searchParams.get('productId')).toBe('milk');
    expect(hydrationRequest?.searchParams.getAll('ids')).toEqual(['soy', 'oat', 'almond']);
    expect(hydrationRequest?.searchParams.get('size')).toBe('3');
    expect(await screen.findByText('Sữa đậu nành · Hộp')).toBeTruthy();
    expect(screen.queryByText('Sữa đậu nành · Lon')).toBeNull();
    expect(screen.getByText('Sữa yến mạch · Hộp')).toBeTruthy();
    expect(screen.getByText('Sữa hạnh nhân · Hộp')).toBeTruthy();

    fireEvent.change(scanner, { target: { value: '8930002' } });
    expect(screen.queryByText('Sản phẩm tương tự còn hàng')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Tìm sản phẩm tương tự Sữa tươi · 180 ml' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Thêm sản phẩm gợi ý Sữa yến mạch · Hộp' }));
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ productId: 'oat', stockQuantity: 3 }));
  } finally {
    view.unmount();
    client.clear();
    vi.unstubAllGlobals();
  }
});
