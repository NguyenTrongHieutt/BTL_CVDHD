import { expect, it } from 'vitest';
import { encodeReportCsv } from './reportCsv';

it('keeps Vietnamese text, multiline fields and numeric totals readable in spreadsheets', () => {
  expect(encodeReportCsv([
    ['Sản phẩm', 'Danh mục', 'Số lượng', 'Doanh thu'],
    ['Trà, vị "nhài"\nthử', null, 0, -50000],
  ])).toBe('\uFEFF"Sản phẩm","Danh mục","Số lượng","Doanh thu"\r\n"Trà, vị ""nhài""\nthử",,0,-50000\r\n');
});

it('treats formula-like names and SKU values as text without altering negative numeric values', () => {
  expect(encodeReportCsv([['=1+1', ' +012', '-01', '@mã', '\tSKU', -1000]]))
    .toBe('\uFEFF"\'=1+1","\' +012","\'-01","\'@mã","\'\tSKU",-1000\r\n');
});
