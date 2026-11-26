import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Input, InputNumber, Space, Tag, Typography } from 'antd';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert, RecordTable, useDocumentTitle } from '../../../shared/components';
import type { Uuid } from '../../../shared/types/common';

const MAX_QUANTITY = 2_147_483_647;
const DRAFT_PAGE_SIZE = 20;

interface StockCandidate {
  variantId: Uuid;
  productName: string;
  variantName: string | null;
  sku: string;
  quantity: number;
  isActive: boolean;
}

interface DraftLine {
  variantId: Uuid;
  label: string;
  systemQuantity: number;
  countedQuantity: number | null;
  reason: string;
}

interface Submission {
  key: string;
  body: { note: string; lines: Omit<DraftLine, 'label'>[] };
}

interface Stocktake {
  stocktakeId: Uuid;
  lines: { delta: number }[];
}

export function StocktakesPage() {
  useDocumentTitle('Kiểm kho');
  const queryClient = useQueryClient();
  const [search, setSearch] = useState({ q: '', page: 0, size: 20 });
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [draftPage, setDraftPage] = useState(1);
  const [note, setNote] = useState('');
  const [pending, setPending] = useState<Submission | null>(null);

  const stocks = useQuery({
    queryKey: ['inventory', 'stock', 'stocktake', search],
    queryFn: ({ signal }) => api.get<PageResponse<StockCandidate>>('/api/v1/inventory/stock', {
      query: { ...search, includeInactive: true }, signal,
    }),
    enabled: Boolean(search.q),
    retry: false,
  });

  const save = useMutation({
    mutationFn: ({ key, body }: Submission) =>
      api.post<Stocktake>('/api/v1/inventory/stocktakes', body, { idempotencyKey: key }),
    onSuccess: () => {
      setPending(null);
      setLines([]);
      setDraftPage(1);
      setNote('');
    },
    onError: error => {
      if (shouldReuseIdempotencyKey(error)) return;
      setPending(null);
      if (isApiError(error) && error.type === '/errors/inventory/stocktake-stale') {
        const quantities = error.extensions.currentQuantities;
        if (quantities && typeof quantities === 'object') {
          const updated = new Map(Object.entries(quantities));
          setLines(current => current.map(line => {
            const quantity = updated.get(line.variantId);
            return typeof quantity === 'number' && Number.isInteger(quantity) && quantity >= 0
              ? { ...line, systemQuantity: quantity, reason: '' }
              : line;
          }));
        }
      }
    },
    onSettled: () => {
      for (const queryKey of [['inventory'], ['sales', 'sellable'], ['sales', 'quote'], ['reporting']]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
  });

  const locked = pending !== null;
  const visibleDraftPage = Math.min(draftPage, Math.max(1, Math.ceil(lines.length / DRAFT_PAGE_SIZE)));
  const draftOffset = (visibleDraftPage - 1) * DRAFT_PAGE_SIZE;
  const countsReady = lines.every(line =>
    line.countedQuantity !== null && Number.isInteger(line.countedQuantity) &&
    line.countedQuantity >= 0 && line.countedQuantity <= MAX_QUANTITY);
  const reasonsReady = lines.every(line => line.countedQuantity === line.systemQuantity || line.reason.trim().length > 0);
  const canSubmit = lines.length > 0 && countsReady && reasonsReady;
  let submitHint = '';
  if (locked && save.isError) submitHint = 'Gửi lại cùng phiếu sau khi kết nối ổn định để tránh ghi nhận hai lần.';
  else if (!locked && lines.length > 0 && !canSubmit) {
    submitHint = countsReady ? 'Nhập lý do cho từng dòng có chênh lệch trước khi lưu.'
      : 'Nhập số đếm thực tế cho tất cả các dòng trước khi lưu.';
  }

  function add(stock: StockCandidate) {
    const index = lines.findIndex(line => line.variantId === stock.variantId);
    setDraftPage(1 + Math.floor((index < 0 ? lines.length : index) / DRAFT_PAGE_SIZE));
    setLines(current => current.some(line => line.variantId === stock.variantId) ? current : [
      ...current,
      {
        variantId: stock.variantId,
        label: [stock.productName, stock.variantName, stock.sku].filter(Boolean).join(' · '),
        systemQuantity: stock.quantity,
        countedQuantity: null,
        reason: '',
      },
    ]);
  }

  function edit(variantId: Uuid, change: Partial<Pick<DraftLine, 'countedQuantity' | 'reason'>>) {
    setLines(current => current.map(line => line.variantId === variantId ? { ...line, ...change } : line));
  }

  function submit() {
    if (save.isPending) return;
    if (pending) {
      save.mutate(pending);
      return;
    }
    if (!canSubmit) return;
    const request = {
      key: newIdempotencyKey(),
      body: {
        note: note.trim(),
        lines: lines.map(({ variantId, systemQuantity, countedQuantity, reason }) => ({
          variantId, systemQuantity, countedQuantity, reason: reason.trim(),
        })),
      },
    };
    setPending(request);
    save.mutate(request);
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={1} style={{ margin: 0 }}>Kiểm kho</Typography.Title>
      {save.data && (
        <Alert type="success" showIcon title="Đã lưu phiếu kiểm kho"
          description={save.data.lines.filter(line => line.delta !== 0).length + ' dòng đã điều chỉnh tồn.'} />
      )}
      <Card title="Chọn hàng cần kiểm">
        <Space orientation="vertical" size={4} style={{ display: 'flex', maxWidth: 460, marginBottom: search.q ? 12 : 0 }}>
          <label htmlFor="stocktake-search">Tìm hàng cần kiểm</label>
          <Input.Search
            id="stocktake-search"
            placeholder="Tên sản phẩm, SKU hoặc mã vạch"
            enterButton="Tìm hàng"
            allowClear
            disabled={locked}
            onSearch={value => {
              const q = value.trim();
              if (q && q === search.q && search.page === 0) void stocks.refetch();
              else setSearch(current => ({ ...current, q, page: 0 }));
            }}
          />
        </Space>
        {search.q && stocks.isError && <ErrorAlert error={stocks.error} retryLabel="Thử lại tìm hàng"
          onRetry={() => void stocks.refetch()} retrying={stocks.isFetching} />}
        {search.q && !stocks.isError && (
          <RecordTable<StockCandidate>
            compactColumns={{ title: 'name', status: 'status', actions: 'actions' }}
            rowKey="variantId"
            loading={stocks.isFetching}
            dataSource={stocks.data?.items ?? []}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: stocks.isPending ? 'Đang tìm hàng…' : 'Không tìm thấy hàng tồn kho.' }}
            pagination={{
              current: search.page + 1,
              pageSize: search.size,
              total: stocks.data?.total ?? 0,
              showTotal: total => total > 0 ? `Trang ${search.page + 1}/${Math.ceil(total / search.size)}` : null,
              showSizeChanger: false,
              onChange: page => setSearch(current => ({ ...current, page: page - 1 })),
            }}
            columns={[
              { title: 'Sản phẩm', key: 'name', render: (_, row) => [row.productName, row.variantName].filter(Boolean).join(' · ') },
              { title: 'SKU', dataIndex: 'sku' },
              { title: 'Tồn hệ thống', dataIndex: 'quantity', align: 'right' },
              { title: 'Trạng thái', key: 'status', render: (_, row) => row.isActive
                ? <Tag color="success">Đang kinh doanh</Tag> : <Tag>Ngừng kinh doanh</Tag> },
              { title: 'Thao tác', key: 'actions', render: (_, row) => {
                const added = lines.some(line => line.variantId === row.variantId);
                return <Button aria-label={`${added ? 'Đã thêm' : 'Thêm'} ${row.productName} · ${row.sku}`}
                  disabled={added || locked || stocks.isFetching} onClick={() => add(row)}>
                  {added ? 'Đã thêm' : 'Thêm'}
                </Button>;
              } },
            ]}
          />
        )}
      </Card>

      <Card title="Phiếu kiểm kho">
        <Space orientation="vertical" style={{ width: '100%' }}>
          <Typography.Text>Nhập số đếm thực tế, kể cả 0. Dòng có chênh lệch cần nhập lý do.</Typography.Text>
          {save.isError && <ErrorAlert error={save.error} />}
          <RecordTable<DraftLine>
            compactColumns={{ title: 'label', actions: 'actions' }}
            rowKey="variantId"
            dataSource={lines}
            pagination={{
              current: visibleDraftPage,
              pageSize: DRAFT_PAGE_SIZE,
              hideOnSinglePage: true,
              showSizeChanger: false,
              onChange: setDraftPage,
            }}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: 'Chưa có hàng trong phiếu.' }}
            columns={[
              { title: 'Hàng kiểm', dataIndex: 'label' },
              { title: 'Tồn hệ thống', dataIndex: 'systemQuantity', align: 'right' },
              { title: 'Số đếm thực tế', key: 'countedQuantity', render: (_, line, index) => (
                <InputNumber
                  aria-label={'Số đếm thực tế dòng ' + (draftOffset + index + 1)}
                  aria-required="true"
                  inputMode="numeric"
                  min={0}
                  max={MAX_QUANTITY}
                  precision={0}
                  value={line.countedQuantity}
                  disabled={locked}
                  onChange={countedQuantity => edit(line.variantId, { countedQuantity })}
                />
              ) },
              { title: 'Chênh lệch', key: 'delta', align: 'right', render: (_, line) => {
                if (line.countedQuantity === null) return '—';
                const delta = line.countedQuantity - line.systemQuantity;
                return delta > 0 ? '+' + delta : delta;
              } },
              { title: 'Lý do chênh lệch', key: 'reason', render: (_, line, index) => (
                <Input
                  aria-label={'Lý do chênh lệch dòng ' + (draftOffset + index + 1)}
                  aria-required={line.countedQuantity !== null && line.countedQuantity !== line.systemQuantity}
                  placeholder="Bắt buộc nếu lệch"
                  maxLength={200}
                  value={line.reason}
                  disabled={locked || line.countedQuantity === null || line.countedQuantity === line.systemQuantity}
                  onChange={event => edit(line.variantId, { reason: event.target.value })}
                />
              ) },
              { title: 'Thao tác', key: 'actions', render: (_, line, index) => (
                <Button danger aria-label={`Bỏ dòng ${draftOffset + index + 1}`} disabled={locked} onClick={() =>
                  setLines(current => current.filter(row => row.variantId !== line.variantId))}>Bỏ dòng</Button>
              ) },
            ]}
          />
          <Space orientation="vertical" size={4} style={{ display: 'flex' }}>
            <label htmlFor="stocktake-note">Ghi chú phiếu kiểm kho (không bắt buộc)</label>
            <Input id="stocktake-note" maxLength={200} value={note} disabled={locked}
              onChange={event => setNote(event.target.value)} />
          </Space>
          {submitHint && <Typography.Text id="stocktake-submit-hint">{submitHint}</Typography.Text>}
          <Button
            type="primary"
            loading={save.isPending}
            disabled={!pending && !canSubmit}
            aria-describedby={submitHint ? 'stocktake-submit-hint' : undefined}
            onClick={submit}
          >
            {pending && save.isError ? 'Thử lại lưu phiếu' : 'Lưu phiếu kiểm kho'}
          </Button>
        </Space>
      </Card>
    </Space>
  );
}
