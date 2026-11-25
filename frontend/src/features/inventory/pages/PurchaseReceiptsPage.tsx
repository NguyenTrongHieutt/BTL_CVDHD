import { useDeferredValue, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Input, InputNumber, Select, Space, Table, Typography } from 'antd';
import { api, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import type { PageResponse } from '../../../shared/api/client';
import { ErrorAlert, MoneyText, ProductPicker, useDocumentTitle } from '../../../shared/components';
import type { SupplierSummary } from '../../../shared/types/catalog';
import type { IsoDateTime, Uuid } from '../../../shared/types/common';
import type { SellableItem } from '../../../shared/types/sales';

const MAX_QUANTITY = 2_147_483_647;

interface DraftLine {
  variantId: Uuid;
  label: string;
  quantity: number | null;
  unitCost: number | null;
}

interface Receipt {
  receiptId: Uuid;
  code: string;
  receivedAt: IsoDateTime;
  totalAmount: number;
}

interface Submission {
  key: string;
  request: { supplierId: Uuid; note: string; lines: Omit<DraftLine, 'label'>[] };
}

export function PurchaseReceiptsPage() {
  useDocumentTitle('Phiếu nhập');
  const queryClient = useQueryClient();
  const [supplier, setSupplier] = useState<{ value: Uuid; label: string }>();
  const [supplierSearch, setSupplierSearch] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [pending, setPending] = useState<Submission | null>(null);
  const supplierQuery = useDeferredValue(supplierSearch.trim());

  const suppliers = useQuery({
    queryKey: ['catalog', 'suppliers', 'purchase-receipt-options', supplierQuery],
    queryFn: ({ signal }) => api.get<PageResponse<SupplierSummary>>('/api/v1/catalog/suppliers', {
      signal, query: { q: supplierQuery || undefined, page: 0, size: 30 },
    }),
  });
  const save = useMutation({
    mutationFn: ({ key, request }: Submission) => api.post<Receipt>(
      '/api/v1/inventory/purchase-receipts', request, { idempotencyKey: key },
    ),
    onSuccess: () => {
      setPending(null);
      setSupplier(undefined);
      setSupplierSearch('');
      setLines([]);
      setNote('');
      for (const queryKey of [
        ['inventory', 'stock'], ['inventory', 'low-stock'], ['inventory', 'ledger'], ['sales', 'sellable'],
      ]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) setPending(null);
    },
  });

  const locked = pending !== null;
  const total = lines.reduce((sum, line) => sum + (line.quantity ?? 0) * (line.unitCost ?? 0), 0);
  const validTotal = Number.isSafeInteger(total);
  const canSubmit = supplier !== undefined && lines.length > 0 && validTotal && lines.every(
    ({ quantity, unitCost }) => quantity !== null && Number.isInteger(quantity)
      && quantity > 0 && quantity <= MAX_QUANTITY && unitCost !== null
      && Number.isSafeInteger(unitCost) && unitCost > 0,
  );

  function addProduct(product: SellableItem) {
    setLines((current) => {
      const label = [product.name, product.variantName].filter(Boolean).join(' · ');
      if (!current.some((line) => line.variantId === product.variantId)) {
        return [...current, { variantId: product.variantId, label, quantity: 1, unitCost: null }];
      }
      return current.map((line) => line.variantId === product.variantId
        ? { ...line, quantity: Math.min(MAX_QUANTITY, (line.quantity ?? 0) + 1) }
        : line);
    });
  }

  function changeLine(variantId: Uuid, change: Partial<Pick<DraftLine, 'quantity' | 'unitCost'>>) {
    setLines((current) => current.map((line) => line.variantId === variantId ? { ...line, ...change } : line));
  }

  function submit() {
    if (save.isPending) return;
    if (pending) {
      save.mutate(pending);
      return;
    }
    if (!supplier || !canSubmit) return;
    const submission = {
      key: newIdempotencyKey(),
      request: {
        supplierId: supplier.value,
        note,
        lines: lines.map(({ variantId, quantity, unitCost }) => ({ variantId, quantity, unitCost })),
      },
    };
    setPending(submission);
    save.mutate(submission);
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={2} style={{ margin: 0 }}>Phiếu nhập</Typography.Title>
      {save.isSuccess && (
        <Alert
          type="success"
          showIcon
          title="Đã lưu phiếu nhập và cập nhật tồn kho."
          description={<Space wrap>
            <Typography.Text copyable>{save.data.code}</Typography.Text>
            <MoneyText value={save.data.totalAmount} />
          </Space>}
        />
      )}
      <Card title="Thông tin phiếu nhập">
        <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
          <label>
            Nhà cung cấp
            <Select<{ value: Uuid; label: string }>
              aria-label="Nhà cung cấp"
              placeholder="Chọn hoặc tìm nhà cung cấp"
              style={{ width: '100%', marginTop: 4 }}
              disabled={locked}
              loading={suppliers.isFetching}
              allowClear
              labelInValue
              showSearch={{ onSearch: setSupplierSearch, filterOption: false }}
              value={supplier}
              options={suppliers.data?.items.map(({ supplierId, name, code }) => ({
                value: supplierId, label: code ? `${name} · ${code}` : name,
              }))}
              onChange={(value) => {
                setSupplier(value);
                setSupplierSearch('');
              }}
            />
          </label>
          {suppliers.isError && <ErrorAlert error={suppliers.error} />}
          <label>
            Ghi chú
            <Input.TextArea value={note} onChange={(event) => setNote(event.target.value)}
              disabled={locked} maxLength={500} rows={2} />
          </label>
        </Space>
      </Card>
      <Card title="Sản phẩm nhập">
        <ProductPicker onPick={addProduct} allowOutOfStock disabled={locked} />
        <Table<DraftLine>
          rowKey="variantId"
          dataSource={lines}
          pagination={false}
          scroll={{ x: 'max-content' }}
          style={{ marginTop: 16 }}
          locale={{ emptyText: 'Chưa có sản phẩm trong phiếu.' }}
          columns={[
            { title: 'Sản phẩm', dataIndex: 'label' },
            {
              title: 'Số lượng',
              render: (_, line) => (
                <InputNumber<number> aria-label={`Số lượng ${line.label}`} value={line.quantity}
                  min={1} max={MAX_QUANTITY} precision={0} disabled={locked}
                  onChange={(quantity) => changeLine(line.variantId, { quantity })} />
              ),
            },
            {
              title: 'Giá nhập (₫)',
              render: (_, line) => (
                <InputNumber<number> aria-label={`Giá nhập ${line.label}`} value={line.unitCost}
                  min={1} max={Number.MAX_SAFE_INTEGER} precision={0}
                  placeholder="Nhập giá"
                  style={{ width: 145 }} disabled={locked}
                  onChange={(unitCost) => changeLine(line.variantId, { unitCost })} />
              ),
            },
            {
              title: 'Thành tiền',
              align: 'right',
              render: (_, line) => {
                const amount = (line.quantity ?? 0) * (line.unitCost ?? 0);
                return Number.isSafeInteger(amount) ? <MoneyText value={amount} /> : 'Vượt giới hạn';
              },
            },
            {
              title: 'Thao tác',
              render: (_, line) => (
                <Button danger type="text" disabled={locked} aria-label={`Bỏ ${line.label}`}
                  onClick={() => setLines((current) => current.filter(({ variantId }) => variantId !== line.variantId))}>
                  Bỏ
                </Button>
              ),
            },
          ]}
        />
      </Card>
      {save.isError && <ErrorAlert error={save.error} />}
      {save.isError && pending && <Alert type="info" title="Thử gửi lại cùng phiếu để tránh nhập kho hai lần." />}
      <Space wrap size="large">
        <Typography.Text strong>
          Tổng tiền {validTotal ? <MoneyText value={total} /> : 'vượt giới hạn'}
        </Typography.Text>
        <Button type="primary" loading={save.isPending} disabled={!pending && !canSubmit} onClick={submit}>
          {save.isError && pending ? 'Gửi lại phiếu' : 'Lưu phiếu nhập'}
        </Button>
      </Space>
    </Space>
  );
}
