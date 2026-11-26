type CsvCell = string | number | null;
export type CsvRow = readonly CsvCell[];

export function encodeReportCsv(rows: readonly CsvRow[]): string {
  return '\uFEFF' + rows.map(row => row.map(encodeCell).join(',')).join('\r\n') + '\r\n';
}

function encodeCell(value: CsvCell): string {
  if (value === null) return '';
  if (typeof value === 'number') return String(value);
  // Ngăn tên hoặc mã do cửa hàng nhập bị bảng tính diễn giải thành công thức.
  const text = /^[\s\uFEFF]*[=+@-]/u.test(value) || /^[\t\r\n]/u.test(value) ? "'" + value : value;
  return '"' + text.replaceAll('"', '""') + '"';
}
