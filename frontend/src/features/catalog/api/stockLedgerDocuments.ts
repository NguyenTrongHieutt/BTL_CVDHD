import { api } from '../../../shared/api/client';
import type { IsoDateTime, Money, Uuid } from '../../../shared/types/common';
import type { StockDocumentType } from '../../../shared/types/inventory';
import { UUID_PATTERN } from '../components/productFilterParams';
import { isStockDocumentType, STOCK_LEDGER_PATH } from './stockLedger';

export interface LedgerDocumentReference {
  type: StockDocumentType;
  id: Uuid;
}

export interface LedgerDocumentLine {
  lineNo: number;
  productName: string | null;
  variantName: string | null;
  sku?: string | null;
  quantity: number;
  unitAmount?: Money | null;
  amount?: Money | null;
  systemQuantity?: number | null;
  delta?: number | null;
  reason?: string | null;
}

export interface LedgerDocument {
  documentType: StockDocumentType;
  documentId: Uuid;
  recordedAt: IsoDateTime;
  code?: string | null;
  actorName?: string | null;
  supplierName?: string | null;
  customerName?: string | null;
  status?: string | null;
  note?: string | null;
  orderId?: Uuid | null;
  relatedOrderCode?: string | null;
  paymentMethod?: string | null;
  subtotal?: Money | null;
  discount?: Money | null;
  totalAmount?: Money | null;
  lines: LedgerDocumentLine[];
}

export const ledgerDocumentTitles: Readonly<Record<StockDocumentType, string>> = {
  OPENING: 'Tồn đầu kỳ',
  PURCHASE_RECEIPT: 'Phiếu nhập',
  STOCKTAKE: 'Phiếu kiểm kho',
  SALE: 'Đơn bán',
  SALE_CANCEL: 'Chi tiết hủy đơn',
  SALE_RETURN: 'Phiếu trả hàng',
};

export function readLedgerDocument(params: URLSearchParams): LedgerDocumentReference | undefined {
  const type = params.get('sourceType');
  const id = params.get('sourceId');
  return type && isStockDocumentType(type) && id && UUID_PATTERN.test(id) ? { type, id } : undefined;
}

export function withLedgerDocument(params: URLSearchParams, reference?: LedgerDocumentReference): URLSearchParams {
  const next = new URLSearchParams(params);
  if (reference) {
    next.set('sourceType', reference.type);
    next.set('sourceId', reference.id);
  } else {
    next.delete('sourceType');
    next.delete('sourceId');
  }
  return next;
}

export function fetchLedgerDocument(reference: LedgerDocumentReference, signal: AbortSignal): Promise<LedgerDocument> {
  const { type, id } = reference;
  const route = type === 'SALE' || type === 'SALE_CANCEL' || type === 'SALE_RETURN'
    ? '/api/v1/sales/ledger-documents'
    : STOCK_LEDGER_PATH + '/documents';
  return api.get<LedgerDocument>(`${route}/${type}/${encodeURIComponent(id)}`, { signal });
}
