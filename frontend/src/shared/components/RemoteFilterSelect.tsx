import { useEffect, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Flex, Select, Typography } from 'antd';
import type { SelectProps } from 'antd';
import type { PageRequest, PageResponse } from '../api/pagination';
import { SEARCH_DEBOUNCE_MS } from './SearchInput';
import { SelectPageMenu, SELECT_PAGE_SIZE } from './SelectPageMenu';
import { handleSelectPageKey } from './selectPageKey';

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
  loading?: boolean;
  value?: FilterOption;
  optionRender?: SelectProps<FilterOption, Option>['optionRender'];
  emptyText?: string;
  preload?: boolean;
  errorText?: string;
  retryLabel?: string;
  onChange: (option?: Option) => void;
  queryKey: readonly string[];
  fetchOptions: (request: PageRequest, keyword: string, signal: AbortSignal) => Promise<PageResponse<Option>>;
}

export function RemoteFilterSelect<Option extends FilterOption>({
  ariaLabel, placeholder, style, value, disabled, loading, optionRender, emptyText = 'Không có kết quả khớp.',
  preload, errorText = 'Chưa tải được danh sách.', retryLabel = 'Tải lại lựa chọn',
  onChange, queryKey, fetchOptions, ...inputAttributes
}: RemoteFilterSelectProps<Option>) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const queryClient = useQueryClient();
  const term = search.trim();

  useEffect(() => {
    if (term === keyword) return undefined;
    const timer = setTimeout(() => setKeyword(term), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [keyword, term]);

  const termKey = [...queryKey, 'filter-options', keyword];
  const result = useQuery({
    queryKey: [...termKey, page],
    queryFn: ({ signal }) => fetchOptions({ page, size: SELECT_PAGE_SIZE }, keyword, signal),
    enabled: (open || (preload && !term && page === 0)) && !disabled && keyword === term,
  });
  const changingKeyword = keyword !== term;
  const searching = changingKeyword || result.isPending;
  const matches = searching ? [] : result.data?.items ?? [];
  const failed = !searching && result.isError && matches.length === 0;
  const firstPage = queryClient.getQueryData<PageResponse<Option>>([...termKey, 0]);
  const total = changingKeyword ? 0 : (result.data?.total ?? firstPage?.total ?? 0);
  const lastPage = Math.max(0, Math.ceil(total / SELECT_PAGE_SIZE) - 1);
  if (result.data && !changingKeyword && page > lastPage) setPage(lastPage);
  const pageCount = total > 0 || page > 0 ? Math.max(page, lastPage) + 1 : 0;
  const paging = { page, pageCount, onPageChange: setPage };
  const renderMenu = (menu: ReactNode) => <SelectPageMenu {...paging}>{menu}</SelectPageMenu>;
  const errorFeedback = failed ? (
    <Flex wrap align="center" gap={8}>
      <Typography.Text role="alert" type="danger">{errorText}</Typography.Text>
      <Button size="small" loading={result.isFetching} onMouseDown={(event) => event.preventDefault()}
        onClick={() => void result.refetch()}>{retryLabel}</Button>
    </Flex>
  ) : null;

  return (
    <>
      <Select<FilterOption, Option>
        {...inputAttributes}
        aria-label={ariaLabel}
        placeholder={placeholder}
        value={value ?? null}
        labelInValue
        allowClear
        open={open && !disabled}
        disabled={disabled}
        style={style}
        virtual={false}
        aria-keyshortcuts={pageCount > 1 ? 'PageUp PageDown' : undefined}
        loading={loading || (open && searching) || result.isFetching}
        showSearch={{ searchValue: search, filterOption: false, onSearch: (next) => {
          setSearch(next);
          if (next.trim() !== term) setPage(0);
        } }}
        onInputKeyDown={(event) => {
          if (!open) return;
          handleSelectPageKey(event, paging);
          // Máy quét gửi Enter ngay sau mã. Chờ kết quả mới trước khi cho phép chọn.
          if (event.key !== 'Enter' || (!searching && !failed)) return;
          event.preventDefault();
          event.stopPropagation();
          if (failed) void result.refetch();
          else setKeyword(term);
        }}
        onOpenChange={(visible) => {
          setOpen(visible);
          if (!visible) {
            setSearch('');
            setKeyword('');
            setPage(0);
          }
        }}
        onChange={(option) => {
          if (!option) onChange();
          else {
            const match = matches.find(({ value: id }) => id === option.value);
            if (match) onChange(match);
          }
        }}
        popupRender={renderMenu}
        options={matches}
        optionRender={optionRender}
        notFoundContent={searching ? 'Đang tìm…' : errorFeedback ?? emptyText}
      />
      {preload && !open ? errorFeedback : null}
    </>
  );
}
