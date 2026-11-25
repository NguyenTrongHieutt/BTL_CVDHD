import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Input, InputNumber, Space, Table, Tag, Typography } from 'antd';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert, useDocumentTitle } from '../../../shared/components';
import type { Uuid } from '../../../shared/types/common';

const MAX_QUANTITY = 2_147_483_647;

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
  const canSubmit = lines.length > 0 && lines.every(line =>
    line.countedQuantity !== null && Number.isInteger(line.countedQuantity) &&
    line.countedQuantity >= 0 && line.countedQuantity <= MAX_QUANTITY &&
    (line.countedQuantity === line.systemQuantity || line.reason.trim().length > 0));

  function add(stock: StockCandidate) {
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
      <Typography.Title level={2} style={{ margin: 0 }}>Kiểm kho</Typography.Title>
      {save.data && (
        <Alert type="success" showIcon title="Đã lưu phiếu kiểm kho"
          description={save.data.lines.filter(line => line.delta !== 0).length + ' dòng đã điều chỉnh tồn.'} />
      )}
      <Card title="Chọn hàng cần kiểm">
        <Input.Search
          aria-label="Tìm hàng cần kiểm"
          placeholder="Tên sản phẩm, SKU hoặc mã vạch"
          enterButton="Tìm hàng"
          allowClear
          disabled={locked}
          style={{ maxWidth: 460, marginBottom: search.q ? 12 : 0 }}
          onSearch={value => {
            const q = value.trim();
            if (q && q === search.q && search.page === 0) void stocks.refetch();
            else setSearch(current => ({ ...current, q, page: 0 }));
          }}
        />
        {search.q && stocks.isError && <ErrorAlert error={stocks.error} />}
        {search.q && (
          <Table<StockCandidate>
            rowKey="variantId"
            loading={stocks.isFetching}
            dataSource={stocks.data?.items ?? []}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: 'Không tìm thấy hàng tồn kho.' }}
            pagination={{
              current: search.page + 1,
              pageSize: search.size,
              total: stocks.data?.total ?? 0,
              showSizeChanger: false,
              onChange: page => setSearch(current => ({ ...current, page: page - 1 })),
            }}
            columns={[
              { title: 'Sản phẩm', render: (_, row) => [row.productName, row.variantName].filter(Boolean).join(' · ') },
              { title: 'SKU', dataIndex: 'sku' },
              { title: 'Tồn hệ thống', dataIndex: 'quantity', align: 'right' },
              { title: 'Trạng thái', render: (_, row) => row.isActive ? 'Đang kinh doanh' : <Tag>Ngừng kinh doanh</Tag> },
              { title: 'Thao tác', render: (_, row) => {
                const added = lines.some(line => line.variantId === row.variantId);
                return <Button disabled={added || locked || stocks.isFetching} onClick={() => add(row)}>
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
          <Table<DraftLine>
            rowKey="variantId"
            dataSource={lines}
            pagination={false}
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: 'Chưa có hàng trong phiếu.' }}
            columns={[
              { title: 'Hàng kiểm', dataIndex: 'label' },
              { title: 'Tồn hệ thống', dataIndex: 'systemQuantity', align: 'right' },
              { title: 'Số đếm thực tế', render: (_, line, index) => (
                <InputNumber
                  aria-label={'Số đếm thực tế dòng ' + (index + 1)}
                  min={0}
                  max={MAX_QUANTITY}
                  precision={0}
                  value={line.countedQuantity}
                  disabled={locked}
                  onChange={countedQuantity => edit(line.variantId, { countedQuantity })}
                />
              ) },
              { title: 'Chênh lệch', align: 'right', render: (_, line) => {
                if (line.countedQuantity === null) return '—';
                const delta = line.countedQuantity - line.systemQuantity;
                return delta > 0 ? '+' + delta : delta;
              } },
              { title: 'Lý do chênh lệch', render: (_, line, index) => (
                <Input
                  aria-label={'Lý do chênh lệch dòng ' + (index + 1)}
                  placeholder="Bắt buộc nếu lệch"
                  maxLength={200}
                  value={line.reason}
                  disabled={locked || line.countedQuantity === null || line.countedQuantity === line.systemQuantity}
                  onChange={event => edit(line.variantId, { reason: event.target.value })}
                />
              ) },
              { title: 'Thao tác', render: (_, line) => (
                <Button danger disabled={locked} onClick={() =>
                  setLines(current => current.filter(row => row.variantId !== line.variantId))}>Bỏ dòng</Button>
              ) },
            ]}
          />
          <Input
            aria-label="Ghi chú phiếu kiểm kho"
            placeholder="Ghi chú phiếu kiểm kho (nếu có)"
            maxLength={200}
            value={note}
            disabled={locked}
            onChange={event => setNote(event.target.value)}
          />
          <Button
            type="primary"
            loading={save.isPending}
            disabled={!pending && !canSubmit}
            onClick={submit}
          >
            {pending && save.isError ? 'Thử lại lưu phiếu' : 'Lưu phiếu kiểm kho'}
          </Button>
        </Space>
      </Card>
    </Space>
  );
}
