/**
 * Hộp thoại xác nhận trả về Promise. Người giữ: Anh.
 *
 * Dùng `App.useApp().modal` để hộp thoại theo locale `vi_VN` và theme của `ConfigProvider`
 * (hàm tĩnh `Modal.confirm` không đọc được context).
 *
 * @example
 * const confirm = useConfirm();
 * if (await confirm({ title: 'Hủy Đơn hàng DH-000123?', danger: true, okText: 'Hủy đơn' })) { ... }
 */
import { App } from 'antd';
import { useCallback } from 'react';
import type { ReactNode } from 'react';

export interface ConfirmOptions {
  title: ReactNode;
  content?: ReactNode;
  okText?: string;
  cancelText?: string;
  /** Nút đồng ý màu đỏ, cho thao tác khó hoàn tác (hủy đơn, ngừng kinh doanh, khóa tài khoản). */
  danger?: boolean;
}

export type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>;

export function useConfirm(): ConfirmFn {
  const { modal } = App.useApp();
  return useCallback<ConfirmFn>(
    (options) =>
      new Promise<boolean>((resolve) => {
        modal.confirm({
          title: options.title,
          content: options.content,
          okText: options.okText ?? 'Đồng ý',
          cancelText: options.cancelText ?? 'Không',
          okButtonProps: { danger: options.danger },
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      }),
    [modal],
  );
}
