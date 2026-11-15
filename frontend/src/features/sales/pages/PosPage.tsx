import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Descriptions, InputNumber, QRCode, Select, Space, Table, Typography } from 'antd';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { MoneyText } from '../../../shared/components/MoneyText';
import { DiscountInput } from './DiscountInput';
import { useCurrentUser } from '../../../shared/api/session';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import { ProductPicker } from '../../../shared/components/ProductPicker';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import type { CreateOrderRequest, PaymentMethod, QuoteLineRequest, QuoteRequest, QuoteResponse, SellableItem } from '../../../shared/types/sales';
import { emptyCart, loadCart, saveCart } from '../cart/cartStorage';

function CartQuantityInput({ quantity, available, disabled, onCommit }: {
  quantity: number;
  available: number;
  disabled: boolean;
  onCommit: (quantity: number) => void;
}) {
  const [draft, setDraft] = useState<number | null | undefined>(undefined);
  const [notice, setNotice] = useState('');
  const overStock = draft != null && draft > available;

  function commit() {
    if (draft === undefined) return;
    setDraft(undefined);
    if (disabled || draft === null || !Number.isInteger(draft) || draft < 1) return;
    if (draft > available) {
      setNotice(available > 0
        ? `Kho còn ${available} sản phẩm. Đã điều chỉnh số lượng về ${available}.`
        : 'Sản phẩm đã hết hàng.');
    }
    if (available > 0) onCommit(Math.min(draft, available));
  }

  return (
    <Space orientation="vertical" size={4}>
      <InputNumber
        aria-label="Số lượng trong giỏ"
        aria-invalid={overStock}
        disabled={disabled}
        min={1}
        precision={0}
        value={draft === undefined ? quantity : draft}
        status={overStock || notice ? 'warning' : undefined}
        onChange={(value) => {
          setNotice('');
          setDraft(value);
        }}
        onBlur={commit}
        onPressEnter={commit}
      />
      {(overStock || notice) && <Typography.Text type="warning" role="status">
        {notice || (available > 0 ? `Còn ${available} sản phẩm. Số lượng sẽ về ${available} khi rời ô hoặc nhấn Enter.`
          : 'Sản phẩm đã hết hàng.')}
      </Typography.Text>}
    </Space>
  );
}

