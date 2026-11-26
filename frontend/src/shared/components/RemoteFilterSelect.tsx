import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { Alert, Select } from 'antd';
import type { PageRequest, PageResponse } from '../api/pagination';
import { SEARCH_DEBOUNCE_MS } from './SearchInput';

const PAGE_SIZE = 20;
const LOAD_MORE = '__load_more_filter_options__';

export interface FilterOption {
  value: string;
  label: string;
}

interface RemoteFilterSelectProps<Option extends FilterOption> {
  id?: string;
  ariaLabel: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  'aria-required'?: boolean;
  placeholder: string;
  style?: CSSProperties;
  disabled?: boolean;
  value?: FilterOption;
  onChange: (option?: Option) => void;
  queryKey: readonly string[];
  fetchOptions: (request: PageRequest, keyword: string, signal: AbortSignal) => Promise<PageResponse<Option>>;
}

export function RemoteFilterSelect<Option extends FilterOption>({
  ariaLabel, placeholder, style, value, disabled, onChange, queryKey, fetchOptions, ...inputAttributes
}: RemoteFilterSelectProps<Option>) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [keyword, setKeyword] = useState('');
  const keepOpen = useRef(false);
  const term = search.trim();

  useEffect(() => {
    if (term === keyword) return undefined;
    const timer = setTimeout(() => setKeyword(term), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keyword, term]);

  const result = useInfiniteQuery({
    queryKey: [...queryKey, 'filter-options', keyword],
    queryFn: ({ pageParam, signal }) => fetchOptions({ page: pageParam, size: PAGE_SIZE }, keyword, signal),
    initialPageParam: 0,
    enabled: open && !disabled && keyword === term,
    getNextPageParam: ({ items, page, size, total }) =>
      items.length > 0 && (page + 1) * size < total ? page + 1 : undefined,
  });
  const searching = keyword !== term || result.isPending;
  const matches = searching ? [] : result.data?.pages.flatMap(({ items }) => items) ?? [];
  const options: FilterOption[] = [...matches];
  const failed = result.isError && matches.length === 0;
  if (result.hasNextPage && !searching) {
    options.push({
      value: LOAD_MORE,
      label: result.isFetchingNextPage ? 'Đang tải thêm…' : result.isFetchNextPageError ? 'Thử tải thêm' : 'Tải thêm kết quả',
    });
  }

  return (
    <Select<FilterOption, FilterOption>
      {...inputAttributes}
      aria-label={ariaLabel}
      placeholder={placeholder}
      value={value ?? null}
      labelInValue
      allowClear
      open={open && !disabled}
      disabled={disabled}
      style={style}
      loading={open && (searching || result.isFetching)}
      showSearch={{ searchValue: search, onSearch: setSearch, filterOption: false }}
      onInputKeyDown={(event) => {
        // Máy quét gửi Enter ngay sau mã. Chờ kết quả mới trước khi cho phép chọn.
        if (event.key !== 'Enter' || !open || (!searching && !result.isLoading)) return;
        event.preventDefault();
        event.stopPropagation();
        setKeyword(term);
      }}
      onOpenChange={(visible) => {
        if (!visible && keepOpen.current) {
          keepOpen.current = false;
          return;
        }
        setOpen(visible);
        if (!visible) {
          setSearch('');
          setKeyword('');
        }
      }}
      onChange={(option) => {
        if (option?.value === LOAD_MORE) {
          keepOpen.current = true;
          if (!result.isFetching) void result.fetchNextPage();
          return;
        }
        onChange(option ? matches.find(({ value: id }) => id === option.value) : undefined);
      }}
      options={options}
      notFoundContent={searching ? 'Đang tìm…' : failed
        ? <Alert type="error" title="Chưa tải được. Mở lại danh sách hoặc đổi từ khóa để thử lại." />
        : 'Không có kết quả khớp.'}
    />
  );
}
