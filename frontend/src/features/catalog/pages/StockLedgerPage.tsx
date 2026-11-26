/**
 * Lịch sử xuất nhập kho (FR-28, KHO-07; xem các lần Tồn kho thay đổi, FR-22). Người giữ: Hiếu — features/catalog
 * (AD-20). Chỉ Chủ cửa hàng (route `OWNER_ONLY`; backend chặn Nhân viên).
 *
 * Gọi `GET /api/v1/inventory/ledger` của Anh (KHO-01) qua shared/api, không import features/inventory; khóa
 * `['inventory', 'ledger', { ...bộ lọc, page, size }]`. Bảng mới nhất trước (backend sắp): thời điểm theo giờ Việt
 * Nam, loại giao dịch bằng tiếng Việt, Sản phẩm / Biến thể ("Sản phẩm không còn" khi Biến thể không tra được), số
 * lượng thay đổi có dấu (+ xanh, − đỏ), tồn sau thay đổi, người thực hiện, mã chứng từ rút gọn (nút sao chép lấy mã
 * đầy đủ). Phân trang phía server. Mở lại màn là tải lại (`staleTime: 0`): sổ kho đổi theo mọi lần bán, nhập, kiểm.
 *
 * Bộ lọc (Sản phẩm / Biến thể, loại giao dịch, khoảng ngày) nằm trên URL query (`api/stockLedger.ts`), nên tải lại
 * trang hay gửi link vẫn giữ; đổi bộ lọc thì bảng về trang đầu. Khoảng ngày quá 366 ngày thì không gửi, báo dưới ô
 * ngày (như backend); lỗi `400` của backend trên khoảng ngày cũng báo ở đó. Lỗi tải khác: khung lỗi và nút "Thử lại"
 * (đã có bảng thì giữ bảng, khung lỗi nằm trên).
 */
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Button, Flex, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { isApiError } from '../../../shared/api/client';
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/pagination';
import type { PageRequest } from '../../../shared/api/pagination';
import { ErrorAlert, RecordTable, fromAntdChange, toAntdPagination, useDocumentTitle } from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { StockLedgerEntry } from '../../../shared/types/inventory';
import {
  LEDGER_RANGE_TOO_LONG,
  documentTypeInfo,
  fetchStockLedger,
  hasStockLedgerFilters,
  isStockDocumentType,
  ledgerRangeError,
  stockLedgerFiltersFromSearchParams,
  stockLedgerQueryKey,
  toStockLedgerSearchParams,
  withStockLedgerFilters,
} from '../api/stockLedger';
import type { StockLedgerFilters } from '../api/stockLedger';
import { ledgerDocumentTitles, readLedgerDocument, withLedgerDocument } from '../api/stockLedgerDocuments';
import { StockLedgerDocumentModal } from '../components/StockLedgerDocumentModal';
import { StockLedgerFilterBar } from '../components/StockLedgerFilterBar';

const TITLE = 'Lịch sử xuất nhập kho';
const EMPTY = '—';
const MISSING_PRODUCT = 'Sản phẩm không còn';
const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];

/** Ngày giờ theo giờ Việt Nam, dù máy đặt múi giờ nào; ghép thành "dd/MM/yyyy HH:mm". */
const dateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: VIETNAM_TIME_ZONE,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
const quantityFormatter = new Intl.NumberFormat('vi-VN');

function formatDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const part = Object.fromEntries(dateTimeFormatter.formatToParts(date).map(({ type, value }) => [type, value]));
  return `${part.day}/${part.month}/${part.year} ${part.hour}:${part.minute}`;
}

/** Số lượng thay đổi có dấu: `+24`, `−3` (dấu trừ U+2212), `0`. */
function formatDelta(delta: number): string {
  if (delta > 0) return `+${quantityFormatter.format(delta)}`;
  if (delta < 0) return `−${quantityFormatter.format(-delta)}`;
  return '0';
}

/** Mã chứng từ rút gọn: 8 ký tự đầu, chữ HOA. */
function shortDocumentId(id: string): string {
  return id.slice(0, 8).toUpperCase();
}

/** Tên Sản phẩm / Biến thể của một dòng, một dòng chữ (ô chọn Sản phẩm khi chỉ biết Biến thể). */
function entryProductLabel(entry: StockLedgerEntry): string {
  if (entry.productName === null) return MISSING_PRODUCT;
  return entry.variantName ? `${entry.productName} · ${entry.variantName}` : entry.productName;
}

