import { useState } from 'react';
import type { ReactNode } from 'react';
import { Select } from 'antd';
import type { SelectProps } from 'antd';
import { foldText } from '../foldText';
import type { FilterOption } from './RemoteFilterSelect';
import { SelectPageMenu, SELECT_PAGE_SIZE } from './SelectPageMenu';
import { handleSelectPageKey } from './selectPageKey';

interface LocalOption extends FilterOption {
  searchText?: string;
}

interface LocalFilterSelectProps extends Pick<SelectProps<FilterOption, LocalOption>,
  'id' | 'aria-label' | 'aria-describedby' | 'aria-invalid' | 'aria-required' | 'allowClear' |
  'disabled' | 'loading' | 'status' | 'placeholder' | 'style' | 'notFoundContent'> {
  options: LocalOption[];
  value?: string | null;
  onChange?: (value?: string) => void;
}

export function LocalFilterSelect({ options, value, onChange, ...props }: LocalFilterSelectProps) {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(false);
  const keyword = foldText(search.trim());
  const matching = keyword ? options.filter(({ label, value: optionId, searchText }) =>
    foldText(searchText ?? `${label} ${optionId}`).includes(keyword)) : options;
  const pageCount = Math.ceil(matching.length / SELECT_PAGE_SIZE);
  const currentPage = Math.max(0, Math.min(page, pageCount - 1));
  const paging = { page: currentPage, pageCount, onPageChange: setPage };
  const renderMenu = (menu: ReactNode) => <SelectPageMenu {...paging}>{menu}</SelectPageMenu>;
  const selected = value ? options.find((option) => option.value === value) ?? { value, label: value } : null;
  const offset = currentPage * SELECT_PAGE_SIZE;

  return (
    <Select<FilterOption, LocalOption>
      notFoundContent="Không có kết quả khớp."
      {...props}
      aria-keyshortcuts={pageCount > 1 ? 'PageUp PageDown' : undefined}
      labelInValue
      virtual={false}
      value={selected}
      options={matching.slice(offset, offset + SELECT_PAGE_SIZE)}
      showSearch={{ searchValue: search, filterOption: false, onSearch: (next) => {
        setSearch(next);
        setPage(0);
      } }}
      onOpenChange={(visible) => {
        setOpen(visible);
        if (!visible) {
          setPage(0);
          setSearch('');
        }
      }}
      onInputKeyDown={(event) => { if (open) handleSelectPageKey(event, paging); }}
      popupRender={renderMenu}
      onChange={(option) => onChange?.(option?.value)}
    />
  );
}
