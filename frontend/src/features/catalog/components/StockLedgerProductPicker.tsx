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
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Button, Flex, Select, Tag, Typography } from 'antd';
import type { SelectProps } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { SEARCH_DEBOUNCE_MS } from '../../../shared/components/SearchInput';
import type { Product, ProductVariant } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { describeError } from '../../../shared/messages.vi';
import { defaultVariant, fetchProduct, fetchProducts, productsQueryKey } from '../api/products';
import { foldText } from './textUtils';

const PAGE_SIZE = 20;
const LOAD_MORE = '__load_more_products__';
/** Giá trị của ô chọn Sản phẩm khi chỉ biết Biến thể (link chỉ có `variantId`). */
const VARIANT_ONLY = 'variant-only';
const VARIANT_ONLY_LABEL = 'Biến thể đã chọn';

type ProductOption = { value: Uuid; label: string; product?: Product };

/** Một dòng gợi ý: tên, kèm thẻ "Ngừng kinh doanh" cho hàng đã ngừng (vẫn chọn được để xem lịch sử). */
const renderProductOption: NonNullable<SelectProps<Uuid, ProductOption>['optionRender']> = (option) => (
  <Flex justify="space-between" gap={8}>
    <span>{option.label}</span>
    {option.data.product?.status === 'INACTIVE' ? <Tag>Ngừng kinh doanh</Tag> : null}
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
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const keepOpen = useRef(false);
  // Sản phẩm vừa chọn từ kết quả tìm: đã có đủ Biến thể, không cần tải chi tiết
  const [picked, setPicked] = useState<Product | null>(null);

  useEffect(() => {
    const next = search.trim();
    if (next === q) return undefined;
    const timer = setTimeout(() => setQ(next), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [search, q]);

  const results = useInfiniteQuery({
    queryKey: [...productsQueryKey, 'ledger-options', q],
    queryFn: ({ pageParam, signal }) => fetchProducts({ page: pageParam, size: PAGE_SIZE }, { q: q || undefined }, signal),
    initialPageParam: 0,
    getNextPageParam: ({ items, page, size, total }) =>
      items.length > 0 && (page + 1) * size < total ? page + 1 : undefined,
    enabled: open && search.trim() === q,
  });

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
  // Chữ đang gõ chưa được tìm (đang chờ hết thời gian gõ): không hiện kết quả của từ khóa cũ
  const searching = search.trim() !== q;
  const matches = searching ? [] : results.data?.pages.flatMap(({ items }) => items) ?? [];
  const options: ProductOption[] = matches.map((item) => ({ value: item.id, label: item.name, product: item }));
  if (!searching && results.hasNextPage) {
    options.push({
      value: LOAD_MORE,
      label: results.isFetchingNextPage ? 'Đang tải thêm…' : results.isFetchNextPageError ? 'Thử tải thêm' : 'Tải thêm kết quả',
    });
  }

  const resetSearch = () => {
    setSearch('');
    setQ('');
  };
  const selectValue = product || loadingProduct ? productId : variantId ? VARIANT_ONLY : undefined;

  const selectedLabel = () => {
    if (product) return product.name;
    if (loadingProduct) return 'Đang tải…';
    return fallbackLabel ?? VARIANT_ONLY_LABEL;
  };

  const onProductChange = (id: Uuid | undefined) => {
    if (id === LOAD_MORE) {
      keepOpen.current = true;
      if (!results.isFetching) void results.fetchNextPage();
      return;
    }
    const chosen = matches.find((item) => item.id === id);
    resetSearch();
    if (!id || !chosen) {
      setPicked(null);
      onChange({});
      return;
    }
    setPicked(chosen);
    onChange({ productId: chosen.id, variantId: chosen.variants.length > 1 ? undefined : defaultVariant(chosen)?.id });
  };

  const notFound = () => {
    if (searching || results.isFetching) return 'Đang tìm…';
    if (results.isError) return 'Không tải được danh sách Sản phẩm. Hãy thử lại.';
    return 'Không tìm thấy Sản phẩm nào';
  };

  return (
    <Flex vertical gap={4} className="stock-ledger-product-filter">
      <Flex wrap gap={8}>
        <Select<Uuid, ProductOption>
          aria-label="Lọc theo Sản phẩm"
          allowClear
          placeholder="Mọi Sản phẩm (gõ để tìm)"
          value={selectValue ?? null}
          open={open}
          onOpenChange={(next) => {
            if (!next && keepOpen.current) {
              keepOpen.current = false;
              return;
            }
            setOpen(next);
            if (!next) resetSearch();
          }}
          loading={results.isFetching || (Boolean(productId) && detail.isFetching)}
          showSearch={{ filterOption: false, searchValue: search, onSearch: setSearch }}
          // Enter (máy quét gửi Enter sau mã vạch) khi chưa có kết quả của chữ đang gõ: tìm ngay, không chờ hết thời gian
          // gõ; không để Select xử lý Enter này (danh sách trống thì Select đóng lại và mất chữ vừa quét)
          onInputKeyDown={(event) => {
            if (event.key !== 'Enter' || !(searching || results.isLoading)) return;
            event.preventDefault();
            event.stopPropagation();
            setQ(search.trim());
          }}
          options={options}
          optionRender={renderProductOption}
          // Sản phẩm đang chọn không có trong kết quả tìm hiện tại (mở từ link, tìm từ khác): không hiện id thô
          labelRender={({ label }) => label ?? selectedLabel()}
          notFoundContent={notFound()}
          onChange={onProductChange}
          style={{ width: 280, maxWidth: '100%' }}
        />
        {chooseVariant ? (
          <Select<Uuid>
            aria-label="Chọn Biến thể"
            allowClear
            placeholder="Tìm biến thể, SKU hoặc mã vạch"
            status={variantId ? undefined : 'warning'}
            value={variantId ?? null}
            options={product.variants.map((variant) => ({
              value: variant.id,
              label: variantLabel(variant),
              terms: foldText([variant.name, variant.sku, variant.barcode].filter(Boolean).join(' ')),
            }))}
            showSearch={{
              filterOption: (input, option) => option?.terms?.includes(foldText(input.trim())) ?? false,
            }}
            notFoundContent="Không có biến thể khớp từ khóa."
            onChange={(id) => onChange({ productId: product.id, variantId: id ?? undefined })}
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