/** Câu lỗi của backend trên khoảng ngày (`400` có `errors[]` trên `fromDate`/`toDate`), để hiện dưới ô ngày. */
function dateFieldError(error: unknown): string | undefined {
  if (!isApiError(error) || error.status !== 400) return undefined;
  const fieldError = error.errors.find(({ field }) => field === 'fromDate' || field === 'toDate');
  if (!fieldError) return undefined;
  return fieldError.message || error.detail || LEDGER_RANGE_TOO_LONG;
}

type LedgerRow = StockLedgerEntry & { key: string };

const COLUMNS: TableColumnsType<LedgerRow> = [
  {
    title: 'Thời điểm',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 146,
    render: (createdAt: string) => <span style={{ whiteSpace: 'nowrap' }}>{formatDateTime(createdAt)}</span>,
  },
  {
    title: 'Loại giao dịch',
    dataIndex: 'documentType',
    key: 'documentType',
    width: 120,
    render: (documentType: string) => {
      const { label, color } = documentTypeInfo(documentType);
      return <Tag color={color}>{label}</Tag>;
    },
  },
  {
    title: 'Sản phẩm / Biến thể',
    key: 'product',
    render: (_, entry) =>
      entry.productName === null ? (
        <Typography.Text type="secondary" italic>
          {MISSING_PRODUCT}
        </Typography.Text>
      ) : (
        <Flex vertical>
          <span>{entry.productName}</span>
          {entry.variantName ? <Typography.Text type="secondary">{entry.variantName}</Typography.Text> : null}
        </Flex>
      ),
  },
  {
    title: 'Thay đổi',
    dataIndex: 'delta',
    key: 'delta',
    width: 90,
    align: 'right',
    render: (delta: number) => (
      <Typography.Text strong type={delta > 0 ? 'success' : delta < 0 ? 'danger' : undefined}>
        {formatDelta(delta)}
      </Typography.Text>
    ),
  },
  {
    title: 'Tồn sau thay đổi',
    dataIndex: 'quantityAfter',
    key: 'quantityAfter',
    width: 110,
    align: 'right',
    render: (quantityAfter: number) => quantityFormatter.format(quantityAfter),
  },
  {
    title: 'Người thực hiện',
    dataIndex: 'actorName',
    key: 'actorName',
    width: 142,
    render: (actorName: string | null) => actorName ?? EMPTY,
  },
];

function documentColumn(params: URLSearchParams): TableColumnsType<LedgerRow>[number] {
  return {
    title: 'Mã chứng từ',
    key: 'documentId',
    width: 135,
    render: (_, { documentId, documentType }) => {
      const code = shortDocumentId(documentId);
      return (
        <Typography.Text code style={{ whiteSpace: 'nowrap' }}
          copyable={{ text: documentId, tooltips: ['Sao chép mã đầy đủ', 'Đã sao chép'] }}>
          {isStockDocumentType(documentType) ? (
            <Link
              preventScrollReset
              to={{ search: withLedgerDocument(params, { type: documentType, id: documentId }).toString() }}
              aria-label={`Mở ${ledgerDocumentTitles[documentType].toLocaleLowerCase('vi-VN')} ${code}`}
              aria-haspopup="dialog"
              style={{ textDecoration: 'underline', textUnderlineOffset: 3 }}
            >
              {code}
            </Link>
          ) : code}
        </Typography.Text>
      );
    },
  };
}

