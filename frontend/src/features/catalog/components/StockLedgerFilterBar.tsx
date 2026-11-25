/**
 * Thanh bộ lọc của màn Lịch sử xuất nhập kho (FR-28, KHO-07). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Sản phẩm / Biến thể (`StockLedgerProductPicker`), loại giao dịch, khoảng ngày theo lịch Việt Nam (tối đa 366 ngày,
 * `DateRangePicker` dùng chung) và "Xóa bộ lọc" khi đang lọc. `value` là bộ lọc đọc từ URL; mọi ô hiện đúng `value`.
 * `dateError` (khoảng ngày quá dài, hoặc lỗi `400` của backend trên khoảng ngày) hiện ngay dưới ô ngày, gắn với ô qua
 * `aria-describedby`, đọc ngay nhờ `role="alert"`.
 * TODO(Anh, KHO-07): thêm ô lọc theo người thực hiện khi API sổ kho có tham số `actorId`.
 */
import { Button, Flex, Select, Typography } from 'antd';
import { useId } from 'react';
import { DateRangePicker } from '../../../shared/components';
import type { StockDocumentType } from '../../../shared/types/inventory';
import { STOCK_DOCUMENT_TYPES, STOCK_DOCUMENT_TYPE_INFO, hasStockLedgerFilters } from '../api/stockLedger';
import type { StockLedgerFilters } from '../api/stockLedger';
import { StockLedgerProductPicker } from './StockLedgerProductPicker';

const TYPE_OPTIONS = STOCK_DOCUMENT_TYPES.map((type) => ({ value: type, label: STOCK_DOCUMENT_TYPE_INFO[type].label }));

export interface StockLedgerFilterBarProps {
  value: StockLedgerFilters;
  onChange: (next: StockLedgerFilters) => void;
  /** Lỗi của khoảng ngày, hiện dưới ô ngày. */
  dateError?: string;
  /** Tên của Biến thể đang lọc khi ô chọn Sản phẩm không tự biết (xem `StockLedgerProductPicker`). */
  variantLabel?: string;
}

export function StockLedgerFilterBar({ value, onChange, dateError, variantLabel }: StockLedgerFilterBarProps) {
  const dateErrorId = useId();
  const range = value.from && value.to ? { fromDate: value.from, toDate: value.to } : null;

  return (
    <Flex
      role="search"
      aria-label="Bộ lọc lịch sử xuất nhập kho"
      wrap
      gap={8}
      align="flex-start"
      style={{ marginBottom: 16 }}
    >
      <StockLedgerProductPicker
        value={{ productId: value.productId, variantId: value.variantId }}
        fallbackLabel={variantLabel}
        onChange={({ productId, variantId }) => onChange({ ...value, productId, variantId })}
      />
      <Select<StockDocumentType>
        aria-label="Lọc theo loại giao dịch"
        allowClear
        placeholder="Mọi loại giao dịch"
        value={value.type}
        options={TYPE_OPTIONS}
        onChange={(type) => onChange({ ...value, type: type ?? undefined })}
        style={{ width: 190 }}
      />
      <Flex
        className="stock-ledger-dates"
        vertical
        gap={4}
        role="group"
        aria-label="Khoảng ngày"
        aria-describedby={dateError ? dateErrorId : undefined}
      >
        <DateRangePicker
          allowClear
          value={range}
          onChange={(next) => onChange({ ...value, from: next?.fromDate, to: next?.toDate })}
        />
        {dateError ? (
          <Typography.Text id={dateErrorId} role="alert" type="danger">
            {dateError}
          </Typography.Text>
        ) : null}
      </Flex>
      {hasStockLedgerFilters(value) ? <Button onClick={() => onChange({})}>Xóa bộ lọc</Button> : null}
    </Flex>
  );
}
