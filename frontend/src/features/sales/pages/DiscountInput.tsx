import { InputNumber, Select, Space } from 'antd';
import type { DiscountIntent } from '../../../shared/types/sales';

export function DiscountInput({ label, value, onChange }: {
  label: string;
  value: DiscountIntent | undefined;
  onChange: (value: DiscountIntent | undefined) => void;
}) {
  const type = value?.type ?? 'AMOUNT';
  return (
    <Space.Compact>
      <Select<DiscountIntent['type']>
        aria-label={label + ' theo'}
        value={type}
        options={[{ value: 'AMOUNT', label: '₫' }, { value: 'PERCENT', label: '%' }]}
        onChange={(nextType) => onChange({ type: nextType, value: 0 })}
      />
      <InputNumber
        aria-label={label}
        min={0}
        max={type === 'PERCENT' ? 100 : Number.MAX_SAFE_INTEGER}
        precision={type === 'AMOUNT' ? 0 : undefined}
        value={value?.value ?? null}
        placeholder="0"
        onChange={(amount) => onChange({ type, value: amount ?? 0 })}
      />
    </Space.Compact>
  );
}
