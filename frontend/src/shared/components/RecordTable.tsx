import { Table } from 'antd';
import type { TableProps } from 'antd';
import { isValidElement } from 'react';
import type { ReactNode } from 'react';
import { useCompactLayout } from './useCompactLayout';

export interface CompactColumns {
  title: string;
  status?: string;
  actions?: string;
}

export interface RecordTableProps<T> extends TableProps<T> {
  compactColumns?: CompactColumns;
}

type Column<T> = NonNullable<TableProps<T>['columns']>[number];

function renderCell<T extends object>(column: Column<T>, record: T, index: number): ReactNode {
  if ('children' in column) return null;
  const path = column.dataIndex;
  const parts = path == null || path === '' ? [] : Array.isArray(path) ? path : [path];
  let value: unknown = parts.length ? record : undefined;
  for (const key of parts) {
    value = value != null && typeof value === 'object' ? Reflect.get(value, String(key)) : undefined;
  }
  const cell = column.render ? column.render(value, record, index) : value;
  if (cell && typeof cell === 'object' && !isValidElement(cell) && 'children' in cell) {
    return cell.children as ReactNode;
  }
  return cell as ReactNode;
}

export function RecordTable<T extends object>({ compactColumns, columns, className, ...props }: RecordTableProps<T>) {
  const compact = useCompactLayout();
  if (!compact || !compactColumns || !columns) return <Table<T> {...props} className={className} columns={columns} />;

  const columnKey = (column: Column<T>) => column.key ?? ('dataIndex' in column ? column.dataIndex : undefined);
  const title = columns.find(column => columnKey(column) === compactColumns.title);
  if (!title) return <Table<T> {...props} className={className} columns={columns} />;

  const status = compactColumns.status ? columns.find(column => columnKey(column) === compactColumns.status) : undefined;
  const actions = compactColumns.actions ? columns.find(column => columnKey(column) === compactColumns.actions) : undefined;
  const fields = columns.filter(column => typeof column.title === 'string' && !('children' in column) &&
    column !== title && column !== status && column !== actions);

  return <Table<T> {...props} className={['record-table', className].filter(Boolean).join(' ')}
    showHeader={false} scroll={undefined} tableLayout="auto" columns={[{
      key: 'record',
      render: (_, record, index) => (
        <div className="record-card">
          <div className="record-card-heading">
            <div className="record-card-name">{renderCell(title, record, index)}</div>
            {status && <div>{renderCell(status, record, index)}</div>}
          </div>
          {fields.length > 0 && <dl className="record-card-fields">
            {fields.map(column => (
              <div key={String(columnKey(column) ?? column.title)}>
                <dt>{column.title as string}</dt>
                <dd>{renderCell(column, record, index)}</dd>
              </div>
            ))}
          </dl>}
          {actions && <div className="record-card-actions">{renderCell(actions, record, index)}</div>}
        </div>
      ),
    }]} />;
}
