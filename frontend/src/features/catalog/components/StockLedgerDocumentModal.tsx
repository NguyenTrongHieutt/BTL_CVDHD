import { skipToken, useQuery } from '@tanstack/react-query';
import { Button, Descriptions, Flex, Modal, Typography } from 'antd';
import type { DescriptionsProps, TableColumnsType } from 'antd';
import { Link } from 'react-router';
import { ErrorAlert, MoneyText, RecordTable } from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import { fetchLedgerDocument, ledgerDocumentTitles, withLedgerDocument } from '../api/stockLedgerDocuments';
import type { LedgerDocument, LedgerDocumentLine, LedgerDocumentReference } from '../api/stockLedgerDocuments';

const documentDate = new Intl.DateTimeFormat('vi-VN', {
  dateStyle: 'short', timeStyle: 'short', timeZone: VIETNAM_TIME_ZONE,
});
const numbers = new Intl.NumberFormat('vi-VN');
const signedNumbers = new Intl.NumberFormat('vi-VN', { signDisplay: 'exceptZero' });
const statusLabels: Readonly<Record<string, string>> = {
  COMPLETED: 'Đã hoàn thành', CANCELLED: 'Đã hủy', RETURNED: 'Đã trả hàng',
};
const paymentLabels: Readonly<Record<string, string>> = { CASH: 'Tiền mặt', BANK_TRANSFER: 'Chuyển khoản' };

function metadata(document: LedgerDocument, searchParams: URLSearchParams): NonNullable<DescriptionsProps['items']> {
  const type = document.documentType;
  const items: NonNullable<DescriptionsProps['items']> = [
    { key: 'date', label: 'Thời điểm', children: documentDate.format(new Date(document.recordedAt)) },
  ];
  if (document.actorName) items.push({ key: 'actor', label: 'Người thực hiện', children: document.actorName });
  if (document.supplierName) items.push({ key: 'supplier', label: 'Nhà cung cấp', children: document.supplierName });
  if (document.customerName) items.push({ key: 'customer', label: 'Khách hàng', children: document.customerName });
  if (document.status) items.push({ key: 'status', label: 'Trạng thái đơn', children: statusLabels[document.status] ?? document.status });
  if (document.paymentMethod) items.push({
    key: 'method', label: type === 'SALE_RETURN' ? 'Phương thức hoàn tiền' : 'Phương thức thanh toán',
    children: paymentLabels[document.paymentMethod] ?? document.paymentMethod,
  });
  if (document.orderId && type !== 'SALE') {
    const search = withLedgerDocument(searchParams, { type: 'SALE', id: document.orderId });
    items.push({
      key: 'order', label: 'Đơn bán gốc',
      children: <Link preventScrollReset to={{ search: search.toString() }}>
        {document.relatedOrderCode ?? document.code ?? 'Mở đơn bán'}
      </Link>,
    });
  }
  if (document.note) items.push({
    key: 'note', label: type === 'SALE_CANCEL' || type === 'SALE_RETURN' ? 'Lý do' : 'Ghi chú',
    span: 'filled', children: <span style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{document.note}</span>,
  });
  return items;
}

