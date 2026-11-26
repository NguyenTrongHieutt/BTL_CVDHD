import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { CurrentUserContext } from '../../../shared/api/session';
import { StockList } from './StockList';
import { StockPage } from './StockPage';
import type { StockItem } from './StockList';

const active: StockItem = {
  variantId: 'stock-active', productName: 'Trà nhài', variantName: '350 ml', sku: 'TRA-350',
  quantity: 4, minQuantity: 2, isActive: true, belowMin: false,
};
const inactive: StockItem = {
  ...active, variantId: 'stock-inactive', productName: 'Mẫu đã ngừng bán', variantName: null,
  sku: 'KHO-NGUNG', isActive: false,
};
const user = { storeId: 'a', storeName: 'Cửa hàng', userId: 'b', displayName: 'An', role: 'OWNER' as const };

function setup(component: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{component}</QueryClientProvider>);
}

it('nêu rõ kết quả lọc hàng ngừng và ảnh hưởng của bộ lọc tồn thấp', async () => {
  vi.stubGlobal('fetch', vi.fn<typeof fetch>(async (input) => {
    const { pathname, searchParams } = new URL(String(input), 'http://localhost');
    const items = pathname.endsWith('/categories') ? []
      : searchParams.get('includeInactive') === 'true' && searchParams.get('belowMin') !== 'true' ? [active, inactive]
        : [active];
    return Response.json({ items, total: items.length, page: 0, size: 20 });
  }));
  const view = setup(<StockList owner />);
  try {
    await screen.findByText('Trà nhài · 350 ml');
    expect(screen.queryByText('Mẫu đã ngừng bán')).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Gồm hàng ngừng kinh doanh' }));
    expect(await screen.findByText('Mẫu đã ngừng bán')).toBeTruthy();
    expect(screen.getByText('Trang này có 1 mặt hàng ngừng kinh doanh.')).toBeTruthy();
    fireEvent.click(screen.getByRole('checkbox', { name: 'Chỉ hàng tồn thấp' }));
    expect(await screen.findByText('Bỏ chọn “Chỉ hàng tồn thấp” để xem hàng ngừng kinh doanh.')).toBeTruthy();
  } finally {
    view.unmount();
    vi.unstubAllGlobals();
  }
});

it('tìm và chọn đúng SKU trong một ô rồi lưu ngưỡng từ dữ liệu tồn kho', async () => {
  const fetchMock = vi.fn<typeof fetch>(async (input, init) => {
    const { pathname, searchParams } = new URL(String(input), 'http://localhost');
    if (init?.method === 'PUT') return new Response(null, { status: 204 });
    if (pathname.endsWith('/low-stock')) return Response.json([]);
    const items = pathname.endsWith('/stock') && searchParams.get('q') === 'TRA-350' ? [active] : [];
    return Response.json({ items, total: items.length, page: 0, size: 20 });
  });
  vi.stubGlobal('fetch', fetchMock);
  const view = setup(<CurrentUserContext value={user}><StockPage /></CurrentUserContext>);
  try {
    const choose = screen.getByRole('combobox', { name: 'Sản phẩm hoặc biến thể' });
    fireEvent.mouseDown(choose);
    fireEvent.change(choose, { target: { value: 'TRA-350' } });
    fireEvent.click(await screen.findByRole('option', { name: 'Trà nhài · 350 ml · TRA-350' }));
    const threshold = screen.getByRole<HTMLInputElement>('spinbutton', { name: 'Ngưỡng tồn tối thiểu' });
    expect(threshold.value).toBe('2');
    fireEvent.change(threshold, { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lưu ngưỡng' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(
      ([input, init]) => String(input).endsWith('/stock/stock-active/min-quantity')
        && init?.method === 'PUT' && init.body === JSON.stringify({ minQuantity: 5 }),
    )).toBe(true));
    expect(await screen.findByText('Đã lưu ngưỡng tồn tối thiểu.')).toBeTruthy();
  } finally {
    view.unmount();
    vi.unstubAllGlobals();
  }
});
