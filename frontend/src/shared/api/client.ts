/**
 * Client API duy nhất của frontend (AD-20). Người giữ: Anh.
 *
 * - `fetch` cùng origin, gửi kèm cookie `access_token` (HttpOnly, AD-9). Vite proxy và nginx
 *   chuyển `/api/` sang backend, `/ai/` sang ai-service.
 * - Mỗi request mang một `X-Request-Id` mới (UUID) để lần theo log qua các khối.
 * - Gửi và nhận JSON. Lỗi dạng RFC 9457 Problem Details được đọc thành `ApiError` (AD-19).
 * - Body là `FormData` (tải tệp, ví dụ ảnh Sản phẩm) thì gửi nguyên dạng `multipart/form-data`.
 * - Gặp `401` thì gọi handler chung (app đăng ký: xóa cache, chuyển về `/login`).
 * - POST tạo hoặc chuyển trạng thái chứng từ phải truyền `idempotencyKey` (AD-7).
 *
 * Feature không gọi `fetch` trực tiếp; lint chặn điều này.
 */
import { randomUuid } from './uuid';

export const REQUEST_ID_HEADER = 'X-Request-Id';
export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key';
export const IDEMPOTENT_REPLAY_HEADER = 'Idempotent-Replay';

/** Mã lỗi chỉ sinh ở frontend khi không gọi được máy chủ. Không phải mã của backend. */
export const NETWORK_ERROR_TYPE = '/errors/client/network';
/** Mã lỗi chỉ sinh ở frontend khi phản hồi 2xx không đọc được thành JSON. */
export const INVALID_RESPONSE_TYPE = '/errors/client/invalid-response';
/** Mã lỗi khi thao tác ghi nền vẫn chưa kết thúc sau thời gian chờ hữu hạn. */
export const WRITE_STATUS_PENDING_TYPE = '/errors/client/write-status-pending';
/** Mã lỗi khi API trạng thái thao tác ghi trả dữ liệu không đúng hợp đồng. */
export const INVALID_WRITE_STATUS_TYPE = '/errors/client/invalid-write-status';

/** Một lỗi dữ liệu nhập trong `errors[]` (backend và ai-service đều dùng `field` và `message`). */
export interface FieldError {
  field: string;
  message: string;
  code?: string;
}

interface ApiErrorInit {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  errors?: FieldError[];
  requestId: string;
  extensions?: Record<string, unknown>;
  cause?: unknown;
}

/** Lỗi API đã chuẩn hóa từ Problem Details. `status` = 0 khi không gọi được máy chủ. */
export class ApiError extends Error {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly detail: string | undefined;
  readonly instance: string | undefined;
  readonly errors: FieldError[];
  readonly requestId: string;
  /** Các trường mở rộng khác của Problem Details. */
  readonly extensions: Record<string, unknown>;

  constructor(init: ApiErrorInit) {
    super(init.detail ?? init.title, init.cause === undefined ? undefined : { cause: init.cause });
    this.name = 'ApiError';
    this.type = init.type;
    this.title = init.title;
    this.status = init.status;
    this.detail = init.detail;
    this.instance = init.instance;
    this.errors = init.errors ?? [];
    this.requestId = init.requestId;
    this.extensions = init.extensions ?? {};
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    (error.name === 'AbortError' || error.name === 'TimeoutError')
  );
}

/** Lỗi đáng thử lại: mất mạng hoặc 5xx (trừ 501 chưa làm và 503 dịch vụ AI tắt). */
export function isRetryableError(error: unknown): boolean {
  if (!isApiError(error)) return false;
  if (error.status === 0) return true;
  return error.status >= 500 && error.status !== 501 && error.status !== 503;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function toFieldErrors(value: unknown): FieldError[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord).map((item) => {
    const code = asString(item.code);
    return {
      field: asString(item.field) ?? asString(item.pointer) ?? '',
      message: asString(item.message) ?? asString(item.detail) ?? '',
      ...(code === undefined ? {} : { code }),
    };
  });
}

const PROBLEM_MEMBERS = new Set(['type', 'title', 'status', 'detail', 'instance', 'requestId', 'errors']);

/**
 * Đọc body lỗi thành `ApiError`. Body không phải Problem Details (ví dụ trang HTML 502)
 * thì dùng mã `about:blank` và trạng thái HTTP.
 */
