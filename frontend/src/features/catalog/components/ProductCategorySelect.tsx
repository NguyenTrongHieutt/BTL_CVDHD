import type { ComponentProps } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { categoriesQueryKey, fetchCategoryOption } from '../../../shared/api/categories';
import { isApiError } from '../../../shared/api/client';
import { CategoryFilterSelect } from '../../../shared/components/CategoryFilterSelect';
import type { Uuid } from '../../../shared/types/common';

type Props = Pick<ComponentProps<typeof CategoryFilterSelect>,
  'id' | 'ariaLabel' | 'aria-describedby' | 'aria-invalid' | 'aria-required' | 'placeholder' | 'style' | 'disabled'> & {
  value?: Uuid | null;
  currentName?: string | null;
  onChange?: (value?: Uuid) => void;
};

const optionKey = (id?: Uuid | null) => [...categoriesQueryKey, 'option', id] as const;

export function ProductCategorySelect({ value, currentName, onChange, ...props }: Props) {
  const queryClient = useQueryClient();
  const selected = useQuery({
    queryKey: optionKey(value),
    enabled: Boolean(value),
    queryFn: ({ signal }) => fetchCategoryOption(value ?? '', signal),
  });
  const missing = isApiError(selected.error) && selected.error.status === 404;
  const label = selected.data?.name ?? currentName ??
    (missing ? 'Danh mục không còn' : selected.isError ? 'Không tải được Danh mục' : 'Đang tải danh mục…');

  return (
    <CategoryFilterSelect
      {...props}
      preload
      errorText="Không tải được danh sách Danh mục."
      retryLabel="Tải lại Danh mục"
      emptyText="Không có danh mục khớp."
      value={value ? { value, label } : undefined}
      onChange={(option) => {
        if (option) queryClient.setQueryData(optionKey(option.value), { id: option.value, name: option.label });
        onChange?.(option?.value);
      }}
    />
  );
}
