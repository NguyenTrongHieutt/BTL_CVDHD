/**
 * Tìm kiếm Sản phẩm cho màn bán hàng (FR-35, AD-12). Người giữ: Lân (C).
 * `ProductPicker` của Anh dùng hàm này khi gắn Tìm kiếm ngữ nghĩa (TH-01).
 *
 * Gọi `GET /ai/search` và chờ tối đa 1,5 giây. Quá giờ hoặc gặp lỗi (ai-service tắt thì nginx
 * trả `503 /errors/ai/unavailable`) thì chuyển sang Tìm kiếm thường (FR-5). Kết quả chỉ có id
 * Sản phẩm; tên, giá, Tồn kho lấy từ `GET /api/v1/sales/sellable?ids=`.
 */
import type { SemanticSearchResponse } from '../types/assistant';
import type { Uuid } from '../types/common';
import { api, isAbortError } from './client';

export const AI_SEARCH_PATH = '/ai/search';
export const AI_SEARCH_TIMEOUT_MS = 1500;
export const AI_SEARCH_LIMIT = 10;

export type ProductSearchMode = 'semantic' | 'normal';

export interface ProductSearchResult {
  /** `semantic`: kết quả từ ai-service; `normal`: đã chuyển sang Tìm kiếm thường. */
  mode: ProductSearchMode;
  /** Id Sản phẩm theo thứ tự liên quan giảm dần. */
  ids: Uuid[];
}

export interface ProductSearchOptions {
  /** Hủy khi người bán gõ tiếp. Bị hủy thì hàm ném lại lỗi hủy, không chuyển sang Tìm kiếm thường. */
  signal?: AbortSignal;
  limit?: number;
}

export async function searchProducts(q: string, options: ProductSearchOptions = {}): Promise<ProductSearchResult> {
  const query = q.trim();
  if (!query) return { mode: 'normal', ids: [] };
  try {
    return { mode: 'semantic', ids: await semanticSearch(query, options) };
  } catch (error) {
    if (options.signal?.aborted) throw error;
    return { mode: 'normal', ids: await normalSearch(query, options.signal) };
  }
}

async function semanticSearch(query: string, options: ProductSearchOptions): Promise<Uuid[]> {
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(new DOMException('Tìm kiếm ngữ nghĩa quá 1,5 giây', 'TimeoutError')),
    AI_SEARCH_TIMEOUT_MS,
  );
  const outer = options.signal;
  const forwardAbort = () => controller.abort(outer?.reason);
  if (outer?.aborted) forwardAbort();
  outer?.addEventListener('abort', forwardAbort, { once: true });
  try {
    const response = await api.get<SemanticSearchResponse>(AI_SEARCH_PATH, {
      query: { q: query, limit: options.limit ?? AI_SEARCH_LIMIT },
      signal: controller.signal,
    });
    return response.items.map((hit) => hit.productId);
  } finally {
    clearTimeout(timer);
    outer?.removeEventListener('abort', forwardAbort);
  }
}

/** Tìm kiếm thường (FR-5) theo tên, mã, mã vạch; không phân biệt hoa thường, không cần gõ dấu. */
/** Tìm kiếm thường (FR-5) theo hợp đồng CB-03: `GET /api/v1/sales/sellable?q=` (sales của Anh, GĐ1). */
export const NORMAL_SEARCH_PATH = '/api/v1/sales/sellable';

async function normalSearch(query: string, signal: AbortSignal | undefined): Promise<Uuid[]> {
  try {
    const page = await api.get<{ items: { productId: Uuid }[] }>(NORMAL_SEARCH_PATH, {
      query: { q: query, size: AI_SEARCH_LIMIT },
      signal,
    });
    // Nhiều Biến thể của cùng Sản phẩm: giữ thứ tự, bỏ trùng.
    return [...new Set(page.items.map((item) => item.productId))];
  } catch (error) {
    if (isAbortError(error)) {
      throw error;
    }
    // Endpoint chưa có (trước GĐ1) hoặc lỗi: trả rỗng để màn bán hàng vẫn dùng được quét mã vạch.
    return [];
  }
}
