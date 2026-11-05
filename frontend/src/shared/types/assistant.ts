/**
 * Kiểu dữ liệu của Trợ lý AI (`ai-service`): Tìm kiếm ngữ nghĩa và Hỏi đáp dữ liệu.
 * Người giữ: Lân (C). Khớp với `ai-service/app/search/router.py` và `ai-service/app/qa/schemas.py`.
 */
import type { Uuid } from './common';

/** Một kết quả của `GET /ai/search?q=&limit=`: chỉ có `productId` và điểm (AD-12). */
export interface SemanticSearchHit {
  productId: Uuid;
  score: number;
}

export interface SemanticSearchResponse {
  items: SemanticSearchHit[];
}

/** Body của `POST /ai/ask` (FR-37, FR-41). */
export interface AskRequest {
  question: string;
}

/** Hạn mức AI trong ngày theo giờ Việt Nam (AD-13). */
export interface AiQuota {
  used: number;
  limit: number;
}

export interface AnswerTable {
  columns: string[];
  rows: unknown[][];
}

export interface AnswerChart {
  kind: 'line' | 'bar';
  x: string;
  y: string[];
}

/** Câu trả lời do code dựng (AD-11 mục 3). Con số do code điền, LLM chỉ viết lời dẫn. */
export interface AskResponse {
  /** Lời dẫn; `null` khi bị bỏ vì số không khớp hoặc lần gọi viết lời dẫn bị lỗi. */
  answer: string | null;
  /** AI đã hiểu câu hỏi thế nào: Qn, khoảng ngày, loại số liệu (FR-37). */
  interpretation: string;
  table: AnswerTable | null;
  chart: AnswerChart | null;
  reportLink: string | null;
  disclaimer: string;
  quota: AiQuota;
  refused: boolean;
}
