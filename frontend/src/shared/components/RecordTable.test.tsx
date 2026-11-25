import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { RecordTable } from './RecordTable';

it('giữ nhãn, giá trị và thao tác của dòng trên màn hình nhỏ', () => {
  const media = window.matchMedia('(max-width: 767px)');
  const matchMedia = vi.spyOn(window, 'matchMedia').mockImplementation(query => ({
    ...media, media: query, matches: query === media.media,
  }));
  const edit = vi.fn<(id: string) => void>();
  const product = { id: 'one', name: 'Trà nhài', stock: { quantity: 0 }, active: true };

  try {
    const { container } = render(
      <RecordTable<typeof product>
        rowKey="id"
        dataSource={[product]}
        compactColumns={{ title: 'name', status: 'status', actions: 'actions' }}
        columns={[
          { title: 'Tên', dataIndex: 'name' },
          { title: 'Tồn hiện tại', dataIndex: ['stock', 'quantity'], render: value => `${value} chai` },
          { title: 'Trạng thái', key: 'status', render: (_, row) => row.active ? 'Đang bán' : 'Ngừng kinh doanh' },
          { title: 'Thao tác', key: 'actions', render: (_, row) => (
            <button type="button" onClick={() => edit(row.id)}>Sửa {row.name}</button>
          ) },
        ]}
      />,
    );

    const card = within(container.querySelector<HTMLElement>('.record-card')!);
    expect(card.getByText('Tồn hiện tại')).toBeTruthy();
    expect(card.getByText('0 chai')).toBeTruthy();
    expect(card.getByText('Đang bán')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Sửa Trà nhài' }));
    expect(edit).toHaveBeenCalledExactlyOnceWith('one');
    expect(screen.queryByRole('columnheader')).toBeNull();
  } finally {
    matchMedia.mockRestore();
  }
});
