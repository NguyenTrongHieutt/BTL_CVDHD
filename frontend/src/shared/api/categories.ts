import { api } from './client';
import { pageRequestToQuery } from './pagination';
import type { PageRequest, PageResponse } from './pagination';
import type { Category } from '../types/catalog';

export const CATEGORIES_PATH = '/api/v1/catalog/categories';
export const categoriesQueryKey = ['catalog', 'categories'] as const;

export function fetchCategories(request: PageRequest, signal?: AbortSignal, q?: string): Promise<PageResponse<Category>> {
  return api.get<PageResponse<Category>>(CATEGORIES_PATH, { query: { ...pageRequestToQuery(request), q }, signal });
}

export function fetchCategoryOption(id: string, signal: AbortSignal) {
  return api.get<Pick<Category, 'id' | 'name'>>(`${CATEGORIES_PATH}/${encodeURIComponent(id)}/option`, { signal });
}
