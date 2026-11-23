import { api } from '../../../shared/api/client';
import { MAX_PAGE_SIZE, pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type { StoreUser } from '../../../shared/types/platform';
import { fetchUsers } from './users';

const PATH = '/api/v1/platform/audit-log';

export type AuditFilters = Partial<Record<'action' | 'actorId' | 'fromDate' | 'toDate', string>>;
export type Snapshot = Record<string, unknown> | null;

export interface AuditEntry {
  id: string;
  createdAt: string;
  action: string;
  actionLabel: string;
  entity: string;
  entityId: string | null;
  actorId: string | null;
  actorName: string | null;
  before: Snapshot;
  after: Snapshot;
  requestId: string | null;
}

export function fetchAuditLog(filters: AuditFilters, paging: PageRequest, signal: AbortSignal) {
  return api.get<PageResponse<AuditEntry>>(PATH, { query: { ...filters, ...pageRequestToQuery(paging) }, signal });
}

export function fetchAuditActions(signal: AbortSignal) {
  return api.get<{ action: string; label: string }[]>(PATH + '/actions', { signal });
}

export async function fetchAuditActors(signal: AbortSignal) {
  const actors: StoreUser[] = [];
  for (let page = 0; ; page++) {
    const result = await fetchUsers({ page, size: MAX_PAGE_SIZE }, signal);
    actors.push(...result.items);
    if (!result.items.length || (page + 1) * result.size >= result.total) return actors;
  }
}
