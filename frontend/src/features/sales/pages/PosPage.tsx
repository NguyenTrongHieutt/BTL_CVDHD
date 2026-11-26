import { useEffect, useId, useRef, useState } from 'react';
import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShoppingCartOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Descriptions, InputNumber, QRCode, Select, Space, Typography } from 'antd';
import type { InputRef } from 'antd';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { customersQueryKey, fetchCustomer } from '../../../shared/api/customers';
import { customerLoyaltyKey, useCustomerLoyalty } from '../../../shared/api/loyalty';
import { CustomerPicker } from '../../../shared/components/CustomerPicker';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { MoneyText } from '../../../shared/components/MoneyText';
import { DiscountInput } from './DiscountInput';
import { LoyaltyRedemption } from './LoyaltyRedemption';
import { OrderReceipt } from './OrderReceipt';
import { useCurrentUser } from '../../../shared/api/session';
import { LowStockPanel } from '../../../shared/components/LowStockPanel';
import { ProductPicker } from '../../../shared/components/ProductPicker';
import { RecordTable } from '../../../shared/components/RecordTable';
import { useDocumentTitle } from '../../../shared/components/useDocumentTitle';
import type { CustomerSummary } from '../../../shared/types/catalog';
import type { CreateOrderRequest, PaymentMethod, QuoteLineRequest, QuoteRequest, QuoteResponse, SellableItem } from '../../../shared/types/sales';
import { emptyCart, loadCart, saveCart } from '../cart/cartStorage';

