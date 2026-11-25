import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import type { PageResponse } from '../api/pagination';
import { DataTable } from './DataTable';

interface Row {
  id: string;
  name: string;
}

const emptyPage: PageResponse<Row> = { items: [], total: 0, page: 0, size: 20 };
const examplePage: PageResponse<Row> = {
  ...emptyPage, items: [{ id: '1', name: 'Dòng của bộ lọc trước' }], total: 1,
};

function setup(fetchPage: () => Promise<PageResponse<Row>>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const table = (q = '') => (
    <QueryClientProvider client={client}>
      <DataTable rowKey="id" queryKey={['catalog', 'products', { q }]} fetchPage={fetchPage}
        columns={[{ title: 'Tên', dataIndex: 'name' }]} locale={{ emptyText: 'Không có dữ liệu' }} />
    </QueryClientProvider>
  );
  const view = render(table());
  return { changeFilter: (q: string) => view.rerender(table(q)) };
}

test('distinguishes an initial request from a successful empty result', async () => {
  let complete: (page: PageResponse<Row>) => void = () => {};
  setup(() => new Promise((resolve) => { complete = resolve; }));
  expect(screen.getByRole('status').textContent).toContain('Đang tải dữ liệu');
  expect(screen.queryByText('Không có dữ liệu')).toBeNull();

  complete(emptyPage);
  expect(await screen.findByText('Không có dữ liệu')).toBeTruthy();
});

test('allows retrying a failed request without showing a false empty result', async () => {
  const fetchPage = vi.fn<() => Promise<PageResponse<Row>>>()
    .mockRejectedValueOnce(new Error('Kết nối gián đoạn'))
    .mockResolvedValueOnce(examplePage);
  setup(fetchPage);
  await screen.findByRole('alert');
  expect(screen.queryByText('Không có dữ liệu')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
  expect(await screen.findByText('Dòng của bộ lọc trước')).toBeTruthy();
});

test('hides rows from the previous filter during the next request', async () => {
  const fetchPage = vi.fn<() => Promise<PageResponse<Row>>>()
    .mockResolvedValueOnce(examplePage)
    .mockImplementationOnce(() => new Promise(() => {}));
  const { changeFilter } = setup(fetchPage);
  expect(await screen.findByText('Dòng của bộ lọc trước')).toBeTruthy();

  changeFilter('khác');
  await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));
  expect(screen.queryByText('Dòng của bộ lọc trước')).toBeNull();
});
