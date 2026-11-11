/**
 * Thanh bộ lọc của màn Sản phẩm (FR-5, SP-05). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Ô Tìm kiếm thường (theo tên không cần dấu, SKU, mã vạch; gõ xong một lúc thì tìm, Enter tìm ngay nên máy quét mã
 * vạch dùng được), chọn Danh mục, khoảng giá bán, trạng thái, và nút "Xóa bộ lọc" khi đang lọc. `value` là bộ lọc đọc
 * từ URL: ô Danh mục, trạng thái luôn hiện đúng `value`; ô tìm kiếm và ô giá tự giữ chữ đang gõ, và được dựng lại khi
 * `value` đổi từ nơi khác (ví dụ bấm lại menu "Sản phẩm" làm URL mất bộ lọc, hoặc xóa bộ lọc).
 *
 * Ô giá: chữ số, dấu chấm nhóm nghìn (tự nhóm khi gõ). Áp dụng khi rời ô hoặc bấm Enter; giá sai, hoặc giá từ lớn hơn
 * giá đến, thì báo dưới ô (gắn với ô qua `aria-describedby`, đọc ngay nhờ `role="alert"`) và không áp dụng (backend
 * cũng từ chối, `400`).
 */
import { Button, Flex, Input, Select, Typography } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import { SearchInput } from '../../../shared/components';
import type { ProductSearchParams, ProductStatus } from '../../../shared/types/catalog';
import { useCategoryOptions } from '../api/categories';
import { hasProductFilters } from './productFilterParams';
import { MONEY_MAX, foldText, groupThousands, parseWholeNumber } from './textUtils';

const MIN_LABEL = 'Giá từ';
const MAX_LABEL = 'Giá đến';

const STATUS_OPTIONS: { value: ProductStatus; label: string }[] = [
  { value: 'ACTIVE', label: 'Đang bán' },
  { value: 'INACTIVE', label: 'Ngừng kinh doanh' },
];

export interface ProductFilterBarProps {
  value: ProductSearchParams;
  onChange: (next: ProductSearchParams) => void;
}

/** Phần bộ lọc do ô tìm kiếm, ô giá giữ chữ riêng. */
function textFilters({ q, minPrice, maxPrice }: ProductSearchParams): string {
  return JSON.stringify([q ?? '', minPrice ?? null, maxPrice ?? null]);
}

function priceText(value: number | undefined): string {
  return value === undefined ? '' : (groupThousands(String(value)) ?? '');
}

/** Chữ của ô giá thành số; trống thì `value` là `undefined`. */
function readPrice(label: string, text: string): { value?: number; error?: string } {
  const value = parseWholeNumber(text);
  if (value === null) return {};
  if (Number.isNaN(value)) {
    return { error: text.trim().startsWith('-') ? `${label} không được âm` : `${label} phải là số nguyên` };
  }
  if (value > MONEY_MAX) return { error: `${label} quá lớn` };
  return { value };
}

interface PriceRangeProps {
  minPrice: number | undefined;
  maxPrice: number | undefined;
  onApply: (minPrice: number | undefined, maxPrice: number | undefined) => void;
}

function PriceRange({ minPrice, maxPrice, onApply }: PriceRangeProps) {
  const [minText, setMinText] = useState(() => priceText(minPrice));
  const [maxText, setMaxText] = useState(() => priceText(maxPrice));
  const [errors, setErrors] = useState<{ min?: string; max?: string }>({});
  const errorId = useId();

  const apply = () => {
    const min = readPrice(MIN_LABEL, minText);
    const max = readPrice(MAX_LABEL, maxText);
    const reversed =
      min.value !== undefined && max.value !== undefined && min.value > max.value
        ? `${MIN_LABEL} không được lớn hơn ${MAX_LABEL.toLowerCase()}`
        : undefined;
    const next = { min: min.error ?? reversed, max: max.error };
    setErrors(next);
    if (next.min || next.max) return;
    if (min.value !== minPrice || max.value !== maxPrice) onApply(min.value, max.value);
  };

  const inputProps = {
    inputMode: 'numeric' as const,
    autoComplete: 'off',
    suffix: '₫',
    onBlur: apply,
    onPressEnter: apply,
    style: { width: 150 },
  };
  const messages = [errors.min, errors.max].filter(Boolean);

  return (
    <Flex vertical gap={4}>
      <Flex align="center" gap={8}>
        <Input
          {...inputProps}
          aria-label={MIN_LABEL}
          placeholder={MIN_LABEL}
          status={errors.min ? 'error' : undefined}
          aria-invalid={Boolean(errors.min)}
          aria-describedby={errors.min ? errorId : undefined}
          value={minText}
          onChange={(event) => setMinText(groupThousands(event.target.value) ?? '')}
        />
        <Typography.Text type="secondary">–</Typography.Text>
        <Input
          {...inputProps}
          aria-label={MAX_LABEL}
          placeholder={MAX_LABEL}
          status={errors.max ? 'error' : undefined}
          aria-invalid={Boolean(errors.max)}
          aria-describedby={errors.max ? errorId : undefined}
          value={maxText}
          onChange={(event) => setMaxText(groupThousands(event.target.value) ?? '')}
        />
      </Flex>
      {messages.length > 0 ? (
        <Typography.Text id={errorId} role="alert" type="danger">
          {messages.join('. ')}
        </Typography.Text>
      ) : null}
    </Flex>
  );
}

