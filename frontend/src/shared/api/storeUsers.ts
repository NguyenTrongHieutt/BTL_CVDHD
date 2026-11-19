import { api } from './client';
import { MAX_PAGE_SIZE, pageRequestToQuery } from './pagination';
import type { PageRequest, PageResponse } from './pagination';
import type { StoreUser } from '../types/platform';

export const USERS_PATH = '/api/v1/platform/users';
export const usersQueryKey = ['platform', 'users'] as const;
export const actorOptionsKey = [...usersQueryKey, 'actor-options'] as const;

export function fetchUsers(request: PageRequest, signal?: AbortSignal): Promise<PageResponse<StoreUser>> {
  return api.get<PageResponse<StoreUser>>(USERS_PATH, { query: pageRequestToQuery(request), signal });
}

export async function fetchActorOptions(signal: AbortSignal) {
  const actors: StoreUser[] = [];
  for (let page = 0; ; page++) {
    const result = await fetchUsers({ page, size: MAX_PAGE_SIZE }, signal);
    actors.push(...result.items);
    if (!result.items.length || (page + 1) * result.size >= result.total) return actors;
  }
}