interface SaleResult {
  orderId: string;
  orderCode: string;
  pointsEarned?: number;
  pointsRedeemed?: number;
}

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
  const [completedOrder, setCompletedOrder] = useState<SaleResult | null>(null);
  const [showReceipt, setShowReceipt] = useState(false);
  const [pending, setPending] = useState<CreateOrderRequest | null>(null);
  const [cashMode, setCashMode] = useState(false);
  const [cashReceived, setCashReceived] = useState<number | null>(null);
  const hasLines = cart.lines.length > 0;
  const selectedCustomerId = cart.customerId;
  const itemCount = cart.lines.reduce((total, line) => total + line.quantity, 0);
  const selectedCustomer = useQuery({
    queryKey: [...customersQueryKey, 'pos-selected', cart.customerId],
    queryFn: selectedCustomerId ? ({ signal }) => fetchCustomer(selectedCustomerId, signal) : skipToken,
    retry: false,
  });
  const loyalty = useCustomerLoyalty(selectedCustomerId);
  const customerReady = !cart.customerId || Boolean(selectedCustomer.data);

  function changeCustomer(customer: CustomerSummary | null) {
    if (customer) {
      queryClient.setQueryData([...customersQueryKey, 'pos-selected', customer.customerId], customer);
    }
    setCart((current) => ({
      ...current,
      customerId: customer?.customerId,
      pointsRedeemed: current.customerId === customer?.customerId ? current.pointsRedeemed : undefined,
    }));
  }
  const payment = useMutation({
    mutationFn: (order: CreateOrderRequest) => api.post<SaleResult>(
      '/api/v1/sales/orders', order, { idempotencyKey: cart.idempotencyKey },
    ),
    onSuccess: (order) => {
      setCompletedOrder(order);
      setCart(emptyCart());
      setPending(null);
      setCashMode(false);
      setCashReceived(null);
      setMethod('CASH');
      setProducts(new Map());
      for (const key of [
        ['sales', 'sellable'], ['sales', 'orders'], ['sales', 'order-sellers'],
        ['inventory', 'stock'], ['inventory', 'low-stock'],
        customerLoyaltyKey,
      ]) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
    },
    onError: (error) => {
      if (shouldReuseIdempotencyKey(error)) return;
      setPending(null);
      if (isApiError(error) && error.status === 409) {
        setCart((previous) => ({ ...previous, idempotencyKey: newIdempotencyKey() }));
        void queryClient.invalidateQueries({ queryKey: customerLoyaltyKey });
      }
    },
  });

  const [debouncedQuote, setDebouncedQuote] = useState('');
  const request: QuoteRequest = {
    lines: cart.lines,
    customerId: cart.customerId,
    pointsRedeemed: cart.customerId ? cart.pointsRedeemed : undefined,
    orderDiscount: cart.orderDiscount,
    paymentMethod: method,
    cashReceived: method === 'CASH' && cashMode ? cashReceived ?? undefined : undefined,
  };
  const quoteBody = JSON.stringify(request);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedQuote(quoteBody), 300);
    return () => clearTimeout(timer);
  }, [quoteBody]);

  const quote = useQuery<QuoteResponse>({
    queryKey: ['sales', 'quote', storeId, userId, cart.idempotencyKey, debouncedQuote],
    enabled: hasLines && debouncedQuote === quoteBody,
    placeholderData: (previous, query) => query?.queryKey[4] === cart.idempotencyKey ? previous : undefined,
    queryFn: ({ signal }) => api.post<QuoteResponse>(
      '/api/v1/sales/orders/quote', JSON.parse(debouncedQuote),
      { signal, idempotencyKey: cart.idempotencyKey },
    ),
    retry: false,
  });
  const displayPricing = hasLines ? quote.data : undefined;
  const pricing = debouncedQuote === quoteBody && !quote.isPlaceholderData ? displayPricing : undefined;
  const account = pricing?.loyalty ?? loyalty.data;
  const cashTendered = pricing ? cashMode && pricing.total > 0 ? cashReceived : pricing.total : null;
  const bankAvailable = displayPricing?.bankTransferAvailable || method === 'BANK_TRANSFER';
  const calculating = hasLines && pending === null && (debouncedQuote !== quoteBody || quote.isFetching || quote.isPlaceholderData);
  const quoteFailed = pending === null && debouncedQuote === quoteBody && quote.isError;
  const quoteError = quoteFailed && isApiError(quote.error) ? quote.error : undefined;
  const pointError = cart.pointsRedeemed && quoteError
    ? quoteError.errors.find((error) => error.field === 'pointsRedeemed')?.message
      ?? (quoteError.type === '/errors/sales/loyalty-insufficient' ? quoteError.detail ?? 'Số dư điểm không đủ.' : undefined)
    : undefined;
  const discountError = (field: string) => quoteError?.errors.find((error) => error.field === field)?.message;
  const hasDiscountError = quoteError?.errors.some(({ field }) => field === 'orderDiscount' || /^lines\[\d+\]\.discount$/u.test(field ?? ''));
  const showCashOption = hasLines && method === 'CASH' && displayPricing?.total !== 0;
  const cashShort = method === 'CASH' && pricing && cashTendered !== null && cashTendered < pricing.total;
  const checkoutNotice = calculating ? 'Đang cập nhật tổng tiền…'
    : cashShort ? 'Tiền khách đưa chưa đủ.'
      : pricing?.warnings.length ? 'Có sản phẩm vượt số lượng tồn. Kiểm tra lại giỏ hàng.' : null;

  useEffect(() => saveCart(storeId, userId, cart), [storeId, userId, cart]);

  function confirmPayment() {
    if (payment.isPending) return;
    if (pending) {
      payment.mutate(pending);
      return;
    }
    if (!customerReady || !pricing || pricing.warnings.length || quote.isFetching || quote.isError) return;
    if (method === 'CASH' && (cashTendered === null || cashTendered < pricing.total)) return;
    const order = { ...request, cashReceived: method === 'CASH' ? cashTendered ?? undefined : undefined,
      quotedTotal: pricing.total };
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
    if (!hasLines) setCompletedOrder(null);
    setCart({
      ...cart,
      lines: existing
        ? cart.lines.map((line) => line.variantId === item.variantId ? { ...line, quantity } : line)
        : [...cart.lines, { variantId: item.variantId, quantity }],
    });
  }

  function lineName(line: QuoteLineRequest) {
    const item = products.get(line.variantId);
    const priced = displayPricing?.lines.find((entry) => entry.variantId === line.variantId);
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
    if (cart.lines.length === 1) {
      setCashReceived(null);
      setCashMode(false);
      setMethod('CASH');
    }
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
      <Card title="Thêm hàng vào giỏ"><ProductPicker searchRef={search} onPick={add} disabled={pending !== null} /></Card>
      <Card title="Giỏ hàng" extra={
        <span role="status" aria-atomic="true">
          {itemCount > 0 ? (
            <span className="pos-cart-count">
              {itemCount} sản phẩm<span className="visually-hidden"> trong giỏ hàng</span>
            </span>
          ) : <span className="visually-hidden">Giỏ hàng chưa có sản phẩm.</span>}
        </span>
      }>
        {completedOrder && (
          <Alert
            key={completedOrder.orderId}
            className="pos-sale-success"
            type="success"
            showIcon
            title={'Đã bán hàng · ' + completedOrder.orderCode}
            description={[
              completedOrder.pointsRedeemed ? `Đã dùng ${completedOrder.pointsRedeemed.toLocaleString('vi-VN')} điểm` : null,
              completedOrder.pointsEarned ? `Đã cộng ${completedOrder.pointsEarned.toLocaleString('vi-VN')} điểm` : null,
            ].filter(Boolean).join(' · ') || undefined}
            action={<Button onClick={() => setShowReceipt(true)}>Xem hóa đơn</Button>}
          />
        )}
        {warning && <Alert type="warning" showIcon title={warning} />}
        <Space orientation="vertical" className="pos-customer-section">
          <Typography.Text strong>Khách hàng</Typography.Text>
          <CustomerPicker
            value={selectedCustomer.data}
            onChange={changeCustomer}
            allowQuickCreate
            disabled={pending !== null}
            placeholder={cart.customerId && selectedCustomer.isPending ? 'Đang tải khách đã chọn…' : undefined}
          />
          <div className="pos-customer-status">{selectedCustomer.data && (
            account ? <Typography.Text type="secondary" role="status">
              Số dư điểm <strong>{account.balance.toLocaleString('vi-VN')} điểm</strong>
            </Typography.Text> : loyalty.isError ? (
              <Space size={4} wrap>
                <Typography.Text type="secondary">Chưa tải được số dư điểm.</Typography.Text>
                <Button type="link" onClick={() => void loyalty.refetch()} loading={loyalty.isFetching}>Tải lại số dư</Button>
              </Space>
            ) : <Typography.Text type="secondary" role="status">Đang tải số dư điểm…</Typography.Text>
          )}</div>
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
          scroll={{ x: 500 }}
          columns={[
            { title: 'Sản phẩm', key: 'productName', render: (_, line) => lineName(line) },
            { title: 'Số lượng', key: 'quantity', width: 104, render: (_, line, index) => (
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
              width: 145,
              render: (_, line, index) => (
                <DiscountInput label={`Giảm giá dòng ${index + 1}`} scope="line" value={line.discount}
                  error={discountError(`lines[${index}].discount`)} disabled={pending !== null}
                  onChange={(discount) => setLineDiscount(line, discount)}
                />
              ),
            },
            {
              title: 'Thành tiền',
              key: 'netLineAmount',
              width: 120,
              render: (_, line) => <MoneyText value={displayPricing?.lines.find(
                (entry) => entry.variantId === line.variantId,
              )?.netLineAmount} />,
            },
            { title: 'Thao tác', key: 'actions', width: 66, render: (_, line, index) => (
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
      {hasLines && (
        <Card title={<h2 className="pos-payment-heading" tabIndex={-1} ref={paymentHeading}>Thanh toán</h2>}>
          <Space orientation="vertical" className="pos-payment-stack">
            <div className="pos-total">
              <Typography.Text strong>Khách phải trả</Typography.Text>
              <MoneyText value={displayPricing?.total} strong />
            </div>
            <Descriptions className="pos-payment-summary" column={1} items={[
              { key: 'subtotal', label: 'Tổng tiền hàng', children: <MoneyText value={displayPricing?.subtotal} /> },
              { key: 'lineDiscount', label: 'Giảm theo dòng', children: <MoneyText value={displayPricing?.lineDiscountTotal} /> },
              { key: 'orderDiscount', label: 'Giảm cả đơn', children: <MoneyText value={displayPricing?.orderDiscount} /> },
              ...(selectedCustomerId ? [
                { key: 'pointsDiscount', label: 'Giảm do dùng điểm', children: <MoneyText value={displayPricing?.pointsDiscount} /> },
                { key: 'pointsEarned', label: 'Điểm dự kiến cộng',
                  children: displayPricing?.loyalty ? `${displayPricing.loyalty.pointsEarned.toLocaleString('vi-VN')} điểm` : '—' },
              ] : []),
            ]} />
            <div className="pos-payment-fields">
              <DiscountInput label="Giảm giá cả đơn" scope="order" value={cart.orderDiscount}
                error={discountError('orderDiscount')} disabled={pending !== null}
                onChange={(orderDiscount) => setCart((previous) => ({ ...previous, orderDiscount }))}
              />
              <div>
                <label htmlFor="pos-payment-method">Phương thức thanh toán</label>
                <Select<PaymentMethod> id="pos-payment-method" value={method}
                  disabled={pending !== null}
                  options={[
                    { value: 'CASH', label: 'Tiền mặt' },
                    ...(bankAvailable ? [{ value: 'BANK_TRANSFER' as const, label: 'Chuyển khoản' }] : []),
                  ]}
                  onChange={(value) => {
                    setMethod(value);
                    setCashMode(false);
                    setCashReceived(null);
                  }}
                />
              </div>
            </div>
            {selectedCustomerId && (
              <LoyaltyRedemption account={account} points={cart.pointsRedeemed}
                maxRedeemable={pricing?.loyalty?.maxRedeemablePoints} disabled={pending !== null}
                error={pointError}
                onChange={(pointsRedeemed) => setCart((previous) => ({ ...previous, pointsRedeemed }))} />
            )}
            {showCashOption && (
              <div className="pos-cash">
                <Button type="link" className="pos-cash-toggle" aria-expanded={cashMode} aria-controls="pos-cash-details"
                  disabled={pending !== null} onClick={() => {
                    setCashMode(!cashMode);
                    setCashReceived(null);
                  }}>
                  {cashMode ? 'Khách trả đúng số tiền' : 'Tính tiền thừa'}
                </Button>
                <div id="pos-cash-details" hidden={!cashMode}>
                  {cashMode && (
                    <div className="pos-cash-fields">
                      <label htmlFor="pos-cash-received">Tiền khách đưa</label>
                      <InputNumber id="pos-cash-received" min={0} max={Number.MAX_SAFE_INTEGER} inputMode="numeric"
                        disabled={pending !== null} precision={0} value={cashReceived} onChange={setCashReceived} />
                      <div className="pos-cash-change">Tiền thừa <MoneyText value={displayPricing?.change} strong /></div>
                    </div>
                  )}
                </div>
              </div>
            )}
            {quoteFailed && !pointError && !hasDiscountError && <ErrorAlert error={quote.error} retryLabel="Thử lại tính tiền"
              onRetry={() => void quote.refetch()} retrying={quote.isFetching} />}
            {payment.isError && <ErrorAlert error={payment.error} />}
            {pending && payment.isError && <Typography.Text>
              Thử lại cùng giao dịch sau khi kết nối ổn định để tránh ghi nhận đơn hai lần.
            </Typography.Text>}
            {method === 'BANK_TRANSFER' && displayPricing?.vietqrPayload && (
              <Space orientation="vertical" className="pos-transfer-details"
                style={{ visibility: pricing?.vietqrPayload && !calculating && !quoteFailed ? 'visible' : 'hidden' }}>
                <QRCode value={displayPricing.vietqrPayload} />
                {displayPricing.bankAccountName && <div>Tên tài khoản <Typography.Text strong>{displayPricing.bankAccountName}</Typography.Text></div>}
                {displayPricing.transferRef && <div>Nội dung chuyển khoản <Typography.Text strong>{displayPricing.transferRef}</Typography.Text></div>}
              </Space>
            )}
            <div className="pos-checkout-notice" role="status">
              {checkoutNotice && <Typography.Text type={calculating ? 'secondary' : 'warning'}>{checkoutNotice}</Typography.Text>}
            </div>
            <Button className="pos-submit" type="primary" loading={payment.isPending} onClick={confirmPayment}
              disabled={payment.isPending || (!pending && (
                !customerReady || !pricing || quote.isFetching || quote.isError || pricing.warnings.length > 0 ||
                (method === 'CASH' && (cashTendered === null || cashTendered < pricing.total))
              ))}
            >
              {pending && payment.isError ? 'Thử lại xác nhận'
                : displayPricing?.total === 0 ? 'Hoàn tất đơn' : 'Xác nhận đã nhận tiền'}
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
