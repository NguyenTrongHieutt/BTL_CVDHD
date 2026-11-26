/** Kiểu dữ liệu của module reporting và khung phản hồi Q1–Q9 (AD-10). */
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

export interface ReportColumn {
  key: string;
  label: string;
  type: 'text' | 'date' | 'money' | 'int' | 'percent';
}

/** Khung phản hồi của `GET /api/v1/reporting/q/<Qn>` (AD-10). */
export interface QueryResponse<TRow = unknown, TSummary = unknown> {
  queryId: QueryId;
  range: DateRange;
  params: Readonly<Record<string, string | number>>;
  columns: ReportColumn[];
  rows: TRow[];
  summary: TSummary;
}