export function parseProblem(body: unknown, status: number, statusText: string, requestId: string): ApiError {
  const problem = isRecord(body) ? body : {};
  const extensions: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(problem)) {
    if (!PROBLEM_MEMBERS.has(key)) extensions[key] = value;
  }
  return new ApiError({
    type: asString(problem.type) ?? 'about:blank',
    title: asString(problem.title) ?? (statusText || `HTTP ${status}`),
    // Trạng thái HTTP là nguồn đúng; trường `status` trong body chỉ để tham khảo (RFC 9457).
    status,
    detail: asString(problem.detail),
    instance: asString(problem.instance),
    errors: toFieldErrors(problem.errors),
    requestId: asString(problem.requestId) ?? requestId,
    extensions,
  });
}

type QueryValue = string | number | boolean | null | undefined | readonly (string | number | boolean)[];
export type QueryParams = Readonly<Record<string, QueryValue>>;

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  /** Tham số query. Bỏ qua `null`/`undefined`; mảng thành nhiều tham số cùng tên. */
  query?: QueryParams;
  /** Body JSON; `FormData` thì gửi nguyên dạng `multipart/form-data` (trình duyệt tự đặt `Content-Type` kèm boundary). */
  body?: unknown;
  headers?: Readonly<Record<string, string>>;
  signal?: AbortSignal;
  /** Header `Idempotency-Key` (AD-7). Tạo bằng `newIdempotencyKey()`. */
  idempotencyKey?: string;
  /** `false`: không chạy xử lý 401 chung. Chỉ dùng cho request đăng nhập, nơi 401 là "sai mật khẩu". */
  authRedirect?: boolean;
  /** Cách đọc body thành công. Mặc định `json`; `blob` cho tải file (FR-34). */
  responseType?: 'json' | 'blob' | 'text';
}

export interface ApiResponse<T> {
  data: T;
  status: number;
  headers: Headers;
  requestId: string;
  /** `true` khi backend trả lại chứng từ cũ vì trùng `Idempotency-Key` (AD-7). */
  idempotentReplay: boolean;
}

export interface ApiClientConfig {
  /** Gọi khi một request nhận 401. App đăng ký: xóa cache TanStack Query và chuyển về `/login` (AD-9). */
  onUnauthorized?: (() => void) | null;
}

let onUnauthorized: (() => void) | null = null;
let preferPrimaryUntil = 0;
const COMMITTED_WRITE_PREFIX = /^\/api\/v1\/(catalog|inventory|sales)\//u;
const PREVIEW_SUFFIX = /\/(quote|preview)(?:[/?]|$)/u;
const READ_AFTER_WRITE_MS = 6000;
const OPERATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const WRITE_STATUS_PREFIX = '/api/v1/platform/write-requests/';
const WRITE_STATUS_POLL_MS = 500;
const WRITE_STATUS_MAX_POLLS = 20;

export function configureApiClient(config: ApiClientConfig): void {
  if (config.onUnauthorized !== undefined) onUnauthorized = config.onUnauthorized;
}

/** Khóa mới cho header `Idempotency-Key` (AD-7). */
export function newIdempotencyKey(): string {
  return randomUuid();
}

/** Gắn `Idempotency-Key` vào tùy chọn request. */
export function withIdempotencyKey(key: string, options: RequestOptions = {}): RequestOptions {
  return { ...options, idempotencyKey: key };
}

/**
 * AD-7: sinh khóa mới sau mỗi lần thành công và khi xóa giỏ; chỉ giữ khóa cũ khi lỗi mạng
 * hoặc lỗi 5xx (lần gửi lại có thể trùng với lần trước đã được ghi).
 */
export function shouldReuseIdempotencyKey(error: unknown): boolean {
  return isApiError(error) && (error.status === 0 || error.status >= 500);
}

export function buildUrl(path: string, query?: QueryParams): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined) continue;
    if (Array.isArray(value)) {
      for (const item of value) params.append(key, String(item));
    } else {
      params.append(key, String(value));
    }
  }
  const search = params.toString();
  if (!search) return path;
  return `${path}${path.includes('?') ? '&' : '?'}${search}`;
}

async function readErrorBody(response: Response): Promise<unknown> {
  try {
    const text = await response.text();
    return text ? (JSON.parse(text) as unknown) : null;
  } catch {
    return null;
  }
}

