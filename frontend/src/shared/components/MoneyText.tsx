/**
 * Hiển thị số tiền VND, ví dụ "1.234.000 ₫". Người giữ: Anh.
 *
 * Chỉ định dạng, không tính toán: mọi số tiền đến từ backend dưới dạng số nguyên đồng (AD-8).
 */
import { Typography } from 'antd';
import type { Money } from '../types/common';
import { formatMoney } from './money';

export interface MoneyTextProps {
  value: Money | null | undefined;
  strong?: boolean;
  /** Màu chữ của antd, ví dụ `danger` cho tiền hoàn. */
  type?: 'secondary' | 'success' | 'warning' | 'danger';
  className?: string;
}

export function MoneyText({ value, strong, type, className }: MoneyTextProps) {
  return (
    <Typography.Text strong={strong} type={type} className={className ? `money-text ${className}` : 'money-text'}>
      {formatMoney(value)}
    </Typography.Text>
  );
}
