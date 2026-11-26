import { Button, InputNumber, Typography } from 'antd';
import { useId } from 'react';
import { MAX_LOYALTY_POINTS } from '../../../shared/api/loyalty';
import { MoneyText } from '../../../shared/components/MoneyText';
import type { CustomerLoyalty } from '../../../shared/types/sales';

interface Props {
  account?: CustomerLoyalty;
  points?: number;
  maxRedeemable?: number;
  disabled: boolean;
  error?: string;
  onChange: (points: number | undefined) => void;
}

export function LoyaltyRedemption({ account, points, maxRedeemable, disabled, error, onChange }: Props) {
  const id = useId();
  if (!account && !points) return null;
  if (account?.pointValue === 0 && !points) {
    return <Typography.Text type="secondary">Cửa hàng đang tắt đổi điểm.</Typography.Text>;
  }

  const max = maxRedeemable ?? account?.balance;
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;

  return (
    <div className="pos-loyalty">
      <label htmlFor={id}>Điểm dùng cho đơn này</label>
      <div className="pos-loyalty-actions">
        <InputNumber<number> id={id} min={0} max={MAX_LOYALTY_POINTS} precision={0}
          inputMode="numeric" value={points ?? null} disabled={disabled || (!account && !points)}
          aria-describedby={error ? `${helpId} ${errorId}` : helpId}
          aria-invalid={Boolean(error)} status={error ? 'error' : undefined}
          onChange={(value) => onChange(value !== null && value > 0 ? value : undefined)} />
        {Boolean(points) && <Button type="link" disabled={disabled} onClick={() => onChange(undefined)}>Bỏ dùng điểm</Button>}
      </div>
      <Typography.Text id={helpId} type="secondary">
        {account ? <>1 điểm đổi <MoneyText value={account.pointValue} />
          {max !== undefined ? ` · Tối đa ${max.toLocaleString('vi-VN')} điểm` : ''}</>
          : 'Bỏ dùng điểm hoặc thử tải lại số dư của khách.'}
      </Typography.Text>
      {error && <Typography.Text id={errorId} type="danger" role="alert">{error}</Typography.Text>}
    </div>
  );
}
