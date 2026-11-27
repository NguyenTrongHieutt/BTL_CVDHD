import { useQuery } from '@tanstack/react-query';
import { api, newIdempotencyKey } from './client';

const uploads = '/api/v1/platform/import-uploads';
export type ImportKind = 'PRODUCTS' | 'PURCHASE_RECEIPT';
export interface ImportCapabilities {
  enabled: boolean;
  maxFileBytes: number;
  partSizeBytes: number;
  maxParallelParts: number;
}
export interface UploadSession {
  uploadId: string;
  kind: ImportKind;
  fileName: string;
  sizeBytes: number;
  partSizeBytes: number;
  partCount: number;
  expiresAt: string;
  status: 'CREATING' | 'OPEN' | 'READY' | 'RELEASING' | 'RELEASED' | 'ABORTING' | 'CANCELLED';
}
interface SignedPart { partNumber: number; url: string; headers: Record<string, string> }
interface PartStatus {
  uploaded: { partNumber: number; sizeBytes: number; etag: string }[];
  missing: number[];
}
export interface ImportSummary {
  totalRows: number;
  validProducts?: number;
  validRows?: number;
  existingRows?: number;
  newProducts?: number;
  newVariants?: number;
  totalAmount?: number | null;
  errorCount: number;
}
export interface ImportJob<T> {
  jobId: string;
  supplierId: string | null;
  note: string | null;
  revision: number;
  state: 'QUEUED' | 'READY' | 'SAVED' | 'FAILED';
  summary: ImportSummary | null;
  result: T | null;
  statusUrl: string | null;
  saveStatusUrl: string | null;
  expiresAt: string;
}
export interface LargeImportRow {
  row: number;
  sku?: string | null;
  productName?: string | null;
  variantName?: string | null;
  parentCode?: string | null;
  action?: string;
  quantity?: number | null;
  unitCost?: number | null;
  valid: boolean;
  values?: Record<string, string>;
}
export interface LargeImportError { row: number; column: string | null; message: string }
export interface ImportRowsPage {
  items: LargeImportRow[];
  errors: LargeImportError[];
  total: number;
  page: number;
  size: number;
}
export interface AcceptedJob {
  jobId: string;
  operationId: string;
  statusUrl: string;
  state: 'QUEUED' | 'SAVING';
}
export interface CommandState {
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  problem?: { title?: string; detail?: string; type?: string; status?: number };
}
export type ImportEdit = { row: number; values: Record<string, string> };

export function endpoint(kind: ImportKind): string {
  return kind === 'PRODUCTS'
    ? '/api/v1/catalog/products/import-jobs'
    : '/api/v1/inventory/purchase-receipts/import-jobs';
}

export const importUploadApi = {
  capabilities: () => api.get<ImportCapabilities>(uploads + '/capabilities'),
  list: (kind: ImportKind) => api.get<UploadSession[]>(uploads, { query: { kind } }),
  create: (kind: ImportKind, file: File, key = newIdempotencyKey()) => api.post<UploadSession>(uploads,
    { kind, fileName: file.name, sizeBytes: file.size }, { idempotencyKey: key }),
  session: (id: string) => api.get<UploadSession>(uploads + '/' + id),
  cancel: (id: string) => api.delete<void>(uploads + '/' + id),
  start: (kind: ImportKind, id: string, options?: { supplierId: string; note: string }) =>
    api.post<AcceptedJob>(endpoint(kind), { uploadId: id, ...options }),
  job: <T>(kind: ImportKind, id: string) => api.get<ImportJob<T>>(endpoint(kind) + '/' + id),
  rows: (kind: ImportKind, id: string, page: number, size: number, errorsOnly: boolean) =>
    api.get<ImportRowsPage>(endpoint(kind) + '/' + id + '/rows', { query: { page, size, errorsOnly } }),
  confirm: (kind: ImportKind, id: string, revision: number) =>
    api.post<AcceptedJob>(endpoint(kind) + '/' + id + '/confirm', { revision }),
  recheck: (kind: ImportKind, id: string, revision: number, edits: ImportEdit[]) => kind === 'PRODUCTS'
    ? api.post<AcceptedJob>(endpoint(kind) + '/' + id + '/recheck', { revision })
    : api.patch<AcceptedJob>(endpoint(kind) + '/' + id + '/rows', { revision, edits }),
  command: (statusUrl: string) => {
    if (!/^\/api\/v1\/platform\/write-requests\/[0-9a-f-]{36}$/i.test(statusUrl)) {
      throw new Error('Mã theo dõi công việc không hợp lệ.');
    }
    return api.get<CommandState>(statusUrl);
  },
};

