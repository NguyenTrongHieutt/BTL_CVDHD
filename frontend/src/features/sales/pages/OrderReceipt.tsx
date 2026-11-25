import { useQuery } from '@tanstack/react-query';
import { Button, Space, Spin } from 'antd';
import { api } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components/ErrorAlert';
import { formatMoney } from '../../../shared/components/money';
import type { IsoDateTime, Money, Quantity, Uuid } from '../../../shared/types/common';

interface Receipt {
  store: { name: string; address: string | null; phone: string | null; invoiceFooter: string | null };
  order: { orderCode: string; createdAt: IsoDateTime; subtotal: Money; discount: Money; total: Money };
  lines: {
    lineNo: number;
    productName: string;
    variantName: string | null;
    quantity: Quantity;
    unitPrice: Money;
    netLineAmount: Money;
  }[];
}

const receiptDate = new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Ho_Chi_Minh',
});

export function OrderReceipt({ orderId, onBack }: { orderId: Uuid; onBack: () => void }) {
  const receipt = useQuery({
    queryKey: ['sales', 'receipt', orderId],
    queryFn: ({ signal }) => api.get<Receipt>('/api/v1/sales/orders/' + orderId + '/receipt', { signal }),
    retry: false,
  });
  const data = receipt.data;

  return (
    <>
      <Space className="no-print" style={{ marginBottom: 16 }}>
        <Button onClick={onBack}>Quay lại bán hàng</Button>
        {data && <Button type="primary" onClick={() => window.print()}>In hóa đơn</Button>}
        {receipt.isError && <Button onClick={() => void receipt.refetch()} loading={receipt.isFetching}>Thử lại</Button>}
      </Space>
      {receipt.isPending && <div className="no-print"><Spin /></div>}
      {receipt.isError && <div className="no-print"><ErrorAlert error={receipt.error} /></div>}
      {data && (
        <section className="print-receipt" aria-label="Hóa đơn bán hàng">
          <header>
            <h1>{data.store.name}</h1>
            {data.store.address && <div>{data.store.address}</div>}
            {data.store.phone && <div>{data.store.phone}</div>}
            <h2>Hóa đơn bán hàng</h2>
            <div>{data.order.orderCode} · {receiptDate.format(new Date(data.order.createdAt))}</div>
          </header>
          <table>
            <thead>
              <tr><th scope="col">Hàng hóa</th><th scope="col">Thành tiền</th></tr>
            </thead>
            <tbody>
              {data.lines.map(line => (
                <tr key={line.lineNo}>
                  <td>
                    <div>{line.productName}{line.variantName && ' · ' + line.variantName}</div>
                    <div>{line.quantity} × {formatMoney(line.unitPrice)}</div>
                  </td>
                  <td>{formatMoney(line.netLineAmount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <dl>
            <div><dt>Tiền hàng</dt><dd>{formatMoney(data.order.subtotal)}</dd></div>
            {data.order.discount > 0 && (
              <div><dt>Giảm giá</dt><dd>{formatMoney(data.order.discount)}</dd></div>
            )}
            <div><dt><strong>Tổng tiền</strong></dt><dd><strong>{formatMoney(data.order.total)}</strong></dd></div>
          </dl>
          {data.store.invoiceFooter && <footer>{data.store.invoiceFooter}</footer>}
        </section>
      )}
    </>
  );
}
