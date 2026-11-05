/**
 * Khung giữ chỗ cho màn hình chưa làm. Người giữ: Anh.
 * Mỗi feature thay component này bằng màn thật ở giai đoạn ghi trên trang.
 */
import { ToolOutlined } from '@ant-design/icons';
import { Result, Typography } from 'antd';
import type { ReactNode } from 'react';
import { useDocumentTitle } from './useDocumentTitle';

export interface PagePlaceholderProps {
  title: string;
  /** Mã chức năng trong PRD, ví dụ `['FR-9', 'FR-10']`. */
  frs: readonly string[];
  /** Giai đoạn làm màn này (Phan-cong-cong-viec mục 4). */
  phase: 'GĐ1' | 'GĐ2' | 'GĐ3';
  owner: 'Anh' | 'Hiếu' | 'Lân';
  description?: ReactNode;
  children?: ReactNode;
}

export function PagePlaceholder({ title, frs, phase, owner, description, children }: PagePlaceholderProps) {
  useDocumentTitle(title);
  return (
    <div>
      <Typography.Title level={3} style={{ marginTop: 0 }}>
        {title}
      </Typography.Title>
      <Result
        icon={<ToolOutlined />}
        title={`Sẽ làm ở ${phase} — ${owner}`}
        subTitle={
          <>
            <div>{frs.join(', ')}</div>
            {description ? <div style={{ marginTop: 8 }}>{description}</div> : null}
          </>
        }
      />
      {children}
    </div>
  );
}