export function useImportCapabilities() {
  return useQuery({
    queryKey: ['import-uploads', 'capabilities'],
    queryFn: importUploadApi.capabilities,
    staleTime: 60_000,
    retry: false,
  });
}

type Digest = { partNumber: number; sha256: string };
type ManifestEvent = { parts: Digest[] } | { error: string };

function manifestFor(file: File, partSize: number, signal: AbortSignal): Promise<Digest[]> {
  return new Promise((resolve, reject) => {
    signal.throwIfAborted();
    const worker = new Worker(new URL('./multipartHash.worker.ts', import.meta.url), { type: 'module' });
    function cleanup() {
      signal.removeEventListener('abort', aborted);
      worker.terminate();
    }
    function aborted() { cleanup(); reject(signal.reason); }
    worker.onmessage = ({ data }: MessageEvent<ManifestEvent>) => {
      cleanup();
      if ('error' in data) reject(new Error(data.error));
      else resolve(data.parts);
    };
    worker.onerror = () => { cleanup(); reject(new Error('Không tính được mã kiểm tra của file.')); };
    signal.addEventListener('abort', aborted, { once: true });
    worker.postMessage({ file, partSize });
  });
}

export function partBlob(file: Blob, partSize: number, number: number): Blob {
  return file.slice((number - 1) * partSize, Math.min(number * partSize, file.size));
}

export async function sendMissingParts(session: UploadSession, file: File, parallel: number, signal: AbortSignal,
  progress: (bytes: number, total: number) => void, phase: (value: 'checking' | 'uploading' | 'completing') => void) {
  if (file.size !== session.sizeBytes) throw new Error('Chọn lại đúng file gốc có cùng dung lượng.');
  const url = uploads + '/' + session.uploadId;
  phase('checking');
  const parts = await manifestFor(file, session.partSizeBytes, signal);
  await api.put(url + '/manifest', { parts }, { signal });
  const current = await api.get<PartStatus>(url + '/parts', { signal });
  let bytes = current.uploaded.reduce((sum, part) => sum + part.sizeBytes, 0);
  progress(bytes, file.size);
  phase('uploading');
  const remaining = [...current.missing];
  const uploadPart = async (number: number) => {
    const signed = await api.post<{ parts: SignedPart[] }>(url + '/parts/urls', { partNumbers: [number] }, { signal });
    const information = signed.parts.find(part => part.partNumber === number);
    if (!information) throw new Error('Kho chưa cấp đường dẫn cho phần ' + number + '.');
    const target = new URL(information.url);
    if (target.protocol !== 'https:' && !['127.0.0.1', 'localhost'].includes(target.hostname)) {
      throw new Error('Đường dẫn kho file phải dùng HTTPS.');
    }
    const body = partBlob(file, session.partSizeBytes, number);
    // URL do kho ký cần giữ nguyên URL, body và header. Client API sẽ thêm header xác thực của ứng dụng.
    // eslint-disable-next-line no-restricted-globals
    const response = await fetch(target, {
      method: 'PUT', headers: information.headers, body, signal, credentials: 'omit', redirect: 'error',
    });
    const etag = response.headers.get('ETag');
    if (!response.ok || !etag) throw new Error('Phần ' + number + ' chưa tải được. Bấm Tiếp tục tải để thử lại.');
    await api.put(url + '/parts/' + number, { etag }, { signal });
    bytes += body.size;
    progress(bytes, file.size);
  };
  let stopped = false;
  let failure: unknown;
  await Promise.all(Array.from({ length: Math.min(Math.max(1, parallel), remaining.length) }, async () => {
    while (!stopped && remaining.length) {
      const number = remaining.shift()!;
      try {
        await uploadPart(number);
      } catch (error) {
        stopped = true;
        failure = error;
      }
    }
  }));
  if (stopped) throw failure;
  phase('completing');
  return api.post<UploadSession>(url + '/complete', undefined, { signal });
}
