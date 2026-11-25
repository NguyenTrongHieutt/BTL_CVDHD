import { DownOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Input, Modal, Select, Typography } from 'antd';
import type { TableProps } from 'antd';
import { useId, useState } from 'react';
import { useSearchParams } from 'react-router';
import { DEFAULT_PAGE_SIZE, clampPageSize } from '../../../shared/api/pagination';
import { ErrorAlert, RecordTable, countDaysInclusive, formatMoney, fromAntdChange, toAntdPagination, useCompactLayout, useDocumentTitle } from '../../../shared/components';
import { MAX_RANGE_DAYS, VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import { fetchAuditActions, fetchAuditActors, fetchAuditLog } from '../api/auditLog';
import type { AuditEntry, AuditFilters, Snapshot } from '../api/auditLog';
import { usersQueryKey } from '../api/users';

const TITLE = 'Nhật ký thao tác';
const DATE_ERROR_ID = 'audit-log-date-error';
const time = new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short', timeZone: VIETNAM_TIME_ZONE });
const labels = new Map(Object.entries({
  salePrice: 'Giá bán', displayName: 'Tên hiển thị', status: 'Trạng thái', name: 'Tên',
  phone: 'Số điện thoại', address: 'Địa chỉ', loginKey: 'Tên đăng nhập',
}));

function isIsoDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

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
  return <RecordTable size="small" pagination={false} rowKey="field" tableLayout="fixed" style={{ overflowWrap: 'anywhere' }}
    compactColumns={{ title: 'field' }}
    locale={{ emptyText: 'Không có dữ liệu trước/sau' }}
    columns={[
      { title: 'Trường', dataIndex: 'field' },
      { title: 'Giá trị cũ', dataIndex: 'before' },
      { title: 'Giá trị mới', dataIndex: 'after' },
    ]}
    dataSource={fields.map((field) => ({ field, before: before.get(field) ?? '—', after: after.get(field) ?? '—' }))}
  />;
}

const expandable: TableProps<AuditEntry>['expandable'] = {
  columnWidth: 108,
  expandedRowRender: (entry) => <AuditDetails entry={entry} />,
  expandIcon: ({ expanded, onExpand, record }) => <Button size="small" aria-expanded={expanded}
    onClick={(event) => onExpand(record, event)}>{expanded ? 'Thu gọn' : 'Chi tiết'}</Button>,
};

