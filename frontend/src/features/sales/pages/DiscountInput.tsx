import { useId, useState } from 'react';
import { Button, InputNumber, Modal, Radio, Space, Typography } from 'antd';
import { formatMoney } from '../../../shared/components/money';
import type { DiscountIntent } from '../../../shared/types/sales';

export function DiscountInput({ label, scope, value, disabled, error, onChange }: {
  label: string;
  scope: 'line' | 'order';
  value: DiscountIntent | undefined;
  disabled?: boolean;
  error?: string;
  onChange: (value: DiscountIntent | undefined) => void;
}) {
  const amountId = useId();
  const [open, setOpen] = useState(false);
  const [type, setType] = useState<DiscountIntent['type']>('AMOUNT');
  const [amount, setAmount] = useState<number | null>(null);
  const applied = value && value.value > 0 ? value : undefined;
  const summary = applied?.type === 'PERCENT' ? applied.value.toLocaleString('vi-VN') + '%'
    : applied ? formatMoney(applied.value) : null;
  const buttonText = scope === 'line' ? summary ?? 'Thêm giảm giá'
    : applied ? 'Sửa giảm giá cả đơn' : 'Giảm giá cả đơn';
  const max = type === 'PERCENT' ? 100 : Number.MAX_SAFE_INTEGER;

  function edit() {
    setType(applied?.type ?? 'AMOUNT');
    setAmount(applied?.value ?? null);
    setOpen(true);
  }

  function apply(next: DiscountIntent | undefined) {
    if (disabled) return;
    onChange(next);
    setOpen(false);
  }

  return (
    <div className="pos-discount">
      <Button aria-label={summary ? `${label}, đã áp dụng ${summary}` : label}
        aria-haspopup="dialog" danger={Boolean(error)} disabled={disabled} onClick={edit}>
        {buttonText}
      </Button>
      {error && <Typography.Text type="danger" role="alert">{error}</Typography.Text>}
      <Modal open={open && !disabled} title={label} width={420} onCancel={() => setOpen(false)}
        footer={(
          <Space wrap>
            {applied && <Button danger onClick={() => apply(undefined)}>Bỏ giảm giá</Button>}
            <Button onClick={() => setOpen(false)}>Hủy</Button>
            <Button type="primary" disabled={amount === null || amount <= 0 || amount > max}
              onClick={() => { if (amount !== null && amount > 0 && amount <= max) apply({ type, value: amount }); }}>
              Áp dụng giảm giá
            </Button>
          </Space>
        )}>
        <Space orientation="vertical" className="pos-discount-form">
          <Typography.Text type="secondary">
            {scope === 'line' ? 'Áp dụng trên thành tiền của dòng hàng, gồm tất cả số lượng đã chọn.'
              : 'Áp dụng trên tổng tiền sau khi giảm từng dòng hàng, trước khi trừ điểm.'}
          </Typography.Text>
          <Radio.Group
            aria-label="Cách giảm giá"
            value={type}
            onChange={(event) => {
              setType(event.target.value as DiscountIntent['type']);
              setAmount(null);
            }}
            options={[
              { label: 'Số tiền (₫)', value: 'AMOUNT' },
              { label: 'Phần trăm (%)', value: 'PERCENT' },
            ]}
          />
          <label htmlFor={amountId}>{type === 'PERCENT' ? 'Phần trăm giảm' : 'Số tiền giảm'}</label>
          <InputNumber id={amountId} min={0} max={max}
            inputMode={type === 'PERCENT' ? 'decimal' : 'numeric'} precision={type === 'AMOUNT' ? 0 : undefined}
            value={amount} onChange={setAmount} />
        </Space>
      </Modal>
    </div>
  );
}
