import { useEffect, useState } from 'react';
import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Descriptions, InputNumber, QRCode, Select, Space, Table, Typography } from 'antd';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { customersQueryKey, fetchCustomer } from '../../../shared/api/customers';
import { CustomerPicker } from '../../../shared/components/CustomerPicker';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { MoneyText } from '../../../shared/components/MoneyText';
import { DiscountInput } from './DiscountInput';
import { OrderReceipt } from './OrderReceipt';
import { useCurrentUser } from '../../../shared/api/session';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import { ProductPicker } from '../../../shared/components/ProductPicker';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import type { CustomerSummary } from '../../../shared/types/catalog';
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
  const [completedOrder, setCompletedOrder] = useState<{ orderId: string; orderCode: string } | null>(null);
  const [showReceipt, setShowReceipt] = useState(false);
  const [pending, setPending] = useState<CreateOrderRequest | null>(null);
  const selectedCustomerId = cart.customerId;
  const selectedCustomer = useQuery({
    queryKey: [...customersQueryKey, 'pos-selected', cart.customerId],
    queryFn: selectedCustomerId ? ({ signal }) => fetchCustomer(selectedCustomerId, signal) : skipToken,
    retry: false,
  });
  const customerReady = !cart.customerId || Boolean(selectedCustomer.data);

  function changeCustomer(customer: CustomerSummary | null) {
    if (customer) {
      queryClient.setQueryData([...customersQueryKey, 'pos-selected', customer.customerId], customer);
    }
    setCart((current) => ({ ...current, customerId: customer?.customerId }));
  }
  const payment = useMutation({
    mutationFn: (order: CreateOrderRequest) => api.post<{ orderId: string; orderCode: string }>(
      '/api/v1/sales/orders', order, { idempotencyKey: cart.idempotencyKey },
    ),
    onSuccess: (order) => {
      setCompletedOrder(order);
      setCart(emptyCart());
      setPending(null);
      setCashReceived(null);
      setMethod('CASH');
      setProducts(new Map());
      for (const key of [
        ['sales', 'sellable'], ['sales', 'orders'], ['sales', 'order-sellers'],
        ['inventory', 'stock'], ['inventory', 'low-stock'],
      ]) {
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
    customerId: cart.customerId,
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
  const bankAvailable = pricing?.bankTransferAvailable ?? method === 'BANK_TRANSFER';

  useEffect(() => saveCart(storeId, userId, cart), [storeId, userId, cart]);

  function confirmPayment() {
    if (pending) {
      payment.mutate(pending);
      return;
    }
    if (!customerReady || !pricing || pricing.warnings.length || quote.isFetching || quote.isError) return;
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

  function lineName(line: QuoteLineRequest) {
    const item = products.get(line.variantId);
    const priced = pricing?.lines.find((entry) => entry.variantId === line.variantId);
    if (priced) return [priced.productName, priced.variantName].filter(Boolean).join(' · ');
    return item
      ? [item.name, item.variantName].filter(Boolean).join(' · ')
      : 'Tìm lại sản phẩm để xem tên và tồn hiện tại';
  }

  function setLineQuantity(line: QuoteLineRequest, quantity: number) {
    setWarning('');
    setCart((previous) => ({
      ...previous,
      lines: previous.lines.map((current) =>
        current.variantId === line.variantId ? { ...current, quantity } : current),
    }));
  }

  function setLineDiscount(line: QuoteLineRequest, discount: QuoteLineRequest['discount']) {
    if (pending !== null) return;
    setCart((previous) => ({
      ...previous,
      lines: previous.lines.map((current) =>
        current.variantId === line.variantId ? { ...current, discount } : current),
    }));
  }

  function removeLine(line: QuoteLineRequest) {
    setWarning('');
    setCart((previous) => {
      const lines = previous.lines.filter((current) => current.variantId !== line.variantId);
      return lines.length ? { ...previous, lines } : emptyCart();
    });
  }

  if (showReceipt && completedOrder) {
    return <OrderReceipt orderId={completedOrder.orderId} onBack={() => setShowReceipt(false)} />;
  }

  return (
    <div className="pos-page">
      <Typography.Title level={2} className="pos-heading" style={{ marginTop: 0 }}>Bán hàng</Typography.Title>
      <div className="pos-grid">
        <div className="pos-main">
      <Card title="Thêm hàng vào giỏ"><ProductPicker onPick={add} autoFocus disabled={pending !== null} /></Card>
      <Card title="Giỏ hàng">
        {completedOrder && (
          <Alert
            type="success"
            showIcon
            title={'Đã bán hàng · ' + completedOrder.orderCode}
            action={<Button onClick={() => setShowReceipt(true)}>Xem hóa đơn</Button>}
          />
        )}
        {warning && <Alert type="warning" showIcon title={warning} />}
        <Space orientation="vertical" style={{ width: '100%', marginBottom: 16 }}>
          <Typography.Text strong>Khách hàng</Typography.Text>
          <CustomerPicker
            value={selectedCustomer.data}
            onChange={changeCustomer}
            allowQuickCreate
            disabled={pending !== null}
            placeholder={cart.customerId && selectedCustomer.isPending ? 'Đang tải khách đã chọn…' : undefined}
          />
          {cart.customerId && selectedCustomer.isError && !selectedCustomer.data && (
            <>
              <ErrorAlert error={selectedCustomer.error} />
              <Button disabled={pending !== null} onClick={() => changeCustomer(null)}>Bỏ chọn khách</Button>
            </>
          )}
        </Space>
        <Table<QuoteLineRequest>
          className="pos-cart-table"
          rowKey="variantId"
          dataSource={cart.lines}
          pagination={false}
          scroll={{ x: true }}
          locale={{ emptyText: 'Giỏ hàng đang trống.' }}
          columns={[
            { title: 'Sản phẩm', render: (_, line) => lineName(line) },
            { title: 'Số lượng', render: (_, line) => (
              <CartQuantityInput
                disabled={pending !== null}
                available={products.get(line.variantId)?.stockQuantity ?? line.quantity}
                quantity={line.quantity}
                onCommit={(quantity) => setLineQuantity(line, quantity)}
              />
            ) },
            {
              title: 'Giảm giá',
              render: (_, line) => (
                <DiscountInput label="Giảm giá dòng" value={line.discount}
                  onChange={(discount) => setLineDiscount(line, discount)}
                />
              ),
            },
            {
              title: 'Thành tiền',
              render: (_, line) => <MoneyText value={pricing?.lines.find(
                (entry) => entry.variantId === line.variantId,
              )?.netLineAmount} />,
            },
            { title: 'Thao tác', render: (_, line) => (
              <Button danger disabled={pending !== null} onClick={() => removeLine(line)}>Xóa</Button>
            ) },
          ]}
        />
        <div className="pos-cart-mobile" role="list" aria-label="Sản phẩm trong giỏ">
          {cart.lines.length === 0 ? <p className="pos-cart-empty">Giỏ hàng đang trống.</p> : null}
          {cart.lines.map((line) => (
            <div className="pos-cart-item" role="listitem" key={line.variantId}>
              <div className="pos-cart-item-head">
                <strong>{lineName(line)}</strong>
                <MoneyText value={pricing?.lines.find(
                  (entry) => entry.variantId === line.variantId,
                )?.netLineAmount} strong />
              </div>
              <div className="pos-cart-item-fields">
                <div>
                  <span>Số lượng</span>
                  <CartQuantityInput
                    disabled={pending !== null}
                    available={products.get(line.variantId)?.stockQuantity ?? line.quantity}
                    quantity={line.quantity}
                    onCommit={(quantity) => setLineQuantity(line, quantity)}
                  />
                </div>
                <div>
                  <span>Giảm giá</span>
                  <DiscountInput label="Giảm giá dòng" value={line.discount}
                    onChange={(discount) => setLineDiscount(line, discount)} />
                </div>
              </div>
              <Button danger disabled={pending !== null} onClick={() => removeLine(line)}>Xóa khỏi giỏ</Button>
            </div>
          ))}
        </div>
      </Card>
        </div>
        <div className="pos-side">
      {cart.lines.length > 0 && (
        <Card title="Thanh toán">
          <Space orientation="vertical" style={{ width: '100%' }}>
            <div className="pos-payment-fields">
              <div>Giảm giá cả đơn
              <DiscountInput label="Giảm giá cả đơn" value={cart.orderDiscount}
                onChange={(orderDiscount) => pending === null && setCart((previous) => ({ ...previous, orderDiscount }))}
              />
              </div>
              <div>Phương thức thanh toán
              <Select<PaymentMethod> aria-label="Phương thức thanh toán" value={method}
                disabled={pending !== null}
                options={[
                  { value: 'CASH', label: 'Tiền mặt' },
                  ...(bankAvailable ? [{ value: 'BANK_TRANSFER' as const, label: 'Chuyển khoản' }] : []),
                ]}
                onChange={(value) => setMethod(value)}
              />
              </div>
              {method === 'CASH' && <>
                <div>Tiền khách đưa
                <InputNumber aria-label="Tiền khách đưa" min={0} max={Number.MAX_SAFE_INTEGER}
                  disabled={pending !== null}
                  precision={0} value={cashReceived} onChange={setCashReceived} />
                </div>
              </>}
            </div>
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
                <div className="pos-total">
                  <Typography.Text strong>Khách phải trả</Typography.Text>
                  <div><MoneyText value={pricing.total} strong /></div>
                </div>
                <Descriptions column={1} items={[
                  { key: 'subtotal', label: 'Tổng tiền hàng', children: <MoneyText value={pricing.subtotal} /> },
                  { key: 'lineDiscount', label: 'Giảm theo dòng', children: <MoneyText value={pricing.lineDiscountTotal} /> },
                  { key: 'orderDiscount', label: 'Giảm cả đơn', children: <MoneyText value={pricing.orderDiscount} /> },
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
            <Button className="pos-submit" type="primary" loading={payment.isPending} onClick={confirmPayment}
              disabled={payment.isPending || (!pending && (
                !customerReady || !pricing || quote.isFetching || quote.isError || pricing.warnings.length > 0 ||
                (method === 'CASH' && (cashReceived === null || cashReceived < pricing.total))
              ))}
            >
              {pending && payment.isError ? 'Thử lại xác nhận' : 'Xác nhận đã nhận tiền'}
            </Button>
          </Space>
        </Card>
      )}
      <LowStockPanel />
        </div>
      </div>
    </div>
  );
}

export function PosPage() {
  useDocumentTitle('Bán hàng');
  const { storeId, userId } = useCurrentUser();
  return <PosCart key={storeId + ':' + userId} storeId={storeId} userId={userId} />;
}
