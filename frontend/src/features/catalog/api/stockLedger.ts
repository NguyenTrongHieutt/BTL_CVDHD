/**
 * Lịch sử xuất nhập kho (FR-28, KHO-07). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Đọc sổ kho của Anh (inventory, KHO-01) bằng `GET /api/v1/inventory/ledger` qua `shared/api`, không import
 * features/inventory. Khóa `['inventory', 'ledger', params]` (dữ liệu của inventory: mutation của Anh invalidate
 * `['inventory']` là bảng tải lại).
 *
 * Bộ lọc nằm trên URL query của màn hình, để tải lại trang hay gửi link vẫn giữ: `type` (loại giao dịch), `variantId`
 * (Biến thể, tham số lọc của API), `productId` (Sản phẩm của Biến thể đó, chỉ để ô chọn hiện tên và các Biến thể),
 * `from`, `to` (khoảng ngày `yyyy-MM-dd` theo lịch Việt Nam). Giá trị hỏng trên URL (loại lạ, id không phải UUID, ngày
 * sai, chỉ có một đầu, khoảng ngược) bị bỏ qua để link gõ tay không làm bảng báo lỗi; khoảng quá 366 ngày thì giữ để
 * báo dưới ô ngày, không gửi.
 */
import { api } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import { MAX_RANGE_DAYS, countDaysInclusive } from '../../../shared/components/dateRange';
import type { IsoDate, Uuid } from '../../../shared/types/common';
import type { StockDocumentType, StockLedgerEntry, StockLedgerSearchParams } from '../../../shared/types/inventory';
import { UUID_PATTERN } from '../components/productFilterParams';

export const STOCK_LEDGER_PATH = '/api/v1/inventory/ledger';

export const stockLedgerQueryKey = ['inventory', 'ledger'] as const;

// Tắt khi cần dùng frontend với bản backend cũ chưa có actorId.
export function ledgerActorFilterEnabled(): boolean {
  return import.meta.env.VITE_LEDGER_ACTOR_FILTER !== 'false';
}

export interface DocumentTypeInfo {
  /** Nhãn tiếng Việt. */
  label: string;
  /** Màu `Tag` của antd. */
  color: string;
}

/** Nhãn, màu của từng loại giao dịch, theo thứ tự hiện trong ô lọc. */
export const STOCK_DOCUMENT_TYPE_INFO: Readonly<Record<StockDocumentType, DocumentTypeInfo>> = {
  OPENING: { label: 'Tồn đầu kỳ', color: 'default' },
  SALE: { label: 'Bán hàng', color: 'blue' },
  SALE_CANCEL: { label: 'Hủy đơn', color: 'orange' },
  SALE_RETURN: { label: 'Trả hàng', color: 'gold' },
  PURCHASE_RECEIPT: { label: 'Nhập hàng', color: 'green' },
  STOCKTAKE: { label: 'Kiểm kho', color: 'purple' },
};

export const STOCK_DOCUMENT_TYPES = Object.keys(STOCK_DOCUMENT_TYPE_INFO) as StockDocumentType[];

function isStockDocumentType(value: string): value is StockDocumentType {
  return Object.hasOwn(STOCK_DOCUMENT_TYPE_INFO, value);
}

/** Nhãn, màu của một loại giao dịch; loại frontend chưa biết thì hiện nguyên mã, màu mặc định. */
export function documentTypeInfo(documentType: string): DocumentTypeInfo {
  return isStockDocumentType(documentType)
    ? STOCK_DOCUMENT_TYPE_INFO[documentType]
    : { label: documentType, color: 'default' };
}

/**
 * Một trang sổ kho, mới nhất trước (backend sắp). Bộ lọc trống không gửi.
 * Cách bật bộ lọc người thực hiện sau khi tích hợp backend: xem contracts.md mục 3.3.
 */
