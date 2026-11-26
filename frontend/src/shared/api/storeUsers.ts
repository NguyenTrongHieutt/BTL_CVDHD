import { api } from './client';
import { pageRequestToQuery } from './pagination';
import type { PageRequest, PageResponse } from './pagination';
import type { StoreUser } from '../types/platform';

export const USERS_PATH = '/api/v1/platform/users';
export const usersQueryKey = ['platform', 'users'] as const;

export function fetchUsers(request: PageRequest, signal?: AbortSignal, q?: string): Promise<PageResponse<StoreUser>> {
  return api.get<PageResponse<StoreUser>>(USERS_PATH, { query: { ...pageRequestToQuery(request), q }, signal });
}

export function fetchActorOption(id: string, signal: AbortSignal) {
  return api.get<Pick<StoreUser, 'userId' | 'displayName'>>(
    `${USERS_PATH}/${encodeURIComponent(id)}/option`, { signal },
  );
}
