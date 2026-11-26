import type { ComponentProps } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchActorOption, fetchUsers, usersQueryKey } from '../api/storeUsers';
import { RemoteFilterSelect } from './RemoteFilterSelect';

type Props = Pick<ComponentProps<typeof RemoteFilterSelect>, 'ariaLabel' | 'style' | 'placeholder'> & {
  value?: string;
  onChange: (id?: string) => void;
};

const selectionKey = (id?: string) => [...usersQueryKey, 'actor-option', id] as const;

export function ActorFilterSelect({ value, onChange, ...props }: Props) {
  const queryClient = useQueryClient();
  const actor = useQuery({
    queryKey: selectionKey(value),
    queryFn: ({ signal }) => fetchActorOption(value ?? '', signal),
    enabled: Boolean(value),
    staleTime: 60_000,
  });
  const label = actor.data?.displayName ?? (actor.isFetching ? 'Đang tải người thực hiện…' : 'Người thực hiện đã chọn');

  return (
    <RemoteFilterSelect
      {...props}
      value={value ? { value, label } : undefined}
      onChange={(option) => {
        if (option) {
          queryClient.setQueryData(selectionKey(option.value), { userId: option.value, displayName: option.label });
        }
        onChange(option?.value);
      }}
      queryKey={usersQueryKey}
      fetchOptions={async (request, keyword, signal) => {
        const page = await fetchUsers(request, signal, keyword || undefined);
        return { ...page, items: page.items.map(({ userId, displayName }) => ({ value: userId, label: displayName })) };
      }}
    />
  );
}
