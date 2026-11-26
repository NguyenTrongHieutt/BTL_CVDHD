import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import { RemoteFilterSelect } from './RemoteFilterSelect';

type FilterProps = ComponentProps<typeof RemoteFilterSelect>;

it('loads the next set of options without selecting the load more row', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onChange = vi.fn<FilterProps['onChange']>();
  const fetchOptions = vi.fn<FilterProps['fetchOptions']>(async ({ page, size }) => ({
    page,
    size,
    total: 21,
    items: [{ value: String(page + 1), label: 'Danh mục ' + (page + 1) }],
  }));
  const { container } = render(<QueryClientProvider client={queryClient}>
    <RemoteFilterSelect ariaLabel="Danh mục" placeholder="Tất cả danh mục" queryKey={['test-filter']}
      fetchOptions={fetchOptions} onChange={onChange} />
  </QueryClientProvider>);

  fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Danh mục' }));
  fireEvent.click(await screen.findByTitle('Tải thêm kết quả'));
  expect(await screen.findByTitle('Danh mục 2')).toBeTruthy();
  expect(fetchOptions.mock.calls.map(([request]) => request.page)).toEqual([0, 1]);
  expect(onChange).not.toHaveBeenCalled();
  expect(container.querySelector('[title="Tải thêm kết quả"]')).toBeNull();
});
