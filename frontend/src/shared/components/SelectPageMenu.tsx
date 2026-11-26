import { LeftOutlined, RightOutlined } from '@ant-design/icons';
import { Button, Flex, theme, Typography } from 'antd';
import type { ReactNode } from 'react';
import type { SelectPaging } from './selectPageKey';

export const SELECT_PAGE_SIZE = 6;

export function SelectPageMenu({ children, page, pageCount, onPageChange }: SelectPaging & { children: ReactNode }) {
  const { token } = theme.useToken();
  if (pageCount === 0) return children;

  return (
    <>
      <div style={{ minHeight: pageCount > 1 ? SELECT_PAGE_SIZE * token.controlHeight : undefined }}>{children}</div>
      <Flex role="group" aria-label="Phân trang trong danh sách" align="center" justify="space-between" gap={4}
        style={{ borderTop: `1px solid ${token.colorBorderSecondary}`, padding: '4px 2px 0' }}
        // Giữ focus trong ô tìm khi bấm vào nút. PageUp/PageDown cho phép điều khiển bằng bàn phím.
        onMouseDown={(event) => event.preventDefault()}>
        <Button type="text" aria-label="Trang trước" icon={<LeftOutlined />} title="Trang trước (Page Up)"
          tabIndex={-1} disabled={page === 0} onClick={() => onPageChange(page - 1)} />
        <Typography.Text type="secondary" role="status" aria-atomic="true" style={{ fontSize: 13, textAlign: 'center' }}>
          Trang {page + 1}/{pageCount}
        </Typography.Text>
        <Button type="text" aria-label="Trang sau" icon={<RightOutlined />} title="Trang sau (Page Down)"
          tabIndex={-1} disabled={page + 1 >= pageCount} onClick={() => onPageChange(page + 1)} />
      </Flex>
    </>
  );
}
