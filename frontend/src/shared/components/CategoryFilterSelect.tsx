import type { ComponentProps } from 'react';
import { RemoteFilterSelect } from './RemoteFilterSelect';
import { categoriesQueryKey, fetchCategories } from '../api/categories';

type Props = Omit<ComponentProps<typeof RemoteFilterSelect>, 'fetchOptions' | 'queryKey' | 'placeholder'> & {
  placeholder?: string;
};

export function CategoryFilterSelect({ placeholder = 'Tất cả danh mục', ...props }: Props) {
  return (
    <RemoteFilterSelect
      {...props}
      placeholder={placeholder}
      queryKey={categoriesQueryKey}
      fetchOptions={async (request, keyword, signal) => {
        const page = await fetchCategories(request, signal, keyword || undefined);
        return { ...page, items: page.items.map(({ id, name }) => ({ value: id, label: name })) };
      }}
    />
  );
}
