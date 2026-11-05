/**
 * Chọn Khách hàng cho Đơn hàng (FR-14). Người giữ: Anh (component), API của Hiếu (catalog).
 * CHƯA LÀM: đây là khung giữ chỗ với hợp đồng props đã chốt.
 *
 * TODO(Anh, DH-06/FR-14, GĐ2):
 *   - Tìm theo tên hoặc số điện thoại bằng API Tìm kiếm Khách hàng của Hiếu (KH-02/FR-19).
 *   - `allowQuickCreate`: tạo nhanh Khách hàng mới bằng số điện thoại ngay trên màn bán hàng.
 *   - Lỗi trùng số điện thoại trong Cửa hàng hiện qua `messages.vi.ts`.
 */
import { Select } from 'antd';
import type { CustomerSummary } from '../types/catalog';

export interface CustomerPickerProps {
  value?: CustomerSummary | null;
  /** `null` khi bỏ chọn (khách lẻ). */
  onChange: (customer: CustomerSummary | null) => void;
  /** Cho tạo nhanh Khách hàng bằng số điện thoại. */
  allowQuickCreate?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export function CustomerPicker({ value, disabled, placeholder = 'Khách lẻ (gõ tên hoặc số điện thoại)' }: CustomerPickerProps) {
  return (
    <Select
      style={{ width: '100%' }}
      disabled={disabled ?? true}
      placeholder={placeholder}
      value={value ? value.customerId : undefined}
      options={value ? [{ value: value.customerId, label: `${value.name} · ${value.phone}` }] : []}
      notFoundContent="Sẽ làm ở GĐ2 — Anh (DH-06)"
    />
  );
}
