/** Đặt tiêu đề tab trình duyệt theo màn hình. Người giữ: Anh. */
import { useEffect } from 'react';

export const APP_TITLE = 'Quản lý bán hàng';

export function useDocumentTitle(title: string | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} · ${APP_TITLE}` : APP_TITLE;
  }, [title]);
}
