import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Input, Select, Table, Typography } from 'antd';
import type { TableProps } from 'antd';
import { useSearchParams } from 'react-router';
import { DEFAULT_PAGE_SIZE, clampPageSize } from '../../../shared/api/pagination';
import { ErrorAlert, countDaysInclusive, formatMoney, fromAntdChange, toAntdPagination, useDocumentTitle } from '../../../shared/components';
import { MAX_RANGE_DAYS, VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import { fetchAuditActions, fetchAuditActors, fetchAuditLog } from '../api/auditLog';
import type { AuditEntry, AuditFilters, Snapshot } from '../api/auditLog';
import { usersQueryKey } from '../api/users';

const TITLE = 'Nhật ký thao tác';
const time = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short', timeZone: VIETNAM_TIME_ZONE });
const labels = new Map(Object.entries({
  salePrice: 'Giá bán', displayName: 'Tên hiển thị', status: 'Trạng thái', name: 'Tên',
  phone: 'Số điện thoại', address: 'Địa chỉ', loginKey: 'Tên đăng nhập',
}));

function snapshotFields(snapshot: Snapshot) {
  const fields = new Map<string, string>();
  function visit(path: string[], value: unknown) {
    const field = path.at(-1) ?? '';
    const key = path.map((part) => labels.get(part) ?? part).join(' › ');
    if (value !== null && typeof value === 'object') {
      const entries = Object.entries(value);
      if (!entries.length) fields.set(key, 'Trống');
      for (const [child, item] of entries) visit([...path, child], item);
    } else if (/price|amount/i.test(field) && typeof value === 'number') {
      fields.set(key, formatMoney(value));
    } else {
      fields.set(key, value == null ? '—' : String(value));
    }
  }
  for (const [key, value] of Object.entries(snapshot ?? {})) visit([key], value);
  return fields;
}

function AuditDetails({ entry }: { entry: AuditEntry }) {
  const before = snapshotFields(entry.before);
  const after = snapshotFields(entry.after);
  const fields = [...new Set([...before.keys(), ...after.keys()])];
  return <Table size="small" pagination={false} rowKey="field"
    locale={{ emptyText: 'Không có dữ liệu trước/sau' }}
    columns={[
      { title: 'Trường', dataIndex: 'field' },
      { title: 'Giá trị cũ', dataIndex: 'before' },
      { title: 'Giá trị mới', dataIndex: 'after' },
    ]}
    dataSource={fields.map((field) => ({ field, before: before.get(field) ?? '—', after: after.get(field) ?? '—' }))}
  />;
}

const expandable: TableProps<AuditEntry>['expandable'] = { expandedRowRender: (entry) => <AuditDetails entry={entry} />,
        expandIcon: ({ expanded, onExpand, record }) => <Button size="small" aria-expanded={expanded}
          onClick={(event) => onExpand(record, event)}>{expanded ? 'Thu gọn' : 'Chi tiết'}</Button> };

export function AuditLogPage() {
  useDocumentTitle(TITLE);
  const [params, setParams] = useSearchParams();
  const filters: AuditFilters = {};
  for (const key of ['action', 'actorId', 'fromDate', 'toDate'] as const) {
    const value = params.get(key);
    if (value) filters[key] = value;
  }
  const { fromDate, toDate } = filters;
  const days = fromDate && toDate ? countDaysInclusive({ fromDate, toDate }) : 1;
  const rangeError = days < 1 ? 'Ngày kết thúc phải từ ngày bắt đầu trở đi'
    : days > MAX_RANGE_DAYS ? 'Khoảng ngày tối đa 366 ngày' : undefined;
  const page = Number(params.get('page'));
  const request = {
    page: Number.isSafeInteger(page) && page >= 0 ? page : 0,
    size: clampPageSize(Number(params.get('size')) || DEFAULT_PAGE_SIZE),
  };
  function update(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value);
      else next.delete(key);
    }
    setParams(next, { replace: true });
  }
  const actions = useQuery({ queryKey: ['platform', 'audit-log', 'actions'], queryFn: ({ signal }) => fetchAuditActions(signal) });
  const actors = useQuery({ queryKey: [...usersQueryKey, 'audit-actors'], queryFn: ({ signal }) => fetchAuditActors(signal) });
  const log = useQuery({
    queryKey: ['platform', 'audit-log', filters, request],
    queryFn: ({ signal }) => fetchAuditLog(filters, request, signal),
    enabled: !rangeError,
    staleTime: 0,
  });

  return <Flex vertical gap="middle" style={{ minWidth: 0, maxWidth: '100%' }}>
    <Typography.Title level={2}>{TITLE}</Typography.Title>
    <Flex gap="small" wrap align="end">
      <label>Loại thao tác<br />
        <Select aria-label="Loại thao tác" allowClear placeholder="Tất cả" style={{ minWidth: 220 }}
          loading={actions.isLoading} value={filters.action}
          options={actions.data?.map(({ action, label }) => ({ value: action, label }))}
          onChange={(action) => update({ action, page: undefined })} />
      </label>
      <label>Người thực hiện<br />
        <Select aria-label="Người thực hiện" allowClear placeholder="Tất cả" style={{ minWidth: 180 }}
          loading={actors.isLoading} value={filters.actorId}
          options={actors.data?.map(({ userId, displayName }) => ({ value: userId, label: displayName }))}
          onChange={(actorId) => update({ actorId, page: undefined })} />
      </label>
      <label>Từ ngày<Input type="date" value={fromDate ?? ''} onChange={(event) => update({ fromDate: event.target.value, page: undefined })} /></label>
      <label>Đến ngày<Input type="date" value={toDate ?? ''} onChange={(event) => update({ toDate: event.target.value, page: undefined })} /></label>
      <Button disabled={!Object.keys(filters).length}
        onClick={() => update({ action: undefined, actorId: undefined, fromDate: undefined, toDate: undefined, page: undefined })}>Xóa bộ lọc</Button>
    </Flex>
    {rangeError && <Typography.Text role="alert" type="danger">{rangeError}</Typography.Text>}
    {[actions, actors, log].map((query, index) => query.isError && <div key={index}>
      <ErrorAlert error={query.error} /><Button onClick={() => void query.refetch()}>Thử lại</Button>
    </div>)}
    <Table<AuditEntry> scroll={{ x: 'max-content' }} rowKey="id" loading={log.isFetching} dataSource={log.data?.items}
      locale={{ emptyText: 'Không có nhật ký thao tác' }}
      columns={[
        { title: 'Thời điểm', dataIndex: 'createdAt', render: (value: string) => time.format(new Date(value)) },
        { title: 'Loại thao tác', render: (_, entry) => entry.actionLabel ?? entry.action },
        { title: 'Người thực hiện', render: (_, entry) => entry.actorName ?? (entry.actorId ? `Chưa rõ tên · ${entry.actorId}` : 'Hệ thống') },
        { title: 'Đối tượng', render: (_, entry) => <span style={{ whiteSpace: 'nowrap' }}>{[entry.entity, entry.entityId].filter(Boolean).join(' · ')}</span> },
      ]}
      expandable={expandable}
      pagination={{ ...toAntdPagination(log.data, request), showSizeChanger: true }}
      onChange={(pagination) => {
        const next = fromAntdChange(pagination, undefined, request);
        update({ page: String(next.page), size: String(next.size) });
      }}
    />
  </Flex>;
}
