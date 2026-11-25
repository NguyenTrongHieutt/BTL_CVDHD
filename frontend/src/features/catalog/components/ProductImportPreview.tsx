import { Alert, Collapse, Flex, Pagination, Tabs, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useMemo, useState } from 'react';
import { MoneyText, RecordTable } from '../../../shared/components';
import type { ProductImportPreview, ProductImportPreviewRow, ProductImportRowError } from '../api/products';

const EMPTY = '—';
const GROUPS_PER_PAGE = 8;
const count = new Intl.NumberFormat('vi-VN');

type FailedRow = ProductImportRowError & { key: number };
const failedColumns: TableColumnsType<FailedRow> = [
  { title: 'Dòng', dataIndex: 'row', width: 80, align: 'right' },
  { title: 'Cột', dataIndex: 'column', width: 160, render: (column: string | null) => column ?? EMPTY },
  { title: 'Lý do', dataIndex: 'message' },
];

export function ImportFailedRows({ errors }: { errors: ProductImportRowError[] }) {
  return (
    <RecordTable<FailedRow>
      size="small"
      rowKey="key"
      compactColumns={{ title: 'message' }}
      columns={failedColumns}
      dataSource={errors.map((error, index) => ({ ...error, key: index }))}
      scroll={{ x: 420 }}
      pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
    />
  );
}

const variantColumns: TableColumnsType<ProductImportPreviewRow> = [
  {
    title: 'Dòng trong file', key: 'row', width: 92,
    render: (_, row) => (
      <Flex vertical gap={2}>
        <Typography.Text strong>Dòng {row.row}</Typography.Text>
        {row.variantName && <Typography.Text>{row.variantName}</Typography.Text>}
      </Flex>
    ),
  },
  {
    title: 'SKU và mã vạch', key: 'codes', width: 150,
    render: (_, row) => (
      <Flex vertical gap={2}>
        <Typography.Text>{row.sku || 'SKU tự sinh'}</Typography.Text>
        {row.barcode && <Typography.Text type="secondary">Mã vạch {row.barcode}</Typography.Text>}
      </Flex>
    ),
  },
  {
    title: 'Giá và tồn kho', key: 'stock', width: 150,
    render: (_, row) => (
      <Flex vertical gap={2}>
        <MoneyText value={row.salePrice} strong />
        <Typography.Text type="secondary">
          Tồn đầu kỳ {row.openingQuantity === null ? EMPTY : count.format(row.openingQuantity)}
        </Typography.Text>
        {row.openingUnitCost !== null && (
          <Typography.Text type="secondary">Giá vốn <MoneyText value={row.openingUnitCost} /></Typography.Text>
        )}
      </Flex>
    ),
  },
  {
    title: 'Thuộc tính', key: 'attributes',
    render: (_, row) => {
      const attributes = Object.entries(row.attributes);
      return attributes.length ? (
        <Flex vertical gap={2}>
          {attributes.map(([name, value]) => (
            <Typography.Text key={name}>{name} · {value}</Typography.Text>
          ))}
        </Flex>
      ) : EMPTY;
    },
  },
];

interface ImportPreviewGroup {
  key: string;
  rows: ProductImportPreviewRow[];
}

function groupRows(rows: ProductImportPreviewRow[]): ImportPreviewGroup[] {
  const groups = new Map<string, ImportPreviewGroup>();
  for (const row of rows) {
    const key = row.parentCode ? `parent:${row.parentCode}` : `row:${row.row}`;
    const group = groups.get(key);
    if (group) group.rows.push(row);
    else groups.set(key, { key, rows: [row] });
  }
  return [...groups.values()];
}

function groupPanel({ key, rows }: ImportPreviewGroup) {
  const [first] = rows;
  const name = rows.find((row) => row.productName)?.productName ?? 'Sản phẩm chưa có tên';
  const category = rows.find((row) => row.categoryName)?.categoryName;
  const unit = rows.find((row) => row.unit)?.unit;
  const description = rows.find((row) => row.description)?.description;
  const valid = rows.every((row) => row.valid);
  return {
    key,
    label: (
      <Flex align="center" justify="space-between" wrap gap={8} className="import-preview-heading">
        <div>
          <Typography.Text strong>{name}</Typography.Text>
          {first.parentCode && (
            <div><Typography.Text type="secondary">
              Mã cha {first.parentCode} · {count.format(rows.length)} biến thể
            </Typography.Text></div>
          )}
        </div>
        <Tag color={valid ? 'success' : 'error'}>{valid ? 'Sẵn sàng lưu' : 'Có lỗi'}</Tag>
      </Flex>
    ),
    children: (
      <Flex vertical gap={8} className="import-preview-group">
        {(category || unit) && <Typography.Text type="secondary">{[category, unit].filter(Boolean).join(' · ')}</Typography.Text>}
        {description && <Typography.Paragraph style={{ marginBlockEnd: 0 }}>{description}</Typography.Paragraph>}
        <RecordTable<ProductImportPreviewRow>
          rowKey="row"
          size="small"
          compactColumns={{ title: 'row' }}
          columns={variantColumns}
          dataSource={rows}
          scroll={{ x: 520 }}
          pagination={rows.length > 10 ? { pageSize: 10, showSizeChanger: false } : false}
        />
      </Flex>
    ),
  };
}

export function ProductImportPreviewView({ preview }: { preview: ProductImportPreview }) {
  const [page, setPage] = useState(1);
  const groups = useMemo(() => groupRows(preview.rows), [preview.rows]);
  const visibleGroups = groups.slice((page - 1) * GROUPS_PER_PAGE, page * GROUPS_PER_PAGE);
  const invalidRows = preview.totalRows - preview.validRows;
  const items = [{
    key: 'data',
    label: `Sản phẩm (${count.format(groups.length)})`,
    children: (
      <Flex vertical gap={12}>
        {visibleGroups.length > 0 && (
          <Collapse
            key={page}
            size="small"
            defaultActiveKey={[visibleGroups[0].key]}
            items={visibleGroups.map(groupPanel)}
          />
        )}
        {groups.length > GROUPS_PER_PAGE && (
          <Pagination
            current={page}
            pageSize={GROUPS_PER_PAGE}
            total={groups.length}
            showSizeChanger={false}
            onChange={setPage}
            align="end"
          />
        )}
      </Flex>
    ),
  }, ...(preview.errors.length ? [{
    key: 'errors',
    label: `Dòng có lỗi (${count.format(invalidRows)})`,
    children: <ImportFailedRows errors={preview.errors} />,
  }] : [])];

  return (
    <Flex vertical gap={12}>
      <Alert
        showIcon
        type={preview.validProducts === 0 ? 'warning' : invalidRows > 0 ? 'warning' : 'info'}
        title={preview.totalRows === 0 ? 'File chưa có dòng dữ liệu' :
          `${count.format(preview.validProducts)} sản phẩm sẵn sàng lưu · ${count.format(preview.validRows)}/${count.format(preview.totalRows)} dòng hợp lệ`}
        description={invalidRows > 0
          ? `${count.format(invalidRows)} dòng bị bỏ qua khi lưu. Nếu một biến thể có lỗi, cả nhóm sẽ bị bỏ qua.`
          : 'Dữ liệu sẽ được tạo mới khi bạn xác nhận lưu.'}
      />
      {preview.totalRows > 0 && <Tabs items={items} defaultActiveKey={preview.validProducts === 0 ? 'errors' : 'data'} />}
    </Flex>
  );
}
