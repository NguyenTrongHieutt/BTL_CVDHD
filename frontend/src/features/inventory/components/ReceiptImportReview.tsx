import { Alert, Button, Collapse, Flex, Input, Pagination, Tabs, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useId, useMemo, useState } from 'react';
import { MoneyText, RecordTable } from '../../../shared/components';
import { foldText } from '../../../shared/foldText';
import type { ReceiptImportError, ReceiptImportPreview, ReceiptImportRow } from '../receiptImportApi';

const count = new Intl.NumberFormat('vi-VN');
const groupsPerPage = 8;

function groupRows(rows: ReceiptImportRow[]) {
  const groups = new Map<string, ReceiptImportRow[]>();
  for (const row of rows) {
    const key = row.parentCode ? `parent:${row.parentCode}` : `row:${row.row}`;
    const group = groups.get(key);
    if (group) group.push(row);
    else groups.set(key, [row]);
  }
  return [...groups];
}

function rowTag(row: ReceiptImportRow) {
  if (!row.valid) return <Tag color="error">Có lỗi</Tag>;
  if (row.action === 'EXISTING') return <Tag color="processing">SKU đã có</Tag>;
  return <Tag color="success">{row.action === 'NEW_VARIANT' ? 'Thêm biến thể' : 'SKU mới'}</Tag>;
}

function renderErrors(errors: ReceiptImportError[]) {
  return (
    <ul className="receipt-import-error-list">
      {errors.map(({ column, message }, index) => (
        <li key={`${column ?? 'row'}-${index}`}>{column && <strong>{column} · </strong>}{message}</li>
      ))}
    </ul>
  );
}

interface Props {
  preview: ReceiptImportPreview;
  search: string;
  onSearchChange: (search: string) => void;
  disabled?: boolean;
  onEdit: (row: ReceiptImportRow) => void;
}

