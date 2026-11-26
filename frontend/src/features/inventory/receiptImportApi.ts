import type { QueryClient } from '@tanstack/react-query';
import { api } from '../../shared/api/client';
import type { IsoDateTime, Uuid } from '../../shared/types/common';

export const RECEIPTS_PATH = '/api/v1/inventory/purchase-receipts';
export const RECEIPT_IMPORT_MAX_BYTES = 2 * 1024 * 1024;
export const RECEIPT_IMPORT_MAX_ROWS = 1000;

export const receiptImportLabels = {
  sku: 'SKU',
  parentCode: 'Mã sản phẩm cha',
  productName: 'Tên Sản phẩm',
  variantName: 'Tên Biến thể',
  quantity: 'Số lượng',
  unitCost: 'Giá nhập',
  salePrice: 'Giá bán',
  barcode: 'Mã vạch',
  categoryName: 'Danh mục',
  unit: 'Đơn vị tính',
  description: 'Mô tả',
  attributeName1: 'Tên thuộc tính 1',
  attributeValue1: 'Giá trị thuộc tính 1',
  attributeName2: 'Tên thuộc tính 2',
  attributeValue2: 'Giá trị thuộc tính 2',
  attributeName3: 'Tên thuộc tính 3',
  attributeValue3: 'Giá trị thuộc tính 3',
} as const;

export type ReceiptImportField = keyof typeof receiptImportLabels;
export type ReceiptImportValues = Partial<Record<ReceiptImportField, string | null>>;
export type ReceiptImportFormat = 'xlsx' | 'csv';
type ReceiptImportAction = 'EXISTING' | 'NEW_PRODUCT' | 'NEW_VARIANT';

export interface Receipt {
  receiptId: Uuid;
  code: string;
  receivedAt: IsoDateTime;
  totalAmount: number;
}

export interface ReceiptImportRow {
  row: number;
  sku: string | null;
  parentCode: string | null;
  productName: string | null;
  variantName: string | null;
  quantity: number | null;
  unitCost: number | null;
  action: ReceiptImportAction;
  values: ReceiptImportValues;
  valid: boolean;
}

export interface ReceiptImportError {
  row: number;
  column: string | null;
  message: string;
}

export interface ReceiptImportPreview {
  previewToken: string;
  rows: ReceiptImportRow[];
  errors: ReceiptImportError[];
  existingRows: number;
  newProducts: number;
  newVariants: number;
  totalAmount: number | null;
}

export interface ReceiptImportEdit {
  row: number;
  values: ReceiptImportValues;
}

export interface ReceiptImportInput {
  file: File;
  supplierId: Uuid;
  note: string;
  edits: ReceiptImportEdit[];
}

export interface ReceiptImportCommit extends ReceiptImportInput {
  key: string;
  previewToken: string;
}

function importForm({ file, supplierId, note, edits }: ReceiptImportInput) {
  const form = new FormData();
  form.append('file', file);
  form.append('supplierId', supplierId);
  form.append('note', note);
  if (edits.length) form.append('edits', JSON.stringify(edits));
  return form;
}

export function fetchReceiptImportTemplate(format: ReceiptImportFormat) {
  return api.get<Blob>(`${RECEIPTS_PATH}/import-template`, { query: { format }, responseType: 'blob' });
}

export function previewReceiptImport(input: ReceiptImportInput) {
  return api.post<ReceiptImportPreview>(`${RECEIPTS_PATH}/import/preview`, importForm(input));
}

export function saveReceiptImport(input: ReceiptImportCommit) {
  return api.post<Receipt>(`${RECEIPTS_PATH}/import`, importForm(input), {
    idempotencyKey: input.key,
    headers: { 'X-Import-Preview': input.previewToken },
  });
}

export function invalidateReceiptQueries(client: QueryClient, fromFile = false) {
  const keys = [
    ['inventory', 'stock'], ['inventory', 'low-stock'], ['inventory', 'ledger'], ['sales', 'sellable'],
    ...(fromFile ? [['catalog', 'products'], ['catalog', 'categories']] : []),
  ];
  return Promise.all(keys.map(queryKey => client.invalidateQueries({ queryKey })));
}