async function readBody(response: Response, responseType: 'json' | 'blob' | 'text', requestId: string): Promise<unknown> {
  if (responseType === 'blob') return response.blob();
  const text = await response.text();
  if (responseType === 'text') return text;
  if (!text) return undefined;
  try {
    return JSON.parse(text) as unknown;
  } catch (cause) {
    throw new ApiError({
      type: INVALID_RESPONSE_TYPE,
      title: 'Phản hồi không phải JSON',
      status: response.status,
      requestId,
      cause,
    });
  }
}

interface AcceptedWrite {
  operationId: string;
  status: 'ACCEPTED';
  statusUrl: string;
}

interface WriteStatus {
  operationId: string;
  status: 'PENDING' | 'SUCCEEDED' | 'FAILED';
  result?: unknown;
  problem?: unknown;
  responseStatus?: number;
  responseHeaders?: Record<string, unknown>;
}

function acceptedWriteOf(body: unknown, responseStatus: number, method: HttpMethod): AcceptedWrite | null {
  if (method === 'GET' || responseStatus !== 202 || !isRecord(body)) return null;
  const operationId = asString(body.operationId);
  const statusUrl = asString(body.statusUrl);
  if (!operationId || !OPERATION_ID.test(operationId) || body.status !== 'ACCEPTED') return null;
  const expectedStatusUrl = `${WRITE_STATUS_PREFIX}${operationId}`;
  if (statusUrl !== expectedStatusUrl) return null;
  return { operationId, status: 'ACCEPTED', statusUrl };
}

function writeStatusOf(body: unknown, operationId: string): WriteStatus | null {
  if (!isRecord(body) || body.operationId !== operationId) return null;
  if (body.status !== 'PENDING' && body.status !== 'SUCCEEDED' && body.status !== 'FAILED') return null;
  const responseStatus = typeof body.responseStatus === 'number' ? body.responseStatus : undefined;
  const responseHeaders = isRecord(body.responseHeaders) ? body.responseHeaders : undefined;
  return {
    operationId,
    status: body.status,
    ...(Object.hasOwn(body, 'result') ? { result: body.result } : {}),
    ...(Object.hasOwn(body, 'problem') ? { problem: body.problem } : {}),
    ...(responseStatus === undefined ? {} : { responseStatus }),
    ...(responseHeaders === undefined ? {} : { responseHeaders }),
  };
}

function invalidWriteStatus(requestId: string): ApiError {
  return new ApiError({
    type: INVALID_WRITE_STATUS_TYPE,
    title: 'Không đọc được trạng thái thao tác',
    status: 502,
    detail: 'Máy chủ trả trạng thái xử lý không hợp lệ. Vui lòng thử lại cùng yêu cầu.',
    requestId,
  });
}

function waitForNextStatus(signal?: AbortSignal): Promise<void> {
  if (signal?.aborted) return Promise.reject(signal.reason ?? new DOMException('aborted', 'AbortError'));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(done, WRITE_STATUS_POLL_MS);
    function done() {
      signal?.removeEventListener('abort', aborted);
      resolve();
    }
    function aborted() {
      clearTimeout(timer);
      reject(signal?.reason ?? new DOMException('aborted', 'AbortError'));
    }
    signal?.addEventListener('abort', aborted, { once: true });
  });
}

function markCommittedWrite(path: string, method: HttpMethod): void {
  if (method !== 'GET' && COMMITTED_WRITE_PREFIX.test(path) && !PREVIEW_SUFFIX.test(path)) {
    preferPrimaryUntil = Date.now() + READ_AFTER_WRITE_MS;
  }
}

