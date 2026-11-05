import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  IDEMPOTENCY_KEY_HEADER,
  NETWORK_ERROR_TYPE,
  REQUEST_ID_HEADER,
  api,
  apiRequest,
  buildUrl,
  configureApiClient,
  isRetryableError,
  newIdempotencyKey,
  parseProblem,
  shouldReuseIdempotencyKey,
  withIdempotencyKey,
} from './client';
import { uuidFromRandomBytes } from './uuid';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init.headers as Record<string, string> | undefined) },
  });
}

function problemResponse(problem: Record<string, unknown>, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(problem), {
    status,
    headers: { 'Content-Type': 'application/problem+json', ...headers },
  });
}

const fetchMock = vi.fn<typeof fetch>();

function lastCall(): { url: string; init: RequestInit; headers: Headers } {
  const call = fetchMock.mock.calls.at(-1);
  if (!call) throw new Error('fetch chưa được gọi');
  const [input, init = {}] = call;
  return { url: String(input), init, headers: new Headers(init.headers) };
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  configureApiClient({ onUnauthorized: null });
});

describe('headers và body', () => {
  it('gửi cookie cùng origin, X-Request-Id mới cho mỗi request, Accept JSON', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ ok: true }));

    await api.get('/api/v1/platform/me');
    const first = lastCall();
    await api.get('/api/v1/platform/me');
    const second = lastCall();

    expect(first.init.credentials).toBe('same-origin');
    expect(first.init.method).toBe('GET');
    expect(first.headers.get('Accept')).toContain('application/problem+json');
    expect(first.headers.get(REQUEST_ID_HEADER)).toMatch(UUID_V4);
    expect(second.headers.get(REQUEST_ID_HEADER)).toMatch(UUID_V4);
    expect(second.headers.get(REQUEST_ID_HEADER)).not.toBe(first.headers.get(REQUEST_ID_HEADER));
  });

  it('gửi body JSON và đọc phản hồi JSON', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ total: 1234000 }));

    const data = await api.post<{ total: number }>('/api/v1/sales/orders/quote', { lines: [{ variantId: 'v1', quantity: 2 }] });

    const { init, headers } = lastCall();
    expect(init.method).toBe('POST');
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(JSON.parse(String(init.body))).toEqual({ lines: [{ variantId: 'v1', quantity: 2 }] });
    expect(data).toEqual({ total: 1234000 });
  });

  it('không gửi Content-Type khi không có body; 204 trả undefined', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    const data = await api.post('/api/v1/platform/auth/logout');

    expect(lastCall().headers.has('Content-Type')).toBe(false);
    expect(data).toBeUndefined();
  });

  it('gắn Idempotency-Key và đọc Idempotent-Replay', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ orderCode: 'DH-000001' }, { headers: { 'Idempotent-Replay': 'true' } }));
    const key = newIdempotencyKey();

    const response = await apiRequest('/api/v1/sales/orders', withIdempotencyKey(key, { method: 'POST', body: {} }));

    expect(key).toMatch(UUID_V4);
    expect(lastCall().headers.get(IDEMPOTENCY_KEY_HEADER)).toBe(key);
    expect(response.idempotentReplay).toBe(true);
  });

  it('không gửi Idempotency-Key khi không yêu cầu', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));
    await api.get('/api/v1/sales/orders');
    expect(lastCall().headers.has(IDEMPOTENCY_KEY_HEADER)).toBe(false);
  });

  it('dựng query string, bỏ null/undefined, mảng thành nhiều tham số', () => {
    expect(buildUrl('/api/v1/sales/sellable', { ids: ['a', 'b'], q: undefined, page: 0, sort: null })).toBe(
      '/api/v1/sales/sellable?ids=a&ids=b&page=0',
    );
    expect(buildUrl('/ai/search', { q: 'dầu gội trị gàu' })).toBe('/ai/search?q=d%E1%BA%A7u+g%E1%BB%99i+tr%E1%BB%8B+g%C3%A0u');
    expect(buildUrl('/x', {})).toBe('/x');
  });
});

