import { api } from '../../../shared/api/client';
import type { AskRequest, AskResponse } from '../../../shared/types/assistant';

export const ASK_PATH = '/ai/ask';

/** Gửi câu hỏi qua API client chung để giữ cookie phiên và xử lý 401 thống nhất. */
export function askAssistant(question: string): Promise<AskResponse> {
  const body: AskRequest = { question };
  return api.post<AskResponse>(ASK_PATH, body);
}
