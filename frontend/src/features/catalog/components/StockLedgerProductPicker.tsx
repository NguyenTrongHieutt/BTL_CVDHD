/**
 * Ô chọn Sản phẩm / Biến thể của bộ lọc Lịch sử xuất nhập kho (FR-28, KHO-07). Người giữ: Hiếu — features/catalog.
 *
 * Gõ để tìm bằng `GET /api/v1/catalog/products?q=` (Tìm kiếm thường của SP-05: tên không cần dấu, SKU, mã vạch; cả
 * hàng đã ngừng kinh doanh, vì lịch sử của chúng vẫn còn), gõ xong một lúc mới tìm; Enter (máy quét mã vạch) thì tìm
 * ngay. Khi chữ đang gõ chưa được tìm, danh sách gợi ý trống ("Đang tìm…") để Enter không chọn nhầm kết quả cũ. Đóng
 * danh sách hay chọn xong thì xóa chữ đang gõ. Sổ kho lọc theo Biến thể: Sản phẩm một Biến thể thì chọn luôn Biến thể
 * mặc định; Sản phẩm nhiều Biến thể thì hiện thêm ô chọn Biến thể, chưa chọn thì chưa lọc (nhắc "Chọn Biến thể để lọc").
 *
 * Mở từ link (`?productId=&variantId=`): tải chi tiết Sản phẩm để hiện tên và các Biến thể; tải lỗi thì báo dưới ô, có
 * nút thử lại. Link chỉ có `variantId` (hoặc `productId` không khớp Biến thể) thì hiện `fallbackLabel` (tên lấy từ
 * dòng sổ kho của Biến thể đó).
 */
import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Tag, Typography } from 'antd';
import type { SelectProps } from 'antd';
import { useState } from 'react';
import { LocalFilterSelect } from '../../../shared/components/LocalFilterSelect';
import { RemoteFilterSelect } from '../../../shared/components/RemoteFilterSelect';
import type { FilterOption } from '../../../shared/components/RemoteFilterSelect';
import type { Product, ProductVariant } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { describeError } from '../../../shared/messages.vi';
import { defaultVariant, fetchProduct, fetchProducts, productsQueryKey } from '../api/products';

/** Giá trị của ô chọn Sản phẩm khi chỉ biết Biến thể (link chỉ có `variantId`). */
const VARIANT_ONLY = 'variant-only';
const VARIANT_ONLY_LABEL = 'Biến thể đã chọn';

type ProductOption = FilterOption & { product: Product };

