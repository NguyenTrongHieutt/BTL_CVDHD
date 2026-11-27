import { Bar, BarChart, CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { AnswerChart, AnswerTable } from '../../../shared/types/assistant';

interface AssistantChartProps {
  chart: AnswerChart;
  table: AnswerTable;
}

interface SeriesDefinition {
  key: string;
  label: string;
  color: string;
  dash?: string;
}

type ChartDatum = Record<string, string | number>;

const X_KEY = '__assistant_x';
const chartColors = [
  'var(--ant-color-primary)',
  'var(--ant-color-warning)',
  'var(--ant-color-info)',
  'var(--ant-color-error)',
  'var(--ant-color-text-secondary)',
] as const;
const linePatterns = [undefined, '8 4', '3 3', '10 3 2 3', '2 5'] as const;
const compactNumber = new Intl.NumberFormat('vi-VN', { notation: 'compact', maximumFractionDigits: 1 });

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'number') return value.toLocaleString('vi-VN');
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

function numericValue(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value !== 'string' || value.trim() === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function chartModel(chart: AnswerChart, table: AnswerTable): { data: ChartDatum[]; series: SeriesDefinition[] } | null {
  const keys = table.columnKeys?.length === table.columns.length ? table.columnKeys : table.columns;
  const xIndex = keys.indexOf(chart.x);
  if (xIndex < 0) return null;
  const series = chart.y.flatMap((field, index) => {
    const columnIndex = keys.indexOf(field);
    return columnIndex < 0 ? [] : [{
      key: `__assistant_y_${index}`,
      label: table.columns[columnIndex] ?? field,
      columnIndex,
      color: chartColors[index % chartColors.length],
      dash: linePatterns[index % linePatterns.length],
    }];
  });
  if (series.length === 0) return null;

  const data = table.rows.flatMap((row) => {
    const values = series.map(({ columnIndex }) => numericValue(row[columnIndex]));
    if (values.every((value) => value === undefined)) return [];
    const datum: ChartDatum = { [X_KEY]: displayValue(row[xIndex]) };
    series.forEach(({ key }, index) => {
      if (values[index] !== undefined) datum[key] = values[index];
    });
    return [datum];
  });
  if (data.length === 0) return null;
  return { data, series };
}

export function AssistantChart({ chart, table }: AssistantChartProps) {
  const model = chartModel(chart, table);
  if (!model) return null;
  const label = `${chart.kind === 'line' ? 'Biểu đồ đường' : 'Biểu đồ cột'}: ${chart.y.join(', ')} theo ${chart.x}.`;
  const shared = (
    <>
      <CartesianGrid stroke="var(--line)" strokeDasharray="3 3" vertical={false} />
      <XAxis dataKey={X_KEY} tick={{ fill: 'var(--muted)', fontSize: 12 }} minTickGap={18} />
      <YAxis
        tick={{ fill: 'var(--muted)', fontSize: 12 }}
        tickFormatter={(value: number) => compactNumber.format(value)}
        width={64}
      />
      <Tooltip />
      {model.series.length > 1 && <Legend verticalAlign="top" height={36} />}
    </>
  );

  return (
    <div className="assistant-chart" role="group" aria-label={label}>
      <ResponsiveContainer width="100%" height="100%" minWidth={1} minHeight={250} debounce={50}>
        {chart.kind === 'line' ? (
          <LineChart data={model.data} accessibilityLayer margin={{ top: 12, right: 12, left: 0, bottom: 8 }}>
            {shared}
            {model.series.map((series) => (
              <Line
                key={series.key}
                type="monotone"
                dataKey={series.key}
                name={series.label}
                stroke={series.color}
                strokeWidth={2.5}
                strokeDasharray={series.dash}
                connectNulls
                dot={{ r: 3 }}
                activeDot={{ r: 5 }}
              />
            ))}
          </LineChart>
        ) : (
          <BarChart data={model.data} accessibilityLayer margin={{ top: 12, right: 12, left: 0, bottom: 8 }}>
            {shared}
            {model.series.map((series) => (
              <Bar key={series.key} dataKey={series.key} name={series.label} fill={series.color} radius={[5, 5, 0, 0]} />
            ))}
          </BarChart>
        )}
      </ResponsiveContainer>
    </div>
  );
}
