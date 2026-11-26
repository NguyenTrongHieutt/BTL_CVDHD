import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, expect, it, vi } from 'vitest';
import { AuditLogPage } from './AuditLogPage';

vi.setConfig({ testTimeout: 15_000 });

const path = '/api/v1/platform/audit-log';
const action = 'catalog.variant.price_change';
const actor = '1b0fbc81-e1c7-40ac-84dc-bce30e74885a';
const entry = {
  id: 'entry', createdAt: '2026-11-15T16:30:00Z', action, actionLabel: 'Sửa giá bán',
  entity: 'variant', entityId: 'variant-1', actorId: actor, actorName: 'Chị Hoa',
  before: { salePrice: 15000 }, after: { salePrice: 16000 }, requestId: 'req',
};

function Location() {
  return <output aria-label="URL">{useLocation().search}</output>;
}

function openPage(search = '') {
  const requests: URL[] = [];
  vi.stubGlobal('fetch', async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://localhost');
    requests.push(url);
    if (url.pathname === path + '/actions') return Response.json([{ action, label: 'Sửa giá bán' }]);
    if (url.pathname === '/api/v1/platform/users') {
      return Response.json({ items: [{ userId: actor, displayName: 'Chị Hoa' }], total: 1, page: 0, size: 100 });
    }
    if (url.pathname !== path) throw new Error(url.pathname);
    return Response.json({
      items: [entry, { ...entry, id: 'old', action: 'legacy', actionLabel: 'legacy', actorName: null }, { ...entry, id: 'system', actionLabel: 'Tự động', actorId: null, actorName: null }],
      total: 45, page: Number(url.searchParams.get('page')), size: 20,
    });
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<MemoryRouter initialEntries={['/platform/audit-log' + search]}>
    <QueryClientProvider client={client}><AuditLogPage /><Location /></QueryClientProvider>
  </MemoryRouter>);
  return () => requests.filter((url) => url.pathname === path);
}

afterEach(() => vi.unstubAllGlobals());

it('hiện nhãn, người thực hiện và bảng giá cũ/mới theo thứ tự API', async () => {
  openPage();
  const row = (await screen.findByText('Sửa giá bán')).closest('tr')!;
  expect(within(row).getByText('Chị Hoa')).toBeTruthy();
  expect(screen.getByText('Hệ thống')).toBeTruthy();
  expect(screen.getByText('Chưa rõ tên · ' + actor)).toBeTruthy();
  expect(screen.getByText('legacy')).toBeTruthy();
  fireEvent.click(within(row).getByRole('button', { name: 'Chi tiết' }));
  const detail = screen.getByText('Giá bán').closest('tr')!;
  expect(within(detail).getByText(/15\.000\s*₫/)).toBeTruthy();
  expect(within(detail).getByText(/16\.000\s*₫/)).toBeTruthy();
});

it('khôi phục bộ lọc từ URL và về trang đầu khi đổi ngày', async () => {
  const logs = openPage('?action=' + action + '&actorId=' + actor + '&fromDate=2026-11-01&toDate=2026-11-15&page=1');
  await waitFor(() => expect(logs()).toHaveLength(1));
  expect(Object.fromEntries(logs()[0].searchParams)).toEqual({
    action, actorId: actor, fromDate: '2026-11-01', toDate: '2026-11-15', page: '1', size: '20',
  });
  await screen.findAllByText('Chị Hoa');
  fireEvent.change(screen.getByLabelText('Đến ngày'), { target: { value: '2026-11-16' } });
  await waitFor(() => expect(logs().at(-1)?.searchParams.get('page')).toBe('0'));
  expect(screen.getByLabelText('URL').textContent).toContain('toDate=2026-11-16');
  fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
  await waitFor(() => expect(logs().at(-1)?.searchParams.has('action')).toBe(false));
  expect(screen.getByLabelText('URL').textContent).toBe('');
});

it.each([
  ['2026-11-16', '2026-11-15', 'Ngày kết thúc phải từ ngày bắt đầu trở đi'],
  ['2024-01-01', '2025-01-01', 'Khoảng ngày tối đa 366 ngày'],
])('chặn khoảng ngày %s đến %s', async (from, to, message) => {
  const logs = openPage('?fromDate=' + from + '&toDate=' + to);
  expect(screen.getByRole('alert').textContent).toBe(message);
  expect(screen.queryByText('Không có nhật ký thao tác')).toBeNull();
  expect(logs()).toHaveLength(0);
  fireEvent.click(screen.getByRole('button', { name: 'Xóa bộ lọc' }));
  await waitFor(() => expect(logs()).toHaveLength(1));
});

it('không tải nhật ký nếu liên kết chứa ngày không hợp lệ', async () => {
  const logs = openPage('?fromDate=2026-02-30');
  expect(screen.getByRole('alert').textContent).toContain('Ngày bắt đầu không hợp lệ');
  expect(screen.getByLabelText('Từ ngày').getAttribute('aria-invalid')).toBe('true');
  expect(logs()).toHaveLength(0);
  fireEvent.change(screen.getByLabelText('Từ ngày'), { target: { value: '2026-02-28' } });
  await waitFor(() => expect(logs().at(-1)?.searchParams.get('fromDate')).toBe('2026-02-28'));
});
