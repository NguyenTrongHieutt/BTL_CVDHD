/**
 * Kiểu dữ liệu của module `reporting`: Truy vấn mẫu Q1–Q9 (AD-10).
 * Người giữ: Lân (C). Khớp với `reporting/QueryId.java`.
 */
import type { DateRange } from './common';

/** Truy vấn mẫu, dùng chung cho màn Báo cáo và Trợ lý AI (PRD 4.6.1). */
export type QueryId = 'Q1' | 'Q2' | 'Q3' | 'Q4' | 'Q5' | 'Q6' | 'Q7' | 'Q8' | 'Q9';

export const QUERY_IDS: readonly QueryId[] = ['Q1', 'Q2', 'Q3', 'Q4', 'Q5', 'Q6', 'Q7', 'Q8', 'Q9'];

export const QUERY_TITLES: Readonly<Record<QueryId, string>> = {
  Q1: 'Doanh thu theo kỳ',
  Q2: 'Số Đơn hàng theo kỳ',
  Q3: 'Lợi nhuận gộp theo kỳ',
  Q4: 'Sản phẩm bán chạy / bán chậm',
  Q5: 'Tồn kho và giá trị Tồn kho',
  Q6: 'So sánh hai kỳ',
  Q7: 'Hàng tồn nhiều nhưng bán chậm',
  Q8: 'Gợi ý nhập hàng',
  Q9: 'Sản phẩm nên ưu tiên bán',
};

/**
 * Phản hồi của `GET /api/v1/reporting/q/<Qn>`. Mỗi phản hồi luôn kèm `range` (AD-10).
 * TODO(Lân, BC-02, GĐ1): chốt cấu trúc `data` của từng Qn ở CB-03.
 */
export interface QueryResponse<TData = unknown> {
  queryId: QueryId;
  range: DateRange;
  data: TData;
}