export function fetchStockLedger(
  request: PageRequest,
  filters: StockLedgerSearchParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<StockLedgerEntry>> {
  const { variantId, documentType, fromDate, toDate, actorId } = filters;
  return api.get<PageResponse<StockLedgerEntry>>(STOCK_LEDGER_PATH, {
    query: { variantId, documentType, fromDate, toDate, actorId, ...pageRequestToQuery(request) },
    signal,
  });
}

/** Bộ lọc của màn hình, đọc từ URL (`?type=&productId=&variantId=&from=&to=`). */
export interface StockLedgerFilters {
  actorId?: Uuid;
  type?: StockDocumentType;
  /**
   * Sản phẩm đang chọn ở ô lọc; một mình nó không lọc gì (API chỉ lọc theo Biến thể): Sản phẩm nhiều Biến thể mà
   * chưa chọn Biến thể thì chưa tính là bộ lọc.
   */
  productId?: Uuid;
  variantId?: Uuid;
  /** Có thì luôn có cả `to`, và `from ≤ to`. */
  from?: IsoDate;
  to?: IsoDate;
}

export const STOCK_LEDGER_FILTER_KEYS = ['actorId', 'type', 'productId', 'variantId', 'from', 'to'] as const;

export const LEDGER_RANGE_TOO_LONG = `Khoảng ngày tối đa ${MAX_RANGE_DAYS} ngày. Hãy chọn khoảng ngắn hơn.`;

const ISO_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

/** Ngày `yyyy-MM-dd` có thật → số ngày kể từ 1970-01-01; sai thì `null`. */
function dayNumber(value: string): number | null {
  const match = ISO_DATE_PATTERN.exec(value);
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return date.getTime() / DAY_MS;
}

/**
 * Kiểm khoảng ngày như backend (`StockLedgerQuery`): `from ≤ to`, tối đa `MAX_RANGE_DAYS` (366) ngày tính cả hai đầu.
 * Đúng thì `undefined`, sai thì câu báo lỗi.
 */
export function ledgerRangeError(from: IsoDate, to: IsoDate): string | undefined {
  const [start, end] = [dayNumber(from), dayNumber(to)];
  if (start === null || end === null || start > end) return 'Khoảng ngày không hợp lệ.';
  return countDaysInclusive({ fromDate: from, toDate: to }) > MAX_RANGE_DAYS ? LEDGER_RANGE_TOO_LONG : undefined;
}

function uuid(raw: string | null): Uuid | undefined {
  const value = raw?.trim().toLowerCase();
  return value && UUID_PATTERN.test(value) ? value : undefined;
}

export function stockLedgerFiltersFromSearchParams(params: URLSearchParams): StockLedgerFilters {
  const filters: StockLedgerFilters = {};
  const actorId = uuid(params.get('actorId'));
  if (ledgerActorFilterEnabled() && actorId) filters.actorId = actorId;
  const type = params.get('type')?.trim();
  if (type && isStockDocumentType(type)) filters.type = type;
  const productId = uuid(params.get('productId'));
  if (productId) filters.productId = productId;
  const variantId = uuid(params.get('variantId'));
  if (variantId) filters.variantId = variantId;
  const from = params.get('from')?.trim();
  const to = params.get('to')?.trim();
  if (from && to) {
    const [start, end] = [dayNumber(from), dayNumber(to)];
    // Ngày sai, khoảng ngược: bỏ qua. Khoảng quá dài: giữ để báo dưới ô ngày (`ledgerRangeError`).
    if (start !== null && end !== null && start <= end) {
      filters.from = from;
      filters.to = to;
    }
  }
  return filters;
}

/** URL query mới: giữ tham số khác, thay các khóa của bộ lọc, bỏ khóa trống. */
export function withStockLedgerFilters(params: URLSearchParams, filters: StockLedgerFilters): URLSearchParams {
  const next = new URLSearchParams(params);
  for (const key of STOCK_LEDGER_FILTER_KEYS) {
    const value = filters[key];
    if (value === undefined || value === '') next.delete(key);
    else next.set(key, value);
  }
  return next;
}

/** Có bộ lọc nào đang làm bảng ít dòng đi không (`productId` một mình chưa lọc gì nên không tính). */
export function hasStockLedgerFilters(filters: StockLedgerFilters): boolean {
  return Boolean(filters.actorId || filters.type || filters.variantId || (filters.from && filters.to));
}

/** Tham số gửi API từ bộ lọc trên URL. Khoảng ngày sai thì không có ở đây: màn hình không gửi yêu cầu. */
export function toStockLedgerSearchParams(filters: StockLedgerFilters): StockLedgerSearchParams {
  const params: StockLedgerSearchParams = {};
  if (ledgerActorFilterEnabled() && filters.actorId) params.actorId = filters.actorId;
  if (filters.variantId) params.variantId = filters.variantId;
  if (filters.type) params.documentType = filters.type;
  if (filters.from && filters.to) {
    params.fromDate = filters.from;
    params.toDate = filters.to;
  }
  return params;
}