describe('Problem Details (RFC 9457)', () => {
  it('đọc type, title, status, detail, errors[], requestId thành ApiError', async () => {
    fetchMock.mockResolvedValue(
      problemResponse(
        {
          type: '/errors/common/validation',
          title: 'Dữ liệu không hợp lệ',
          status: 400,
          detail: 'Vui lòng kiểm tra lại các trường được đánh dấu.',
          instance: '/api/v1/catalog/customers',
          requestId: 'req-from-body',
          errors: [{ field: 'phone', message: 'không được để trống' }, { field: 'name', message: 'quá dài', code: 'size' }],
          retryAfter: 5,
        },
        400,
      ),
    );

    const error = await api.post('/api/v1/catalog/customers', {}).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.type).toBe('/errors/common/validation');
    expect(apiError.title).toBe('Dữ liệu không hợp lệ');
    expect(apiError.status).toBe(400);
    expect(apiError.detail).toBe('Vui lòng kiểm tra lại các trường được đánh dấu.');
    expect(apiError.instance).toBe('/api/v1/catalog/customers');
    expect(apiError.requestId).toBe('req-from-body');
    expect(apiError.errors).toEqual([
      { field: 'phone', message: 'không được để trống' },
      { field: 'name', message: 'quá dài', code: 'size' },
    ]);
    expect(apiError.extensions).toEqual({ retryAfter: 5 });
  });

  it('lấy requestId từ header X-Request-Id khi body không có', async () => {
    fetchMock.mockResolvedValue(
      problemResponse({ type: '/errors/sales/cart-stale', title: 'Giỏ đã cũ', status: 409 }, 409, {
        [REQUEST_ID_HEADER]: 'req-from-nginx',
      }),
    );

    const error = (await api.post('/api/v1/sales/orders', {}).catch((e: unknown) => e)) as ApiError;

    expect(error.type).toBe('/errors/sales/cart-stale');
    expect(error.status).toBe(409);
    expect(error.requestId).toBe('req-from-nginx');
    expect(error.errors).toEqual([]);
  });

  it('body không phải JSON (502 HTML): type about:blank, giữ mã HTTP và requestId đã gửi', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' }));

    const error = (await api.get('/api/v1/platform/me').catch((e: unknown) => e)) as ApiError;

    expect(error.type).toBe('about:blank');
    expect(error.status).toBe(502);
    expect(error.title).toBe('Bad Gateway');
    expect(error.requestId).toBe(lastCall().headers.get(REQUEST_ID_HEADER));
  });

  it('parseProblem chấp nhận body thiếu trường', () => {
    const error = parseProblem(null, 404, '', 'rid');
    expect(error).toMatchObject({ type: 'about:blank', title: 'HTTP 404', status: 404, requestId: 'rid', errors: [] });
  });

  it('mất mạng: ApiError status 0, type lỗi mạng của frontend', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = (await api.get('/api/v1/platform/me').catch((e: unknown) => e)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.isNetworkError).toBe(true);
    expect(error.type).toBe(NETWORK_ERROR_TYPE);
    expect(error.requestId).toMatch(UUID_V4);
  });

  it('request bị hủy: ném lại lỗi hủy, không đổi thành lỗi mạng', async () => {
    const controller = new AbortController();
    controller.abort();
    fetchMock.mockRejectedValue(new DOMException('aborted', 'AbortError'));

    const error = await api.get('/ai/search', { signal: controller.signal }).catch((e: unknown) => e);

    expect(error).not.toBeInstanceOf(ApiError);
    expect((error as DOMException).name).toBe('AbortError');
  });
});

describe('xử lý 401 chung', () => {
  it('401 gọi handler đã đăng ký rồi ném ApiError', async () => {
    const onUnauthorized = vi.fn<() => void>();
    configureApiClient({ onUnauthorized });
    fetchMock.mockResolvedValue(
      problemResponse({ type: '/errors/common/unauthorized', title: 'Chưa đăng nhập', status: 401 }, 401),
    );

    const error = (await api.get('/api/v1/sales/orders').catch((e: unknown) => e)) as ApiError;

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(error.status).toBe(401);
  });

  it('request đăng nhập (authRedirect: false) không gọi handler', async () => {
    const onUnauthorized = vi.fn<() => void>();
    configureApiClient({ onUnauthorized });
    fetchMock.mockResolvedValue(
      problemResponse({ type: '/errors/platform/invalid-credentials', title: 'Đăng nhập không thành công', status: 401 }, 401),
    );

    const error = (await api
      .post('/api/v1/platform/auth/login', { loginKey: 'a', password: 'b' }, { authRedirect: false })
      .catch((e: unknown) => e)) as ApiError;

    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(error.type).toBe('/errors/platform/invalid-credentials');
  });

  it('403 không gọi handler 401', async () => {
    const onUnauthorized = vi.fn<() => void>();
    configureApiClient({ onUnauthorized });
    fetchMock.mockResolvedValue(problemResponse({ type: '/errors/common/forbidden', title: 'Không có quyền', status: 403 }, 403));

    await api.get('/api/v1/reporting/q/Q3').catch(() => undefined);

    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});

describe('quy tắc thử lại và Idempotency-Key (AD-7)', () => {
  const make = (status: number) => new ApiError({ type: 'about:blank', title: '', status, requestId: 'r' });

  it('chỉ giữ khóa cũ khi lỗi mạng hoặc 5xx', () => {
    expect(shouldReuseIdempotencyKey(make(0))).toBe(true);
    expect(shouldReuseIdempotencyKey(make(500))).toBe(true);
    expect(shouldReuseIdempotencyKey(make(503))).toBe(true);
    expect(shouldReuseIdempotencyKey(make(409))).toBe(false);
    expect(shouldReuseIdempotencyKey(make(400))).toBe(false);
    expect(shouldReuseIdempotencyKey(new Error('x'))).toBe(false);
  });

  it('TanStack Query chỉ thử lại lỗi mạng và 5xx tạm thời', () => {
    expect(isRetryableError(make(0))).toBe(true);
    expect(isRetryableError(make(502))).toBe(true);
    expect(isRetryableError(make(503))).toBe(false);
    expect(isRetryableError(make(401))).toBe(false);
    expect(isRetryableError(make(404))).toBe(false);
  });
});

describe('UUID', () => {
  it('dựng UUID v4 từ byte ngẫu nhiên khi không có crypto.randomUUID (HTTP qua LAN)', () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, i) => i * 17);
    expect(uuidFromRandomBytes(bytes)).toMatch(UUID_V4);
    expect(() => uuidFromRandomBytes(new Uint8Array(4))).toThrow(RangeError);
  });
});
