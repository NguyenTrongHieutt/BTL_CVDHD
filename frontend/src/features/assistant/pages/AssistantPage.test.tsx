import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { AskResponse } from '../../../shared/types/assistant';
import { ASSISTANT_SUGGESTIONS } from '../suggestions';
import { AssistantPage } from './AssistantPage';

const fetchMock = vi.fn<typeof fetch>();

const ANSWER: AskResponse = {
  answer: 'Doanh thu tháng này tăng 20% so với tháng trước.',
  interpretation: 'Q6 · So sánh doanh thu thuần · 01/11/2026–30/11/2026 so với 01/10/2026–31/10/2026.',
  table: {
    columns: ['Ngày', 'Doanh thu thuần'],
    columnKeys: ['date', 'revenue'],
    columnTypes: ['date', 'money'],
    rows: [['2026-11-01', 1_000_000], ['2026-11-02', 1_200_000]],
    totalRows: 2,
    truncated: false,
  },
  chart: { kind: 'line', x: 'date', y: ['revenue'] },
  reportLink: '/reporting/revenue?fromDate=2026-11-01&toDate=2026-11-30',
  disclaimer: 'AI có thể sai, hãy đối chiếu trong báo cáo',
  quota: { used: 1, limit: 50, remaining: 49 },
  refused: false,
  queryId: 'Q6',
  source: 'Báo cáo cửa hàng',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function lastRequest(): { url: string; init: RequestInit; body: unknown } {
  const call = fetchMock.mock.calls.at(-1);
  if (!call) throw new Error('API chưa được gọi');
  const [input, init = {}] = call;
  return { url: String(input), init, body: JSON.parse(String(init.body)) as unknown };
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

describe('Trợ lý AI (FR-37 – FR-42)', () => {
  it('hiện đúng 5 câu gợi ý và trình bày câu trả lời, biểu đồ, bảng, nguồn, liên kết và hạn mức', async () => {
    fetchMock.mockResolvedValue(json(ANSWER));
    renderPage();

    for (const suggestion of ASSISTANT_SUGGESTIONS) {
      expect(screen.getByRole('button', { name: suggestion })).toBeTruthy();
    }
    fireEvent.click(screen.getByRole('button', { name: ASSISTANT_SUGGESTIONS[0] }));

    expect(await screen.findByRole('heading', { name: 'Kết quả phân tích' })).toBeTruthy();
    const request = lastRequest();
    expect(request.url).toBe('/ai/ask');
    expect(request.init.method).toBe('POST');
    expect(request.init.credentials).toBe('same-origin');
    expect(request.body).toEqual({ question: ASSISTANT_SUGGESTIONS[0] });
    expect(screen.getByText(ANSWER.interpretation)).toBeTruthy();
    expect(screen.getByText(ANSWER.answer ?? '')).toBeTruthy();
    expect(screen.getByRole('group', { name: /Biểu đồ đường: revenue theo date/u })).toBeTruthy();

    const table = screen.getByRole('region', { name: 'Bảng dữ liệu câu trả lời' });
    expect(within(table).getByRole('columnheader', { name: 'Ngày' })).toBeTruthy();
    expect(within(table).getByText('01/11/2026')).toBeTruthy();
    expect(within(table).getByText(/1\.000\.000/u)).toBeTruthy();
    expect(screen.getByText('Nguồn số liệu: Báo cáo cửa hàng.')).toBeTruthy();
    expect(screen.getByText('Còn 49/50 lượt')).toBeTruthy();
    expect(screen.getByRole('link', { name: /Mở báo cáo liên quan/u }).getAttribute('href')).toBe(
      '/reporting/revenue?fromDate=2026-11-01&toDate=2026-11-30',
    );
    expect(screen.getByText('AI có thể sai, hãy đối chiếu trong báo cáo')).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Kết quả phân tích' }));
  });

  it('Enter gửi câu hỏi, Shift + Enter chỉ xuống dòng và trạng thái chờ khóa gửi lặp', async () => {
    let finish: ((response: Response) => void) | undefined;
    fetchMock.mockImplementation(() => new Promise<Response>((resolve) => { finish = resolve; }));
    renderPage();
    const textarea = screen.getByLabelText('Câu hỏi của bạn');
    fireEvent.change(textarea, { target: { value: 'Doanh thu hôm nay?' } });

    fireEvent.keyDown(textarea, { key: 'Enter', shiftKey: true });
    expect(fetchMock).not.toHaveBeenCalled();
    fireEvent.keyDown(textarea, { key: 'Enter' });

    expect(await screen.findByText('Đang phân tích dữ liệu cửa hàng…')).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((screen.getByRole('button', { name: /Đang phân tích/u }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole('button', { name: ASSISTANT_SUGGESTIONS[0] }) as HTMLButtonElement).disabled).toBe(true);
    finish?.(json(ANSWER));
    expect(await screen.findByRole('heading', { name: 'Kết quả phân tích' })).toBeTruthy();
  });

  it('báo hết hạn mức, nêu giờ làm mới, giữ lối sang báo cáo và khóa ô hỏi', async () => {
    fetchMock.mockResolvedValue(json({
      type: '/errors/ai/quota-exceeded',
      title: 'Hết hạn mức AI',
      status: 429,
      requestId: 'req-quota',
    }, 429));
    renderPage();
    const textarea = screen.getByLabelText('Câu hỏi của bạn');
    fireEvent.change(textarea, { target: { value: 'Tình hình doanh thu hôm nay?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Hỏi Trợ lý' }));

    expect(await screen.findByText('Cửa hàng đã dùng hết lượt hỏi Trợ lý AI hôm nay')).toBeTruthy();
    expect(screen.getByText(/làm mới lúc 0 giờ theo giờ Việt Nam/u)).toBeTruthy();
    expect(screen.getByText('Đã hết lượt')).toBeTruthy();
    expect((screen.getByLabelText('Câu hỏi của bạn') as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.getByRole('link', { name: /Xem báo cáo/u }).getAttribute('href')).toBe('/reporting/dashboard');
  });

  it('hiện thông báo cố định khi từ chối câu ngoài phạm vi và vẫn có đường xem báo cáo', async () => {
    fetchMock.mockResolvedValue(json({
      ...ANSWER,
      answer: null,
      interpretation: 'Câu hỏi không ánh xạ được tới dữ liệu cửa hàng.',
      table: null,
      chart: null,
      reportLink: null,
      refused: true,
    } satisfies AskResponse));
    renderPage();
    const textarea = screen.getByLabelText('Câu hỏi của bạn');
    fireEvent.change(textarea, { target: { value: 'Giá vàng hôm nay?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Hỏi Trợ lý' }));

    expect(await screen.findByText('Câu hỏi nằm ngoài phạm vi hỗ trợ')).toBeTruthy();
    expect(screen.getByText(/chỉ trả lời các câu hỏi về dữ liệu bán hàng/u)).toBeTruthy();
    expect(screen.getByRole('link', { name: /Mở báo cáo liên quan/u }).getAttribute('href')).toBe('/reporting/dashboard');
  });

  it('giữ câu đã nhập và cho thử lại khi mất kết nối', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(json(ANSWER));
    renderPage();
    const textarea = screen.getByLabelText('Câu hỏi của bạn') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'Sản phẩm nào bán chạy?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Hỏi Trợ lý' }));

    expect(await screen.findByText('Không kết nối được máy chủ.')).toBeTruthy();
    expect(textarea.value).toBe('Sản phẩm nào bán chạy?');
    fireEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(await screen.findByRole('heading', { name: 'Kết quả phân tích' })).toBeTruthy();
  });
});