export function AuditLogPage() {
  useDocumentTitle(TITLE);
  const compact = useCompactLayout();
  const filterId = useId();
  const [showFilters, setShowFilters] = useState(false);
  const [detail, setDetail] = useState<AuditEntry | null>(null);
  const [params, setParams] = useSearchParams();
  const filters: AuditFilters = {};
  for (const key of ['action', 'actorId', 'fromDate', 'toDate'] as const) {
    const value = params.get(key);
    if (value) filters[key] = value;
  }
  const { fromDate, toDate } = filters;
  const invalidFromDate = Boolean(fromDate && !isIsoDate(fromDate));
  const invalidToDate = Boolean(toDate && !isIsoDate(toDate));
  const days = fromDate && toDate && !invalidFromDate && !invalidToDate ? countDaysInclusive({ fromDate, toDate }) : 1;
  const rangeError = invalidFromDate ? 'Ngày bắt đầu không hợp lệ. Hãy chọn lại ngày hoặc xóa bộ lọc.'
    : invalidToDate ? 'Ngày kết thúc không hợp lệ. Hãy chọn lại ngày hoặc xóa bộ lọc.'
    : days < 1 ? 'Ngày kết thúc phải từ ngày bắt đầu trở đi'
    : days > MAX_RANGE_DAYS ? 'Khoảng ngày tối đa 366 ngày' : undefined;
  const endHasError = invalidToDate || (!invalidFromDate && Boolean(rangeError));
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
  const filterCount = Object.keys(filters).length;

  return <Flex vertical gap="middle" style={{ minWidth: 0, maxWidth: '100%' }}>
    <Flex justify="space-between" align="center" wrap gap={8}>
      <Typography.Title level={1} style={{ margin: 0 }}>{TITLE}</Typography.Title>
      {compact && (
        <Button icon={<DownOutlined className="filter-toggle-chevron" aria-hidden="true" />}
          aria-expanded={showFilters} aria-controls={filterId} onClick={() => setShowFilters(previous => !previous)}>
          Bộ lọc{filterCount > 0 ? ` (${filterCount})` : ''}
        </Button>
      )}
    </Flex>
    <Flex id={filterId} className="audit-filters" hidden={compact && !showFilters}
      gap="small" wrap align="end" role="group" aria-label="Bộ lọc nhật ký">
      <label className="filter-field" style={{ width: 220, maxWidth: '100%' }}>Loại thao tác
        <Select aria-label="Loại thao tác" allowClear placeholder="Tất cả" style={{ width: '100%' }}
          loading={actions.isFetching} value={filters.action} disabled={actions.isError && !actions.data && !filters.action}
          options={actions.data?.map(({ action, label }) => ({ value: action, label }))}
          onChange={(action) => update({ action, page: undefined })} />
      </label>
      <label className="filter-field" style={{ width: 180, maxWidth: '100%' }}>Người thực hiện
        <Select aria-label="Người thực hiện" allowClear placeholder="Tất cả" style={{ width: '100%' }}
          loading={actors.isFetching} value={filters.actorId} disabled={actors.isError && !actors.data && !filters.actorId}
          options={actors.data?.map(({ userId, displayName }) => ({ value: userId, label: displayName }))}
          onChange={(actorId) => update({ actorId, page: undefined })} />
      </label>
      <label className="filter-field">Từ ngày
        <Input type="date" value={fromDate ?? ''} status={invalidFromDate ? 'error' : undefined}
          aria-invalid={invalidFromDate || undefined} aria-describedby={invalidFromDate ? DATE_ERROR_ID : undefined}
          onChange={(event) => update({ fromDate: event.target.value, page: undefined })} />
      </label>
      <label className="filter-field">Đến ngày
        <Input type="date" value={toDate ?? ''} status={endHasError ? 'error' : undefined}
          aria-invalid={endHasError || undefined} aria-describedby={endHasError ? DATE_ERROR_ID : undefined}
          onChange={(event) => update({ toDate: event.target.value, page: undefined })} />
      </label>
      <Button disabled={!filterCount}
        onClick={() => update({ action: undefined, actorId: undefined, fromDate: undefined, toDate: undefined, page: undefined })}>Xóa bộ lọc</Button>
    </Flex>
    {rangeError && <Typography.Text id={DATE_ERROR_ID} role="alert" type="danger">{rangeError}</Typography.Text>}
    {actions.isError && <ErrorAlert error={actions.error} retrying={actions.isFetching}
      retryLabel="Tải lại loại thao tác" onRetry={() => void actions.refetch()} />}
    {actors.isError && <ErrorAlert error={actors.error} retrying={actors.isFetching}
      retryLabel="Tải lại người thực hiện" onRetry={() => void actors.refetch()} />}
    {!rangeError && log.isError && <ErrorAlert error={log.error} retrying={log.isFetching}
      retryLabel="Tải lại nhật ký" onRetry={() => void log.refetch()} />}
    {!rangeError && (!log.isError || log.data) && <RecordTable<AuditEntry> scroll={{ x: 720 }} rowKey="id"
      compactColumns={{ title: 'action', actions: 'details' }}
      loading={log.isFetching} dataSource={log.data?.items} tableLayout="fixed" style={{ overflowWrap: 'anywhere' }}
      locale={{ emptyText: log.isPending ? 'Đang tải nhật ký…' : filterCount
        ? 'Không có thao tác khớp với bộ lọc' : 'Không có nhật ký thao tác' }}
      columns={[
        { title: 'Thời điểm', dataIndex: 'createdAt', width: 134, render: (value: string) => time.format(new Date(value)) },
        { title: 'Loại thao tác', key: 'action', render: (_, entry) => entry.actionLabel ?? entry.action },
        { title: 'Người thực hiện', key: 'actor', render: (_, entry) => entry.actorName ?? (entry.actorId ? `Chưa rõ tên · ${entry.actorId}` : 'Hệ thống') },
        { title: 'Đối tượng', key: 'entity', render: (_, entry) => [entry.entity, entry.entityId].filter(Boolean).join(' · ') },
        ...(compact ? [{
          key: 'details',
          render: (_: unknown, entry: AuditEntry) => (
            <Button size="small" onClick={() => setDetail(entry)}>Chi tiết</Button>
          ),
        }] : []),
      ]}
      expandable={compact ? undefined : expandable}
      pagination={{ ...toAntdPagination(log.data, request), showSizeChanger: true }}
      onChange={(pagination) => {
        const next = fromAntdChange(pagination, undefined, request);
        update({ page: String(next.page), size: String(next.size) });
      }}
    />}
    <Modal open={detail !== null} title={detail?.actionLabel ?? detail?.action}
      onCancel={() => setDetail(null)} footer={<Button onClick={() => setDetail(null)}>Đóng</Button>}>
      {detail && <AuditDetails entry={detail} />}
    </Modal>
  </Flex>;
}