function lineColumns(document: LedgerDocument): TableColumnsType<LedgerDocumentLine> {
  const type = document.documentType;
  const columns: TableColumnsType<LedgerDocumentLine> = [{
    title: 'Sản phẩm / Biến thể', key: 'product',
    render: (_, line) => (
      <Flex vertical>
        <span>{[line.productName, line.variantName].filter(Boolean).join(' · ') || 'Sản phẩm không còn'}</span>
        {line.sku && <Typography.Text type="secondary">{line.sku}</Typography.Text>}
      </Flex>
    ),
  }];
  if (type === 'STOCKTAKE') columns.push(
    { title: 'Tồn hệ thống', dataIndex: 'systemQuantity', align: 'right',
      render: (quantity: number | null) => quantity == null ? '—' : numbers.format(quantity) },
    { title: 'Đếm thực tế', dataIndex: 'quantity', align: 'right', render: (quantity: number) => numbers.format(quantity) },
    { title: 'Chênh lệch', dataIndex: 'delta', align: 'right',
      render: (delta: number | null) => delta == null ? '—' : signedNumbers.format(delta) },
    { title: 'Lý do', dataIndex: 'reason', render: (reason: string | null) => reason || '—' },
  );
  else columns.push({
    title: type === 'SALE_RETURN' ? 'Số lượng trả' : type === 'SALE_CANCEL' ? 'Số lượng hoàn kho' : 'Số lượng',
    dataIndex: 'quantity', align: 'right', render: (quantity: number) => numbers.format(quantity),
  });
  if (document.lines.some(line => line.unitAmount != null)) columns.push({
    title: type === 'PURCHASE_RECEIPT' ? 'Giá nhập' : type === 'OPENING' ? 'Giá vốn đầu kỳ' : 'Đơn giá',
    dataIndex: 'unitAmount', align: 'right', render: (value: number | null) => <MoneyText value={value} />,
  });
  if (document.lines.some(line => line.amount != null)) columns.push({
    title: type === 'SALE_RETURN' ? 'Tiền hoàn' : 'Thành tiền',
    dataIndex: 'amount', align: 'right', render: (value: number | null) => <MoneyText value={value} />,
  });
  return columns;
}

function DocumentDetails({ document, searchParams }: { document: LedgerDocument; searchParams: URLSearchParams }) {
  const totalLabel = document.documentType === 'SALE_RETURN' ? 'Tổng tiền hoàn'
    : document.documentType === 'SALE_CANCEL' ? 'Tổng tiền đơn gốc' : 'Tổng tiền';
  return (
    <Flex vertical gap={16}>
      <Descriptions size="small" column={{ xs: 1, sm: 2 }} items={metadata(document, searchParams)} />
      <RecordTable<LedgerDocumentLine>
        compactColumns={{ title: 'product' }}
        rowKey="lineNo" size="small" dataSource={document.lines} columns={lineColumns(document)}
        pagination={false} scroll={{ x: 'max-content' }}
        locale={{ emptyText: 'Chứng từ chưa có dòng hàng.' }}
      />
      {document.totalAmount != null && (
        <Descriptions size="small" column={1} items={[
          ...(document.subtotal != null ? [{ key: 'subtotal', label: 'Tiền hàng',
            children: <MoneyText value={document.subtotal} /> }] : []),
          ...(document.discount != null && document.discount > 0 ? [{ key: 'discount', label: 'Giảm giá',
            children: <MoneyText value={document.discount} /> }] : []),
          { key: 'total', label: totalLabel, children: <MoneyText value={document.totalAmount} strong /> },
        ]} />
      )}
    </Flex>
  );
}

export function StockLedgerDocumentModal({ reference, searchParams, onClose }: {
  reference?: LedgerDocumentReference;
  searchParams: URLSearchParams;
  onClose: () => void;
}) {
  const query = useQuery({
    queryKey: ['inventory', 'ledger-document', reference?.type, reference?.id],
    queryFn: reference ? ({ signal }) => fetchLedgerDocument(reference, signal) : skipToken,
    staleTime: 0,
  });
  const title = reference ? ledgerDocumentTitles[reference.type] : 'Chứng từ kho';
  const code = query.data?.code;
  return (
    <Modal
      open={Boolean(reference)}
      title={code ? `${title} · ${code}` : title}
      onCancel={onClose}
      width={760}
      styles={{ body: { maxHeight: '68dvh', overflowY: 'auto', overflowWrap: 'anywhere' } }}
      footer={<Button onClick={onClose}>Đóng</Button>}
    >
      {reference && query.isPending && <Typography.Text role="status">Đang tải chứng từ…</Typography.Text>}
      {reference && query.isError && (
        <ErrorAlert error={query.error} retryLabel="Tải lại chứng từ"
          onRetry={() => void query.refetch()} retrying={query.isFetching} />
      )}
      {reference && query.data && <DocumentDetails document={query.data} searchParams={searchParams} />}
    </Modal>
  );
}