function PosCart({ storeId, userId }: { storeId: string; userId: string }) {
  const [cart, setCart] = useState(() => loadCart(storeId, userId));
  const [products, setProducts] = useState<Map<string, SellableItem>>(() => new Map());
  const [warning, setWarning] = useState('');
  const queryClient = useQueryClient();
  const [method, setMethod] = useState<PaymentMethod>('CASH');
  const [bankAvailable, setBankAvailable] = useState(false);
  const [orderCode, setOrderCode] = useState('');
  const [pending, setPending] = useState<CreateOrderRequest | null>(null);
  const payment = useMutation({
    mutationFn: (order: CreateOrderRequest) => api.post<{ orderCode: string }>(
      '/api/v1/sales/orders', order, { idempotencyKey: cart.idempotencyKey },
    ),
    onSuccess: (order) => {
      setOrderCode(order.orderCode);
      setCart(emptyCart());
      setPending(null);
      setCashReceived(null);
      setMethod('CASH');
      setProducts(new Map());
      for (const key of [['sales', 'sellable'], ['inventory', 'stock'], ['inventory', 'low-stock']]) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
    },
    onError: (error) => {
      if (shouldReuseIdempotencyKey(error)) return;
      setPending(null);
      if (isApiError(error) && error.status === 409) {
        setCart((previous) => ({ ...previous, idempotencyKey: newIdempotencyKey() }));
      }
    },
  });

  const [cashReceived, setCashReceived] = useState<number | null>(null);
  const [debouncedQuote, setDebouncedQuote] = useState('');
  const request: QuoteRequest = {
    lines: cart.lines,
    orderDiscount: cart.orderDiscount,
    paymentMethod: method,
    cashReceived: method === 'CASH' ? cashReceived ?? undefined : undefined,
  };
  const quoteBody = JSON.stringify(request);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuote(quoteBody), 300);
    return () => clearTimeout(timer);
  }, [quoteBody]);

  const quote = useQuery({
    queryKey: ['sales', 'quote', storeId, userId, cart.idempotencyKey, debouncedQuote],
    enabled: cart.lines.length > 0 && debouncedQuote === quoteBody,
    queryFn: ({ signal }) => api.post<QuoteResponse>(
      '/api/v1/sales/orders/quote', JSON.parse(debouncedQuote),
      { signal, idempotencyKey: cart.idempotencyKey },
    ),
    retry: false,
  });
  const pricing = debouncedQuote === quoteBody ? quote.data : undefined;

  useEffect(() => saveCart(storeId, userId, cart), [storeId, userId, cart]);

  useEffect(() => {
    if (pricing) setBankAvailable(pricing.bankTransferAvailable);
  }, [pricing]);

  function confirmPayment() {
    if (pending) {
      payment.mutate(pending);
      return;
    }
    if (!pricing || pricing.warnings.length || quote.isFetching || quote.isError) return;
    if (method === 'CASH' && (cashReceived === null || cashReceived < pricing.total)) return;
    const order = { ...request, quotedTotal: pricing.total };
    setPending(order);
    payment.mutate(order);
  }

  function add(item: SellableItem) {
    const existing = cart.lines.find((line) => line.variantId === item.variantId);
    const quantity = (existing?.quantity ?? 0) + 1;
    setProducts((previous) => new Map(previous).set(item.variantId, item));
    if (quantity > item.stockQuantity) {
      setWarning('Số lượng trong giỏ đã đạt hoặc vượt tồn hiện tại.');
      return;
    }
    setWarning('');
    setCart({
      ...cart,
      lines: existing
        ? cart.lines.map((line) => line.variantId === item.variantId ? { ...line, quantity } : line)
        : [...cart.lines, { variantId: item.variantId, quantity }],
    });
  }

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Typography.Title level={2} style={{ margin: 0 }}>Bán hàng</Typography.Title>
      <Card title="Thêm hàng vào giỏ"><ProductPicker onPick={add} autoFocus disabled={pending !== null} /></Card>
      <Card title="Giỏ hàng">
        {orderCode && <Alert type="success" showIcon title={`Đã bán hàng · ${orderCode}`} />}
        {warning && <Alert type="warning" showIcon title={warning} />}
        <Table<QuoteLineRequest>
          rowKey="variantId"
          dataSource={cart.lines}
          pagination={false}
          scroll={{ x: true }}
          locale={{ emptyText: 'Giỏ hàng đang trống.' }}
          columns={[
            { title: 'Sản phẩm', render: (_, line) => {
              const item = products.get(line.variantId);
              const priced = pricing?.lines.find((entry) => entry.variantId === line.variantId);
              if (priced) return [priced.productName, priced.variantName].filter(Boolean).join(' · ');
              return item
                ? [item.name, item.variantName].filter(Boolean).join(' · ')
                : 'Tìm lại sản phẩm để xem tên và tồn hiện tại';
            } },
            { title: 'Số lượng', render: (_, line) => (
              <CartQuantityInput
                disabled={pending !== null}
                available={products.get(line.variantId)?.stockQuantity ?? line.quantity}
                quantity={line.quantity}
                onCommit={(quantity) => {
                  setWarning('');
                  setCart((previous) => ({
                    ...previous,
                    lines: previous.lines.map((current) =>
                      current.variantId === line.variantId ? { ...current, quantity } : current),
                  }));
                }}
              />
            ) },
            {
              title: 'Giảm giá',
              render: (_, line) => (
                <DiscountInput label="Giảm giá dòng" value={line.discount}
                  onChange={(discount) => pending === null && setCart((previous) => ({
                    ...previous,
                    lines: previous.lines.map((current) =>
                      current.variantId === line.variantId ? { ...current, discount } : current),
                  }))}
                />
              ),
            },
            {
              title: 'Thành tiền',
              render: (_, line, index) => (
                <MoneyText value={pricing?.lines[index]?.variantId === line.variantId
                  ? pricing?.lines[index]?.netLineAmount : undefined} />
              ),
            },
            { title: 'Thao tác', render: (_, line) => (
              <Button danger disabled={pending !== null} onClick={() => {
                setWarning('');
                setCart((previous) => {
                  const lines = previous.lines.filter((current) => current.variantId !== line.variantId);
                  return lines.length ? { ...previous, lines } : emptyCart();
                });
              }}>Xóa</Button>
            ) },
          ]}
        />
      </Card>
      {cart.lines.length > 0 && (
        <Card title="Tính tiền">
          <Space orientation="vertical" style={{ width: '100%' }}>
            <Space wrap>
              <Typography.Text>Giảm giá cả đơn</Typography.Text>
              <DiscountInput label="Giảm giá cả đơn" value={cart.orderDiscount}
                onChange={(orderDiscount) => pending === null && setCart((previous) => ({ ...previous, orderDiscount }))}
              />
              <Select<PaymentMethod> aria-label="Phương thức thanh toán" value={method}
                disabled={pending !== null}
                options={[
                  { value: 'CASH', label: 'Tiền mặt' },
                  ...(bankAvailable ? [{ value: 'BANK_TRANSFER' as const, label: 'Chuyển khoản' }] : []),
                ]}
                onChange={(value) => setMethod(value)}
              />
              {method === 'CASH' && <>
                <Typography.Text>Tiền khách đưa</Typography.Text>
                <InputNumber aria-label="Tiền khách đưa" min={0} max={Number.MAX_SAFE_INTEGER}
                  disabled={pending !== null}
                  precision={0} value={cashReceived} onChange={setCashReceived} />
              </>}
            </Space>
            {debouncedQuote === quoteBody && quote.isError && <ErrorAlert error={quote.error} />}
            {(debouncedQuote !== quoteBody || quote.isFetching) && <Typography.Text>Đang tính tiền…</Typography.Text>}
            {payment.isError && <ErrorAlert error={payment.error} />}
            {pricing?.vietqrPayload && (
              <Space orientation="vertical">
                <QRCode value={pricing.vietqrPayload} />
                <Typography.Text>{pricing.bankAccountName}</Typography.Text>
                <Typography.Text>{pricing.transferRef}</Typography.Text>
              </Space>
            )}
            {pricing && (
              <>
                <Descriptions column={2} items={[
                  { key: 'subtotal', label: 'Tổng tiền hàng', children: <MoneyText value={pricing.subtotal} /> },
                  { key: 'lineDiscount', label: 'Giảm theo dòng', children: <MoneyText value={pricing.lineDiscountTotal} /> },
                  { key: 'orderDiscount', label: 'Giảm cả đơn', children: <MoneyText value={pricing.orderDiscount} /> },
                  { key: 'total', label: 'Khách phải trả', children: <MoneyText value={pricing.total} strong /> },
                  { key: 'change', label: 'Tiền thừa', children: <MoneyText value={pricing.change} /> },
                ]} />
                {cashReceived !== null && cashReceived < pricing.total && (
                  <Alert type="warning" showIcon title="Tiền khách đưa chưa đủ." />
                )}
                {pricing.warnings.length > 0 && (
                  <Alert type="warning" showIcon title="Có sản phẩm vượt số lượng tồn. Kiểm tra lại giỏ hàng." />
                )}
              </>
            )}
            <Button type="primary" loading={payment.isPending} onClick={confirmPayment}
              disabled={payment.isPending || (!pending && (
                !pricing || quote.isFetching || quote.isError || pricing.warnings.length > 0 ||
                (method === 'CASH' && (cashReceived === null || cashReceived < pricing.total))
              ))}
            >
              {pending && payment.isError ? 'Thử lại xác nhận' : 'Xác nhận đã nhận tiền'}
            </Button>
          </Space>
        </Card>
      )}
      <LowStockPanel />
    </Space>
  );
}

export function PosPage() {
  useDocumentTitle('Bán hàng');
  const { storeId, userId } = useCurrentUser();
  return <PosCart key={storeId + ':' + userId} storeId={storeId} userId={userId} />;
}
