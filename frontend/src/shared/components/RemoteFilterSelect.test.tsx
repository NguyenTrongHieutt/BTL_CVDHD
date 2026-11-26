import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import { RemoteFilterSelect } from './RemoteFilterSelect';

type FilterProps = ComponentProps<typeof RemoteFilterSelect>;

it('thay danh sách khi chuyển trang và tìm từ đầu khi đổi từ khóa', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const onChange = vi.fn<FilterProps['onChange']>();
  const fetchOptions = vi.fn<FilterProps['fetchOptions']>(async ({ page, size }, keyword) => ({
    page,
    size,
    total: keyword === 'riêng' ? 1 : size * 2 + 1,
    items: [{ value: String(page + 1), label: keyword === 'riêng' ? 'Kết quả riêng' : 'Danh mục ' + (page + 1) }],
  }));
  render(<QueryClientProvider client={queryClient}>
    <RemoteFilterSelect ariaLabel="Danh mục" placeholder="Tất cả danh mục" queryKey={['test-filter']}
      fetchOptions={fetchOptions} onChange={onChange} />
  </QueryClientProvider>);

  const search = screen.getByRole<HTMLInputElement>('combobox', { name: 'Danh mục' });
  fireEvent.mouseDown(search);
  fireEvent.change(search, { target: { value: 'nhóm' } });
  expect(await screen.findByText('Trang 1/3')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Trang sau' }));
  expect(await screen.findByTitle('Danh mục 2')).toBeTruthy();
  expect(screen.queryByTitle('Danh mục 1')).toBeNull();
  expect(search.value).toBe('nhóm');
  expect(screen.getByText('Trang 2/3')).toBeTruthy();
  fireEvent.keyDown(search, { key: 'PageDown' });
  expect(await screen.findByTitle('Danh mục 3')).toBeTruthy();
  fireEvent.keyDown(search, { key: 'PageUp' });
  expect(await screen.findByTitle('Danh mục 2')).toBeTruthy();
  expect(fetchOptions.mock.calls.filter(([, keyword]) => keyword === 'nhóm').map(([request]) => request.page))
    .toEqual([0, 1, 2]);
  expect(onChange).not.toHaveBeenCalled();

  fireEvent.change(search, { target: { value: 'riêng' } });
  fireEvent.keyDown(search, { key: 'Enter' });
  expect(await screen.findByTitle('Kết quả riêng')).toBeTruthy();
  expect(fetchOptions.mock.calls.at(-1)?.[0].page).toBe(0);
  expect(screen.queryByRole('button', { name: 'Trang sau' })).toBeNull();
});
