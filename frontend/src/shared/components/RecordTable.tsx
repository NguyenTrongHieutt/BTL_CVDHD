import { Table } from 'antd';
import type { TableProps, TableRef } from 'antd';
import { isValidElement, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useCompactLayout } from './useCompactLayout';

export interface CompactColumns {
  title: string;
  status?: string;
  actions?: string;
}

export interface RecordTableProps<T> extends TableProps<T> {
  compactColumns?: CompactColumns;
  scrollRows?: boolean;
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

export function RecordTable<T extends object>({
  compactColumns, columns, className, scrollRows = false, scroll, ...props
}: RecordTableProps<T>) {
  const compact = useCompactLayout();
  const tableRef = useRef<TableRef>(null);
  const tableScroll = scrollRows ? { scrollToFirstRowOnChange: true, ...scroll } : scroll;

  useEffect(() => {
    if (!scrollRows || typeof ResizeObserver === 'undefined') return;
    const content = tableRef.current?.nativeElement.querySelector<HTMLElement>('.ant-table-content');
    const table = content?.querySelector('table');
    if (!content || !table) return;

    content.classList.add('record-table-viewport');
    const clearScrollRegion = () => {
      content.removeAttribute('tabindex');
      content.removeAttribute('role');
      content.removeAttribute('aria-label');
    };
    const updateScrollRegion = () => {
      const headerHeight = table.tHead?.offsetHeight ?? 0;
      content.style.setProperty('--record-table-header-height', `${headerHeight}px`);
      if (content.scrollHeight > content.clientHeight + 1) {
        content.tabIndex = 0;
        content.setAttribute('role', 'group');
        content.setAttribute('aria-label', 'Cuộn dữ liệu bảng');
      } else {
        clearScrollRegion();
      }
    };
    updateScrollRegion();
    const observer = new ResizeObserver(updateScrollRegion);
    observer.observe(content);
    observer.observe(table);
    return () => {
      observer.disconnect();
      content.classList.remove('record-table-viewport');
      content.style.removeProperty('--record-table-header-height');
      clearScrollRegion();
    };
  }, [scrollRows]);

  const columnKey = (column: Column<T>) => column.key ?? ('dataIndex' in column ? column.dataIndex : undefined);
  const title = compact && compactColumns && columns?.find(column => columnKey(column) === compactColumns.title);
  if (!title || !compactColumns || !columns) {
    return <Table<T> {...props} ref={tableRef} className={className} columns={columns} scroll={tableScroll} />;
  }

  const status = compactColumns.status ? columns.find(column => columnKey(column) === compactColumns.status) : undefined;
  const actions = compactColumns.actions ? columns.find(column => columnKey(column) === compactColumns.actions) : undefined;
  const fields = columns.filter(column => typeof column.title === 'string' && !('children' in column) &&
    column !== title && column !== status && column !== actions);

  return <Table<T> {...props} ref={tableRef} className={['record-table', className].filter(Boolean).join(' ')}
    showHeader={false} scroll={scrollRows ? { scrollToFirstRowOnChange: true } : undefined} tableLayout="auto" columns={[{
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
