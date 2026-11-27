import { BarChartOutlined, DatabaseOutlined, InfoCircleOutlined, RobotOutlined } from '@ant-design/icons';
import { Alert, Card, Typography } from 'antd';
import type { RefObject } from 'react';
import { Link } from 'react-router';
import type { AnswerColumnType, AnswerTable, AskResponse } from '../../../shared/types/assistant';
import { AssistantChart } from './AssistantChart';

interface AssistantAnswerProps {
  question: string;
  response: AskResponse;
  headingRef: RefObject<HTMLHeadingElement | null>;
}

const decimalFormatter = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
const integerFormatter = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 });
const moneyFormatter = new Intl.NumberFormat('vi-VN', {
  style: 'currency', currency: 'VND', maximumFractionDigits: 0,
});
const dateFormatter = new Intl.DateTimeFormat('vi-VN', {
  day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC',
});

function displayCell(value: unknown, type?: AnswerColumnType): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') {
    if (type === 'money') return moneyFormatter.format(value);
    if (type === 'int') return integerFormatter.format(value);
    if (type === 'percent') return `${decimalFormatter.format(value)}%`;
    return decimalFormatter.format(value);
  }
  if (type === 'date' && typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/u.test(value)) {
    return dateFormatter.format(new Date(`${value}T00:00:00Z`));
  }
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function reportPathOf(value: string | null): string {
  if (value === '/reports') return '/reporting/dashboard';
  if (value?.startsWith('/reporting/') && !value.startsWith('//')) return value;
  return '/reporting/dashboard';
}

function AnswerDataTable({ table }: { table: AnswerTable }) {
  return (
    <div className="assistant-table-scroll" role="region" aria-label="Bảng dữ liệu câu trả lời" tabIndex={0}>
      <table className="assistant-data-table">
        <caption className="visually-hidden">Dữ liệu chi tiết dùng để tạo câu trả lời</caption>
        <thead>
          <tr>
            {table.columns.map((column, index) => <th scope="col" key={`${column}-${index}`}>{column}</th>)}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {table.columns.map((column, columnIndex) => (
                <td key={`${column}-${columnIndex}`}>{displayCell(row[columnIndex], table.columnTypes?.[columnIndex])}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {table.truncated && (
        <p className="assistant-table-note">
          Đang hiển thị {table.rows.length.toLocaleString('vi-VN')} trong tổng số {(table.totalRows ?? table.rows.length).toLocaleString('vi-VN')} dòng.
        </p>
      )}
    </div>
  );
}

export function AssistantAnswer({ question, response, headingRef }: AssistantAnswerProps) {
  const reportPath = reportPathOf(response.reportLink);
  const answerFallback = response.refused
    ? 'Trợ lý chỉ trả lời các câu hỏi về dữ liệu bán hàng, lợi nhuận và tồn kho của cửa hàng.'
    : 'Trợ lý chưa thể tạo lời dẫn, nhưng số liệu đã kiểm tra vẫn được trình bày bên dưới.';

  return (
    <article className="assistant-result" aria-labelledby="assistant-result-title">
      <Card>
        <div className="assistant-result-heading">
          <div className="assistant-result-icon"><RobotOutlined aria-hidden="true" /></div>
          <div>
            <Typography.Title
              level={2}
              id="assistant-result-title"
              ref={headingRef}
              tabIndex={-1}
              className="assistant-result-title"
            >
              Kết quả phân tích
            </Typography.Title>
            <Typography.Text type="secondary" className="assistant-result-question">“{question}”</Typography.Text>
          </div>
        </div>

        <section className="assistant-interpretation" aria-labelledby="assistant-interpretation-title">
          <DatabaseOutlined aria-hidden="true" />
          <div>
            <Typography.Title level={3} id="assistant-interpretation-title">Trợ lý hiểu câu hỏi</Typography.Title>
            <Typography.Paragraph>{response.interpretation}</Typography.Paragraph>
          </div>
        </section>

        <section className="assistant-narrative" aria-labelledby="assistant-narrative-title">
          <Typography.Title level={3} id="assistant-narrative-title">Câu trả lời</Typography.Title>
          {response.answer ? (
            <Typography.Paragraph className="assistant-answer-copy">{response.answer}</Typography.Paragraph>
          ) : (
            <Alert
              type={response.refused ? 'warning' : 'info'}
              showIcon
              title={response.refused ? 'Câu hỏi nằm ngoài phạm vi hỗ trợ' : 'Đang dùng kết quả dự phòng'}
              description={answerFallback}
            />
          )}
        </section>

        {response.chart && response.table && (
          <section className="assistant-chart-section" aria-labelledby="assistant-chart-title">
            <Typography.Title level={3} id="assistant-chart-title">Biểu đồ</Typography.Title>
            <AssistantChart chart={response.chart} table={response.table} />
          </section>
        )}

        {response.table && (
          <section className="assistant-table-section" aria-labelledby="assistant-table-title">
            <Typography.Title level={3} id="assistant-table-title">Số liệu chi tiết</Typography.Title>
            <AnswerDataTable table={response.table} />
          </section>
        )}

        <footer className="assistant-disclaimer" role="note">
          <InfoCircleOutlined aria-hidden="true" />
          <div>
            <strong>{response.disclaimer || 'AI có thể sai, hãy đối chiếu trong báo cáo'}</strong>
            <span>Nguồn số liệu: {response.source ?? 'hệ thống báo cáo của chính cửa hàng'}.</span>
          </div>
          <Link className="assistant-report-link" to={reportPath}>
            <BarChartOutlined aria-hidden="true" />
            Mở báo cáo liên quan
          </Link>
        </footer>
      </Card>
    </article>
  );
}