/** Một dòng gợi ý: tên, kèm thẻ "Ngừng kinh doanh" cho hàng đã ngừng (vẫn chọn được để xem lịch sử). */
const renderProductOption: NonNullable<SelectProps<FilterOption, ProductOption>['optionRender']> = (option) => (
  <Flex justify="space-between" gap={8}>
    <span style={{ overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis' }}>{option.label}</span>
    {option.data.product.status === 'INACTIVE' ? <Tag style={{ flexShrink: 0 }}>Ngừng kinh doanh</Tag> : null}
  </Flex>
);

export interface ProductVariantSelection {
  productId?: Uuid;
  variantId?: Uuid;
}

export interface StockLedgerProductPickerProps {
  value: ProductVariantSelection;
  /** Tên hiện khi không biết Sản phẩm của `value.variantId`, ví dụ "Sữa tươi" hoặc "Sản phẩm không còn". */
  fallbackLabel?: string;
  onChange: (next: ProductVariantSelection) => void;
}

function variantLabel(variant: ProductVariant): string {
  const label = `${variant.name ?? 'Mặc định'} · ${variant.sku}`;
  return variant.status === 'INACTIVE' ? `${label} (ngừng kinh doanh)` : label;
}

export function StockLedgerProductPicker({ value, fallbackLabel, onChange }: StockLedgerProductPickerProps) {
  // Sản phẩm vừa chọn từ kết quả tìm đã có đủ Biến thể.
  const [picked, setPicked] = useState<Product | null>(null);

  const { productId, variantId } = value;
  const detail = useQuery({
    queryKey: [...productsQueryKey, { id: productId }],
    queryFn: ({ signal }) => fetchProduct(productId ?? '', signal),
    enabled: Boolean(productId) && picked?.id !== productId,
  });

  const candidate = productId && picked?.id === productId ? picked : detail.data;
  // Link sửa tay: `productId` không chứa `variantId`, hoặc không có `variantId` mà Sản phẩm chỉ một Biến thể (giao diện
  // không bao giờ tạo ra) thì không tin `productId`: hiện tên theo Biến thể, hoặc như chưa chọn
  const product =
    candidate &&
    productId &&
    (variantId ? candidate.variants.some((v) => v.id === variantId) : candidate.variants.length > 1)
      ? candidate
      : undefined;
  const chooseVariant = product !== undefined && product.variants.length > 1;
  const loadingProduct = !product && detail.isLoading;
  const detailFailed = Boolean(productId) && !product && detail.isError;
  const selectValue = product || loadingProduct ? productId : variantId ? VARIANT_ONLY : undefined;
  const label = product?.name ?? (loadingProduct ? 'Đang tải…' : fallbackLabel ?? VARIANT_ONLY_LABEL);

  const onProductChange = (option?: ProductOption) => {
    if (!option) {
      setPicked(null);
      onChange({});
      return;
    }
    const chosen = option.product;
    setPicked(chosen);
    onChange({ productId: chosen.id, variantId: chosen.variants.length > 1 ? undefined : defaultVariant(chosen)?.id });
  };

  return (
    <Flex vertical gap={4} className="stock-ledger-product-filter">
      <Flex wrap gap={8}>
        <RemoteFilterSelect<ProductOption>
          ariaLabel="Lọc theo Sản phẩm"
          placeholder="Mọi Sản phẩm (gõ để tìm)"
          value={selectValue ? { value: selectValue, label } : undefined}
          loading={Boolean(productId) && detail.isFetching}
          queryKey={[...productsQueryKey, 'ledger-options']}
          fetchOptions={async (request, keyword, signal) => {
            const result = await fetchProducts(request, { q: keyword || undefined }, signal);
            return { ...result, items: result.items.map((item) => ({ value: item.id, label: item.name, product: item })) };
          }}
          optionRender={renderProductOption}
          emptyText="Không tìm thấy Sản phẩm nào"
          onChange={onProductChange}
          style={{ width: 280, maxWidth: '100%' }}
        />
        {chooseVariant ? (
          <LocalFilterSelect
            key={product.id}
            aria-label="Chọn Biến thể"
            allowClear
            placeholder="Tìm biến thể, SKU hoặc mã vạch"
            status={variantId ? undefined : 'warning'}
            value={variantId}
            options={product.variants.map((variant) => ({
              value: variant.id,
              label: variantLabel(variant),
              searchText: [variant.name, variant.sku, variant.barcode].filter(Boolean).join(' '),
            }))}
            notFoundContent="Không có biến thể khớp từ khóa."
            onChange={(id) => onChange({ productId: product.id, variantId: id })}
            style={{ width: 255, maxWidth: '100%' }}
          />
        ) : null}
      </Flex>
      {chooseVariant && !variantId ? <Typography.Text type="warning">Chọn Biến thể để lọc</Typography.Text> : null}
      {detailFailed ? (
        <Flex align="center" gap={4} wrap>
          <Typography.Text type="danger" role="alert">
            Không tải được Sản phẩm đã chọn. {describeError(detail.error).message}
          </Typography.Text>
          <Button type="link" size="small" loading={detail.isFetching} onClick={() => void detail.refetch()}>
            Tải lại
          </Button>
        </Flex>
      ) : null}
    </Flex>
  );
}