export function ProductFilterBar({ value, onChange }: ProductFilterBarProps) {
  const categories = useCategoryOptions();
  // Bộ lọc đổi mà không phải do ô tìm kiếm, ô giá gửi lên (URL đổi từ nơi khác): dựng lại hai ô này theo `value`.
  const sent = useRef(textFilters(value));
  const [inputsKey, setInputsKey] = useState(0);
  useEffect(() => {
    const current = textFilters(value);
    if (current !== sent.current) {
      sent.current = current;
      setInputsKey((key) => key + 1);
    }
  }, [value]);

  const apply = (next: ProductSearchParams) => {
    sent.current = textFilters(next);
    onChange(next);
  };

  // Xóa bộ lọc: khi `value` trống về tới (URL đã đổi) thì dựng lại ô tìm kiếm, ô giá, kể cả khi chúng vốn trống
  // (bỏ chữ đang gõ dở, lỗi dưới ô giá). `textFilters` không bao giờ trả chuỗi rỗng nên hiệu ứng luôn dựng lại.
  const clear = () => {
    sent.current = '';
    onChange({});
  };

  /** Nhãn của Danh mục đang chọn khi không có trong danh sách. */
  const missingCategoryLabel = () => {
    if (categories.isLoading) return '…';
    if (categories.isError) return 'Không tải được Danh mục';
    return 'Danh mục không còn';
  };

  return (
    <Flex role="search" aria-label="Bộ lọc Sản phẩm" wrap gap={8} align="flex-start" style={{ marginBottom: 16 }}>
      <SearchInput
        key={`q-${inputsKey}`}
        aria-label="Tìm Sản phẩm"
        placeholder="Tìm theo tên, SKU, mã vạch"
        defaultValue={value.q ?? ''}
        onSearch={(q) => apply({ ...value, q: q || undefined })}
        style={{ width: 280 }}
      />
      <Select<string>
        aria-label="Lọc theo Danh mục"
        allowClear
        placeholder={categories.isError ? 'Không tải được Danh mục' : 'Mọi Danh mục'}
        status={categories.isError ? 'error' : undefined}
        value={value.categoryId}
        loading={categories.isLoading}
        options={(categories.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }))}
        // Danh mục trên URL không có trong danh sách (đã xóa, hoặc tải danh sách lỗi): không hiện id thô
        labelRender={({ label }) => label ?? missingCategoryLabel()}
        showSearch={{
          filterOption: (input, option) => foldText(String(option?.label ?? '')).includes(foldText(input)),
        }}
        notFoundContent={
          categories.isError ? 'Không tải được danh sách Danh mục. Hãy tải lại trang.' : 'Chưa có Danh mục nào'
        }
        onChange={(categoryId) => apply({ ...value, categoryId: categoryId ?? undefined })}
        style={{ width: 200 }}
      />
      <PriceRange
        key={`price-${inputsKey}`}
        minPrice={value.minPrice}
        maxPrice={value.maxPrice}
        onApply={(minPrice, maxPrice) => apply({ ...value, minPrice, maxPrice })}
      />
      <Select<ProductStatus>
        aria-label="Lọc theo trạng thái"
        allowClear
        placeholder="Mọi trạng thái"
        value={value.status}
        options={STATUS_OPTIONS}
        onChange={(status) => apply({ ...value, status: status ?? undefined })}
        style={{ width: 180 }}
      />
      {hasProductFilters(value) ? <Button onClick={clear}>Xóa bộ lọc</Button> : null}
    </Flex>
  );
}
