import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AI_SEARCH_TIMEOUT_MS, searchProducts } from './aiSearch';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** fetch giả treo cho tới khi signal bị hủy, như khi ai-service chậm. */
function hangUntilAborted(_input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(init.signal?.reason ?? new DOMException('aborted', 'AbortError')));
  });
}

describe('searchProducts (AD-12)', () => {
  it('trả kết quả ngữ nghĩa từ /ai/search', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ items: [{ productId: 'p1', score: 0.9 }, { productId: 'p2', score: 0.7 }] }), {
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    await expect(searchProducts('  dầu gội trị gàu ')).resolves.toEqual({ mode: 'semantic', ids: ['p1', 'p2'] });
    const [url] = fetchMock.mock.calls[0];
    expect(String(url)).toMatch(/^\/ai\/search\?q=/);
  });

  it('ai-service lỗi (503) thì chuyển sang Tìm kiếm thường', async () => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ type: '/errors/ai/unavailable', title: 'Dịch vụ AI chưa sẵn sàng', status: 503 }), {
        status: 503,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );

    await expect(searchProducts('kem đánh răng')).resolves.toEqual({ mode: 'normal', ids: [] });
  });

  it('quá 1,5 giây thì hủy và chuyển sang Tìm kiếm thường', async () => {
    vi.useFakeTimers();
    // /ai/search treo; Tìm kiếm thường (/api/v1/sales/sellable) trả về ngay, có Biến thể trùng Sản phẩm
    fetchMock.mockImplementation((input, init) =>
      String(input).startsWith('/ai/search')
        ? hangUntilAborted(input, init)
        : Promise.resolve(
            new Response(
              JSON.stringify({ items: [{ productId: 'p9' }, { productId: 'p9' }, { productId: 'p3' }], total: 3, page: 0, size: 10 }),
              { status: 200, headers: { 'Content-Type': 'application/json' } },
            ),
          ),
    );

    const pending = searchProducts('bánh quy');
    await vi.advanceTimersByTimeAsync(AI_SEARCH_TIMEOUT_MS - 1);
    let settled = false;
    void pending.then(() => {
      settled = true;
    });
    await Promise.resolve();
    expect(settled).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await expect(pending).resolves.toEqual({ mode: 'normal', ids: ['p9', 'p3'] });
    expect(String(fetchMock.mock.calls[1][0])).toMatch(/^\/api\/v1\/sales\/sellable\?q=/);
  });

  it('người bán gõ tiếp (hủy từ ngoài) thì ném lỗi hủy, không chuyển chế độ', async () => {
    fetchMock.mockImplementation(hangUntilAborted);
    const controller = new AbortController();

    const pending = searchProducts('sữa', { signal: controller.signal });
    controller.abort(new DOMException('gõ tiếp', 'AbortError'));

    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('chuỗi rỗng không gọi mạng', async () => {
    await expect(searchProducts('   ')).resolves.toEqual({ mode: 'normal', ids: [] });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