export function StockLedgerPage() {
  useDocumentTitle(TITLE);
  const [searchParams, setSearchParams] = useSearchParams();
  const reference = readLedgerDocument(searchParams);
  const columns = useMemo(() => [...COLUMNS, documentColumn(searchParams)], [searchParams]);
  const filters = useMemo(() => stockLedgerFiltersFromSearchParams(searchParams), [searchParams]);
  const filtered = hasStockLedgerFilters(filters);
  const rangeError = filters.from && filters.to ? ledgerRangeError(filters.from, filters.to) : undefined;
  const search = useMemo(() => toStockLedgerSearchParams(filters), [filters]);
  const searchKey = JSON.stringify(search);

  // Trang của bảng; bộ lọc gửi đi đổi thì về trang đầu, giữ kích thước trang.
  const [paging, setPaging] = useState<{ searchKey: string; request: PageRequest }>(() => ({
    searchKey,
    request: { page: 0, size: DEFAULT_PAGE_SIZE },
  }));
  const request = paging.searchKey === searchKey ? paging.request : { ...paging.request, page: 0 };

  const ledger = useQuery({
    queryKey: [...stockLedgerQueryKey, { ...search, page: request.page, size: request.size }],
    queryFn: ({ signal }) => fetchStockLedger(request, search, signal),
    // Khoảng ngày quá dài: không gửi (backend cũng từ chối)
    enabled: !rangeError,
    // Sổ kho đổi theo mọi lần bán, nhập, kiểm kho (không phải lần nào cũng có mutation invalidate): mở lại màn là tải lại
    staleTime: 0,
    placeholderData: keepPreviousData,
  });

  // Thay URL hiện tại (không thêm vào lịch sử) để mỗi lần đổi bộ lọc không thành một bước "Quay lại".
  const onFiltersChange = (next: StockLedgerFilters) => {
    setPaging((current) => ({
      searchKey: JSON.stringify(toStockLedgerSearchParams(next)),
      request: { ...current.request, page: 0 },
    }));
    setSearchParams((current) => withStockLedgerFilters(current, next), { replace: true });
  };
  const clearFilters = () => onFiltersChange({});

  const backendDateError = dateFieldError(ledger.error);
  const dateError = rangeError ?? backendDateError;
  const loadFailed = ledger.isError && !backendDateError;
  const data = dateError ? undefined : ledger.data;
  const rows = useMemo<LedgerRow[]>(
    () =>
      (data?.items ?? []).map((entry, index) => ({
        ...entry,
        key: `${data?.page ?? 0}:${index}:${entry.documentId}:${entry.variantId}`,
      })),
    [data],
  );

  // Ô chọn Sản phẩm mở từ link chỉ có `variantId`: lấy tên từ dòng sổ kho của Biến thể đó
  const variantLabel = useMemo(() => {
    const entry = filters.variantId ? data?.items.find((e) => e.variantId === filters.variantId) : undefined;
    return entry ? entryProductLabel(entry) : undefined;
  }, [data, filters.variantId]);

  const emptyText = () => {
    if (dateError) return 'Chọn lại khoảng ngày để xem lịch sử.';
    if (ledger.isPending) return 'Đang tải…';
    if (!filtered) return 'Chưa có lần xuất nhập nào.';
    return (
      <Flex vertical align="center" gap={8}>
        <span>Không có dòng nào khớp bộ lọc.</span>
        <Button onClick={clearFilters}>Xóa bộ lọc</Button>
      </Flex>
    );
  };

  return (
    <div className="catalog-page">
      <Typography.Title level={1} style={{ marginTop: 0 }}>
        {TITLE}
      </Typography.Title>
      <StockLedgerFilterBar
        value={filters}
        onChange={onFiltersChange}
        dateError={dateError}
        variantLabel={variantLabel}
      />
      {loadFailed ? (
        <Flex vertical align="flex-start" style={{ marginBottom: 16 }}>
          <ErrorAlert error={ledger.error} style={{ alignSelf: 'stretch', marginBottom: 8 }} />
          <Button type="primary" loading={ledger.isFetching} onClick={() => void ledger.refetch()}>
            Thử lại
          </Button>
        </Flex>
      ) : null}
      {/* Tải lại lỗi mà đã có bảng (dữ liệu cũ của cùng bộ lọc): giữ bảng, khung lỗi ở trên */}
      {loadFailed && !data ? null : (
        <RecordTable<LedgerRow>
          compactColumns={{ title: 'product', status: 'documentType' }}
          rowKey="key"
          columns={columns}
          dataSource={rows}
          loading={!dateError && ledger.isFetching}
          tableLayout="fixed"
          scroll={{ x: 940 }}
          style={{ overflowWrap: 'anywhere' }}
          locale={{ emptyText: emptyText() }}
          pagination={{
            ...toAntdPagination(data, request),
            showSizeChanger: true,
            pageSizeOptions: PAGE_SIZE_OPTIONS,
            showTotal: (total) => `Tổng ${total.toLocaleString('vi-VN')} dòng`,
          }}
          onChange={(pagination) => setPaging({ searchKey, request: fromAntdChange(pagination, undefined, request) })}
        />
      )}
      <StockLedgerDocumentModal reference={reference} searchParams={searchParams}
        onClose={() => setSearchParams((current) => withLedgerDocument(current), { replace: true, preventScrollReset: true })} />
    </div>
  );
}