export function ReceiptImportReview({ preview, search, onSearchChange, onEdit, disabled }: Props) {
  const [page, setPage] = useState(1);
  const searchId = useId();
  const keyword = foldText(search.trim());
  const matching = useMemo(() => {
    if (!keyword) return preview.rows;
    return preview.rows.filter(row => {
      const fields = [row.sku, row.parentCode, row.productName, row.variantName, row.values.sku, row.values.barcode,
        row.values.productName, row.values.parentCode, row.values.variantName, `Dòng ${row.row}`];
      return fields.some(field => field && foldText(field).includes(keyword));
    });
  }, [preview.rows, keyword]);
  const groups = useMemo(() => groupRows(matching), [matching]);
  const visibleGroups = groups.slice((page - 1) * groupsPerPage, page * groupsPerPage);
  const errorsByRow = useMemo(() => {
    const result = new Map<number, ReceiptImportError[]>();
    for (const error of preview.errors) {
      const errors = result.get(error.row);
      if (errors) errors.push(error);
      else result.set(error.row, [error]);
    }
    return result;
  }, [preview.errors]);
  const rowsWithErrors = preview.rows.filter(row => errorsByRow.has(row.row));
  const matchesWithErrors = matching.filter(row => errorsByRow.has(row.row));
  const rowNumbers = new Set(preview.rows.map(row => row.row));
  const generalErrors = preview.errors.filter(error => !rowNumbers.has(error.row));

  const edit = (row: ReceiptImportRow) => (
    <Button size="small" disabled={disabled} onClick={() => onEdit(row)}>Sửa dòng {row.row}</Button>
  );
  const columns: TableColumnsType<ReceiptImportRow> = [
    {
      title: 'SKU và biến thể', key: 'sku',
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Typography.Text strong>{row.sku || 'Chưa nhập SKU'}</Typography.Text>
          <Typography.Text type="secondary">{row.variantName || `Dòng ${row.row}`}</Typography.Text>
        </Flex>
      ),
    },
    { title: 'Trạng thái', key: 'status', render: (_, row) => rowTag(row) },
    { title: 'Số lượng', key: 'quantity', render: (_, row) => row.quantity == null ? '—' : count.format(row.quantity) },
    { title: 'Giá nhập', key: 'unitCost', render: (_, row) => <MoneyText value={row.unitCost} /> },
    { title: 'Thao tác', key: 'actions', render: (_, row) => edit(row) },
  ];
  const errorColumns: TableColumnsType<ReceiptImportRow> = [
    { title: 'Dòng', key: 'row', render: (_, row) => `Dòng ${row.row}${row.sku ? ` · ${row.sku}` : ''}` },
    { title: 'Lỗi cần sửa', key: 'errors', render: (_, row) => renderErrors(errorsByRow.get(row.row) ?? []) },
    { title: 'Thao tác', key: 'actions', render: (_, row) => edit(row) },
  ];
  const warning = preview.errors.length > 0;

  return (
    <Flex vertical gap={12}>
      <div className="receipt-import-totals">
        <div><Typography.Text type="secondary">SKU đã có</Typography.Text><strong>{count.format(preview.existingRows)}</strong></div>
        <div><Typography.Text type="secondary">Sản phẩm mới</Typography.Text><strong>{count.format(preview.newProducts)}</strong></div>
        <div><Typography.Text type="secondary">SKU mới</Typography.Text><strong>{count.format(preview.newVariants)}</strong></div>
        <div><Typography.Text type="secondary">Tổng tiền nhập</Typography.Text><MoneyText value={preview.totalAmount} strong /></div>
      </div>
      {warning && <Alert type="warning" showIcon title={rowsWithErrors.length
        ? `Cần sửa ${count.format(rowsWithErrors.length)} dòng trước khi lưu phiếu`
        : 'Cần xử lý lỗi trước khi lưu phiếu'}
      description={generalErrors.length ? renderErrors(generalErrors) : undefined} />}
      {preview.rows.length === 0 && !warning && <Alert showIcon type="info" title="File chưa có dòng dữ liệu." />}
      {preview.rows.length > 0 && (
        <div className="receipt-import-field" style={{ maxWidth: 410 }}>
          <label htmlFor={searchId}>Tìm dòng trong file</label>
          <Input id={searchId} value={search} allowClear placeholder="Tên, biến thể, SKU, mã cha, mã vạch hoặc số dòng"
            onChange={(event) => {
              setPage(1);
              onSearchChange(event.target.value);
            }} />
        </div>
      )}
      {preview.rows.length > 0 && <Tabs defaultActiveKey={rowsWithErrors.length ? 'errors' : 'data'} items={[{
        key: 'data',
        label: `Tất cả (${count.format(matching.length)})`,
        children: (
          <Flex vertical gap={12}>
            {groups.length === 0 && <Typography.Text type="secondary" role="status">Không có dòng khớp từ khóa.</Typography.Text>}
            <Collapse
              key={page}
              size="small"
              defaultActiveKey={visibleGroups[0] ? [visibleGroups[0][0]] : []}
              items={visibleGroups.map(([key, rows]) => ({
                key,
                label: (
                  <Flex vertical gap={2}>
                    <Typography.Text strong>
                      {rows.find(row => row.productName)?.productName || rows[0].sku || 'Sản phẩm chưa có tên'}
                    </Typography.Text>
                    {rows[0].parentCode && (
                      <Typography.Text type="secondary">
                        Mã cha {rows[0].parentCode} · {count.format(rows.length)} SKU
                      </Typography.Text>
                    )}
                  </Flex>
                ),
                children: (
                  <RecordTable<ReceiptImportRow> size="small" rowKey="row" columns={columns} dataSource={rows}
                    compactColumns={{ title: 'sku', status: 'status', actions: 'actions' }} scroll={{ x: 540 }}
                    pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false} />
                ),
              }))}
            />
            {groups.length > groupsPerPage && (
              <Pagination size="small" align="end" current={page} total={groups.length} pageSize={groupsPerPage}
                showSizeChanger={false} onChange={setPage} />
            )}
          </Flex>
        ),
      }, ...(rowsWithErrors.length ? [{
        key: 'errors',
        label: `Dòng có lỗi (${count.format(matchesWithErrors.length)})`,
        children: (
          <RecordTable<ReceiptImportRow> size="small" rowKey="row" columns={errorColumns} dataSource={matchesWithErrors}
            compactColumns={{ title: 'row', actions: 'actions' }} scroll={{ x: 460 }}
            locale={{ emptyText: 'Không có dòng lỗi khớp từ khóa.' }}
            pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }} />
        ),
      }] : [])]} />}
    </Flex>
  );
}
