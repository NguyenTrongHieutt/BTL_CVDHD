import { Alert, Button, Collapse, Flex, Input, Typography } from 'antd';
import { useEffect, useId, useRef, useState } from 'react';
import { receiptImportLabels } from '../receiptImportApi';
import type { ReceiptImportError, ReceiptImportField, ReceiptImportRow, ReceiptImportValues } from '../receiptImportApi';

const mainFields: readonly ReceiptImportField[] = [
  'sku', 'quantity', 'unitCost', 'parentCode', 'productName', 'variantName', 'salePrice', 'barcode',
];
const details: readonly ReceiptImportField[] = [
  'categoryName', 'unit', 'description', 'attributeName1', 'attributeValue1',
  'attributeName2', 'attributeValue2', 'attributeName3', 'attributeValue3',
];
const numeric = new Set<ReceiptImportField>(['quantity', 'unitCost', 'salePrice']);

interface Props {
  row: ReceiptImportRow;
  errors: ReceiptImportError[];
  busy: boolean;
  onCancel: () => void;
  onApply: (changes: ReceiptImportValues) => void;
}

export function ReceiptImportRowEditor({ row, errors, busy, onCancel, onApply }: Props) {
  const [values, setValues] = useState(row.values);
  const heading = useRef<HTMLHeadingElement>(null);
  const id = useId();
  useEffect(() => heading.current?.focus(), []);
  const errorsFor = (field: ReceiptImportField) =>
    errors.filter(error => error.column?.toLocaleLowerCase('vi') === receiptImportLabels[field].toLocaleLowerCase('vi'));
  const hasDetailsError = details.some(field => errorsFor(field).length > 0);
  const uncategorized = errors.filter(error => !error.column ||
    !Object.values(receiptImportLabels).some(label => label.toLocaleLowerCase('vi') === error.column?.toLocaleLowerCase('vi')));
  const changes: ReceiptImportValues = {};
  for (const field of [...mainFields, ...details]) {
    if ((values[field] ?? '') !== (row.values[field] ?? '')) changes[field] = values[field] ?? '';
  }

  function renderField(field: ReceiptImportField) {
    const fieldId = `${id}-${field}`;
    const problems = errorsFor(field);
    const inputProps = {
      id: fieldId,
      value: values[field] ?? '',
      disabled: busy,
      'aria-invalid': problems.length > 0,
      'aria-describedby': problems.length ? `${fieldId}-error` : undefined,
      onChange: (event: { target: { value: string } }) => {
        setValues(current => ({ ...current, [field]: event.target.value }));
      },
    };
    return (
      <div className="receipt-import-field" key={field}>
        <label htmlFor={fieldId}>{receiptImportLabels[field]}</label>
        {field === 'description'
          ? <Input.TextArea {...inputProps} rows={2} />
          : <Input {...inputProps} inputMode={numeric.has(field) ? 'decimal' : undefined} />}
        {problems.length > 0 && (
          <Typography.Text id={`${fieldId}-error`} type="danger">
            {problems.map(error => error.message).join(' · ')}
          </Typography.Text>
        )}
      </div>
    );
  }

  return (
    <form onSubmit={(event) => {
      event.preventDefault();
      if (!busy && Object.keys(changes).length) onApply(changes);
    }}>
      <Flex vertical gap={14}>
        <h3 ref={heading} tabIndex={-1} className="receipt-import-editor-heading">Sửa dòng {row.row}</h3>
        {uncategorized.length > 0 && (
          <Alert showIcon type="warning" title={uncategorized.map(error => error.message).join(' · ')} />
        )}
        {row.action === 'EXISTING' && (
          <Typography.Text type="secondary">Tên sản phẩm và giá bán của SKU đã có lấy từ danh mục.</Typography.Text>
        )}
        <div className="receipt-import-grid">{mainFields.map(renderField)}</div>
        <Collapse size="small" defaultActiveKey={hasDetailsError ? ['details'] : []} items={[{
          key: 'details',
          label: 'Danh mục, mô tả và thuộc tính',
          children: <div className="receipt-import-grid">{details.map(renderField)}</div>,
        }]} />
        <Flex wrap gap={8} justify="end">
          <Button onClick={onCancel} disabled={busy}>Hủy sửa</Button>
          <Button type="primary" htmlType="submit" loading={busy} disabled={!Object.keys(changes).length}>
            Áp dụng và kiểm tra
          </Button>
        </Flex>
      </Flex>
    </form>
  );
}