async function awaitQueuedWrite<T>(
  accepted: AcceptedWrite,
  originalPath: string,
  originalMethod: HttpMethod,
  options: RequestOptions,
): Promise<ApiResponse<T>> {
  let lastRequestId = accepted.operationId;
  for (let poll = 0; poll < WRITE_STATUS_MAX_POLLS; poll += 1) {
    const response = await apiRequest<unknown>(accepted.statusUrl, {
      method: 'GET',
      signal: options.signal,
      authRedirect: options.authRedirect,
    });
    lastRequestId = response.requestId;
    const write = writeStatusOf(response.data, accepted.operationId);
    if (!write) throw invalidWriteStatus(response.requestId);
    if (write.status === 'PENDING') {
      if (poll + 1 < WRITE_STATUS_MAX_POLLS) await waitForNextStatus(options.signal);
      continue;
    }

    const terminalStatus = write.responseStatus;
    if (terminalStatus === undefined || terminalStatus < 100 || terminalStatus > 599) {
      throw invalidWriteStatus(response.requestId);
    }
    if (write.status === 'FAILED') {
      throw parseProblem(write.problem, terminalStatus, '', response.requestId);
    }
    if (terminalStatus < 200 || terminalStatus >= 300) throw invalidWriteStatus(response.requestId);

    const headers = new Headers(response.headers);
    for (const [name, value] of Object.entries(write.responseHeaders ?? {})) {
      if (typeof value === 'string') headers.set(name, value);
    }
    markCommittedWrite(originalPath, originalMethod);
    return {
      data: write.result as T,
      status: terminalStatus,
      headers,
      requestId: response.requestId,
      idempotentReplay: headers.get(IDEMPOTENT_REPLAY_HEADER) === 'true',
    };
  }

  throw new ApiError({
    type: WRITE_STATUS_PENDING_TYPE,
    title: 'Thao tác vẫn đang được xử lý',
    status: 504,
    detail: 'Hệ thống xử lý nền chưa phản hồi kết quả. Vui lòng thử lại; yêu cầu cũ sẽ được tiếp tục, không tạo bản ghi trùng.',
    requestId: lastRequestId,
  });
}

/** Gửi một request và trả cả dữ liệu lẫn thông tin phản hồi. Phần lớn code chỉ cần `api.get/post/...`. */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const requestId = randomUuid();
  const headers = new Headers(options.headers);
  headers.set(REQUEST_ID_HEADER, requestId);
  headers.set('Accept', 'application/json, application/problem+json');
  if (options.idempotencyKey) headers.set(IDEMPOTENCY_KEY_HEADER, options.idempotencyKey);
  const method = options.method ?? (options.body === undefined ? 'GET' : 'POST');
  if (method === 'GET' && COMMITTED_WRITE_PREFIX.test(path) && Date.now() < preferPrimaryUntil) {
    headers.set('X-Read-Primary', 'true');
  }

  let body: string | FormData | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  } else if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      body,
      credentials: 'same-origin',
      signal: options.signal,
    });
  } catch (cause) {
    if (isAbortError(cause) || options.signal?.aborted) throw cause;
    throw new ApiError({
      type: NETWORK_ERROR_TYPE,
      title: 'Không kết nối được máy chủ',
      status: 0,
      requestId,
      cause,
    });
  }

  const responseRequestId = response.headers.get(REQUEST_ID_HEADER) ?? requestId;

  if (!response.ok) {
    const error = parseProblem(await readErrorBody(response), response.status, response.statusText, responseRequestId);
    if (error.status === 401 && options.authRedirect !== false) onUnauthorized?.();
    throw error;
  }

  const data = (await readBody(response, options.responseType ?? 'json', responseRequestId)) as T;
  const acceptedWrite = acceptedWriteOf(data, response.status, method);
  if (acceptedWrite) return awaitQueuedWrite<T>(acceptedWrite, path, method, options);
  if (response.status !== 202) markCommittedWrite(path, method);
  return {
    data,
    status: response.status,
    headers: response.headers,
    requestId: responseRequestId,
    idempotentReplay: response.headers.get(IDEMPOTENT_REPLAY_HEADER) === 'true',
  };
}

type BodyOptions = Omit<RequestOptions, 'method' | 'body'>;

/** Lối tắt trả thẳng dữ liệu. */
export const api = {
  get: <T>(path: string, options: Omit<RequestOptions, 'method' | 'body'> = {}) =>
    apiRequest<T>(path, { ...options, method: 'GET' }).then((r) => r.data),
  post: <T>(path: string, body?: unknown, options: BodyOptions = {}) =>
    apiRequest<T>(path, { ...options, method: 'POST', body }).then((r) => r.data),
  put: <T>(path: string, body?: unknown, options: BodyOptions = {}) =>
    apiRequest<T>(path, { ...options, method: 'PUT', body }).then((r) => r.data),
  patch: <T>(path: string, body?: unknown, options: BodyOptions = {}) =>
    apiRequest<T>(path, { ...options, method: 'PATCH', body }).then((r) => r.data),
  delete: <T>(path: string, options: BodyOptions = {}) =>
    apiRequest<T>(path, { ...options, method: 'DELETE' }).then((r) => r.data),
};

export type { PageRequest, PageResponse } from './pagination';
