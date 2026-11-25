import { useEffect, useId, useRef, useState } from 'react';
import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShoppingCartOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, InputNumber, QRCode, Select, Space, Typography } from 'antd';
import type { InputRef } from 'antd';
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
import { RecordTable } from '../../../shared/components/RecordTable';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import type { CustomerSummary } from '../../../shared/types/catalog';
import type { CreateOrderRequest, PaymentMethod, QuoteLineRequest, QuoteRequest, QuoteResponse, SellableItem } from '../../../shared/types/sales';
import { emptyCart, loadCart, saveCart } from '../cart/cartStorage';

function CartQuantityInput({ label, quantity, available, disabled, onCommit }: {
  label: string;
  quantity: number;
  available: number;
  disabled: boolean;
  onCommit: (quantity: number) => void;
}) {
  const [draft, setDraft] = useState<number | null | undefined>(undefined);
  const [notice, setNotice] = useState('');
  const noticeId = useId();
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
        aria-label={label}
        aria-describedby={overStock || notice ? noticeId : undefined}
        aria-invalid={overStock}
        inputMode="numeric"
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
      {(overStock || notice) && <Typography.Text id={noticeId} type="warning" role="status">
        {notice || (available > 0 ? `Còn ${available} sản phẩm. Số lượng sẽ về ${available} khi rời ô hoặc nhấn Enter.`
          : 'Sản phẩm đã hết hàng.')}
      </Typography.Text>}
    </Space>
  );
}

function PosCart({ storeId, userId }: { storeId: string; userId: string }) {
  const search = useRef<InputRef>(null);
  const paymentHeading = useRef<HTMLHeadingElement>(null);
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
  const calculating = pending === null && (debouncedQuote !== quoteBody || quote.isFetching);
  const quoteFailed = pending === null && debouncedQuote === quoteBody && quote.isError;

  useEffect(() => saveCart(storeId, userId, cart), [storeId, userId, cart]);

  function confirmPayment() {
    if (payment.isPending) return;
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
      <Typography.Title level={1} className="pos-heading" style={{ marginTop: 0 }}>Bán hàng</Typography.Title>
      <div className="pos-grid">
        <div className="pos-main">
      <Card title="Thêm hàng vào giỏ"><ProductPicker searchRef={search} onPick={add} autoFocus disabled={pending !== null} /></Card>
      <Card title="Giỏ hàng" extra={cart.lines.length > 0
        ? <Typography.Text type="secondary">{cart.lines.length} mặt hàng</Typography.Text> : undefined}>
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
              <ErrorAlert error={selectedCustomer.error} retryLabel="Tải lại khách đã chọn"
                onRetry={() => void selectedCustomer.refetch()} retrying={selectedCustomer.isFetching} />
              <Button disabled={pending !== null} onClick={() => changeCustomer(null)}>Bỏ chọn khách</Button>
            </>
          )}
        </Space>
        {cart.lines.length === 0 ? (
          <div className="pos-cart-empty">
            <ShoppingCartOutlined aria-hidden="true" />
            <Typography.Text strong>Giỏ hàng đang trống.</Typography.Text>
            <Button type="link" onClick={() => search.current?.focus()}>Tìm sản phẩm</Button>
          </div>
        ) : (
        <RecordTable<QuoteLineRequest>
          className="pos-cart-table"
          compactColumns={{ title: 'productName', actions: 'actions' }}
          rowKey="variantId"
          dataSource={cart.lines}
          pagination={false}
          scroll={{ x: true }}
          columns={[
            { title: 'Sản phẩm', key: 'productName', render: (_, line) => lineName(line) },
            { title: 'Số lượng', key: 'quantity', render: (_, line, index) => (
              <CartQuantityInput
                label={`Số lượng dòng ${index + 1}`}
                disabled={pending !== null}
                available={products.get(line.variantId)?.stockQuantity ?? line.quantity}
                quantity={line.quantity}
                onCommit={(quantity) => setLineQuantity(line, quantity)}
              />
            ) },
            {
              title: 'Giảm giá',
              key: 'discount',
              render: (_, line, index) => (
                <DiscountInput label={`Giảm giá dòng ${index + 1}`} value={line.discount} disabled={pending !== null}
                  onChange={(discount) => setLineDiscount(line, discount)}
                />
              ),
            },
            {
              title: 'Thành tiền',
              key: 'netLineAmount',
              render: (_, line) => <MoneyText value={pricing?.lines.find(
                (entry) => entry.variantId === line.variantId,
              )?.netLineAmount} />,
            },
            { title: 'Thao tác', key: 'actions', render: (_, line, index) => (
              <Button danger aria-label={`Xóa dòng ${index + 1} khỏi giỏ`} disabled={pending !== null} onClick={() => removeLine(line)}>Xóa</Button>
            ) },
          ]}
        />
        )}
        {cart.lines.length > 0 && (
          <Button className="pos-checkout-shortcut" type="primary" block
            onClick={() => paymentHeading.current?.focus()}>
            Đến thanh toán
          </Button>
        )}
      </Card>
        </div>
        <div className="pos-side">
      {cart.lines.length > 0 && (
        <Card title={<h2 className="pos-payment-heading" tabIndex={-1} ref={paymentHeading}>Thanh toán</h2>}>
          <Space orientation="vertical" style={{ width: '100%' }}>
            {pricing && (
              <div className="pos-total">
                <Typography.Text strong>Khách phải trả</Typography.Text>
                <MoneyText value={pricing.total} strong />
              </div>
            )}
            <div className="pos-payment-fields">
              <div>Giảm giá cả đơn
              <DiscountInput label="Giảm giá cả đơn" value={cart.orderDiscount} disabled={pending !== null}
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
                  inputMode="numeric"
                  disabled={pending !== null}
                  precision={0} value={cashReceived} onChange={setCashReceived} />
                </div>
              </>}
            </div>
            {quoteFailed && <ErrorAlert error={quote.error} retryLabel="Thử lại tính tiền"
              onRetry={() => void quote.refetch()} retrying={quote.isFetching} />}
            {calculating && <Typography.Text role="status">Đang tính tiền…</Typography.Text>}
            {payment.isError && <ErrorAlert error={payment.error} />}
            {pending && payment.isError && <Typography.Text>
              Thử lại cùng giao dịch sau khi kết nối ổn định để tránh ghi nhận đơn hai lần.
            </Typography.Text>}
            {pricing?.vietqrPayload && (
              <Space orientation="vertical">
                <QRCode value={pricing.vietqrPayload} />
                {pricing.bankAccountName && <div>Tên tài khoản <Typography.Text strong>{pricing.bankAccountName}</Typography.Text></div>}
                {pricing.transferRef && <div>Nội dung chuyển khoản <Typography.Text strong>{pricing.transferRef}</Typography.Text></div>}
              </Space>
            )}
            {pricing && (
              <>
                <Descriptions column={1} items={[
                  { key: 'subtotal', label: 'Tổng tiền hàng', children: <MoneyText value={pricing.subtotal} /> },
                  { key: 'lineDiscount', label: 'Giảm theo dòng', children: <MoneyText value={pricing.lineDiscountTotal} /> },
                  { key: 'orderDiscount', label: 'Giảm cả đơn', children: <MoneyText value={pricing.orderDiscount} /> },
                  ...(method === 'CASH' ? [{ key: 'change', label: 'Tiền thừa', children: <MoneyText value={pricing.change} /> }] : []),
                ]} />
                {method === 'CASH' && cashReceived !== null && cashReceived < pricing.total && (
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
