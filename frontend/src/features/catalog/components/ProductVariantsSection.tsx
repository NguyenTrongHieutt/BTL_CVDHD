/**
 * Phần "Biến thể" của form sửa Sản phẩm (FR-7, SP-07). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng.
 *
 * Sản phẩm nhiều Biến thể: bảng Biến thể (nhãn, SKU, mã vạch, giá bán, trạng thái; Biến thể mặc định đứng đầu, có thẻ
 * "Mặc định"). Mỗi Biến thể có "Lịch sử kho" (màn KHO-07 lọc sẵn theo Biến thể đó) và "Sửa", kể cả Biến thể đã ngừng
 * kinh doanh (đổi nhãn, SKU, mã vạch để Biến thể mới dùng lại; không mở bán lại); Biến thể đang bán có thêm "Ngừng kinh
 * doanh" (có hộp xác nhận; một chiều). Sản phẩm một Biến thể: chỉ có câu hướng dẫn. Cả hai có nút "Thêm Biến thể"
 * (`VariantFormModal`); `addBlockedReason` khóa nút này và hiện lý do (form Sản phẩm còn thay đổi giá, mã chưa lưu, sẽ
 * mất khi Sản phẩm thành nhiều Biến thể). Mỗi thao tác lưu ngay, không chờ "Lưu" của form Sản phẩm; xong thì báo lên
 * `onChange` chi tiết Sản phẩm mới để form hiện đúng (ví dụ ẩn ô giá khi Sản phẩm thành nhiều Biến thể). Ngừng Biến thể
 * lỗi (ví dụ Biến thể đang bán cuối cùng) hiện ở khung báo lỗi của phần này.
 */
import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Flex, Table, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { Link } from 'react-router';
import { ErrorAlert, MoneyText, useConfirm } from '../../../shared/components';
import type { Product, ProductVariant } from '../../../shared/types/catalog';
import { useDeactivateVariant } from '../api/products';
import { VariantFormModal } from './VariantFormModal';
import type { VariantEditing } from './VariantFormModal';
import { stockLedgerLink } from './stockLedgerLink';
import { variantLabel } from './variantRows';

const EMPTY = '—';

export interface ProductVariantsSectionProps {
  product: Product;
  /** Form Sản phẩm đang gửi hay chỉ còn đóng được: khóa mọi nút. */
  disabled?: boolean;
  /** Có thì khóa nút "Thêm Biến thể" và hiện câu này (ví dụ form Sản phẩm còn thay đổi chưa lưu). */
  addBlockedReason?: string;
  onChange: (product: Product) => void;
}

export function ProductVariantsSection({
  product,
  disabled = false,
  addBlockedReason,
  onChange,
}: ProductVariantsSectionProps) {
  const [editing, setEditing] = useState<VariantEditing | null>(null);
  const deactivate = useDeactivateVariant();
  const confirm = useConfirm();
  const { message } = App.useApp();
  const busy = disabled || deactivate.isPending;

  const onDeactivate = async (variant: ProductVariant) => {
    deactivate.reset();
    const label = variantLabel(variant);
    const ok = await confirm({
      title: `Ngừng kinh doanh Biến thể "${label}"?`,
      content:
        'Biến thể sẽ ẩn khỏi màn bán hàng, không bán được nữa; các Biến thể khác vẫn bán bình thường. Tồn kho và ' +
        'lịch sử vẫn được giữ. Thao tác này không hoàn tác được.',
      okText: 'Ngừng kinh doanh',
      danger: true,
    });
    if (!ok) return;
    deactivate.mutate(
      { productId: product.id, variantId: variant.id },
      {
        onSuccess: (updated) => {
          void message.success(`Đã ngừng kinh doanh Biến thể "${label}"`);
          onChange(updated);
        },
      },
    );
  };

  const columns: TableColumnsType<ProductVariant> = [
    {
      title: 'Nhãn',
      key: 'name',
      render: (_, variant) => (
        <Flex gap={4} align="center" wrap>
          <span>{variantLabel(variant)}</span>
          {variant.isDefault ? <Tag>Mặc định</Tag> : null}
        </Flex>
      ),
    },
    { title: 'SKU', dataIndex: 'sku', key: 'sku' },
    { title: 'Mã vạch', dataIndex: 'barcode', key: 'barcode', render: (barcode: string | null) => barcode ?? EMPTY },
    {
      title: 'Giá bán',
      dataIndex: 'salePrice',
      key: 'salePrice',
      align: 'right',
      render: (price: number) => <MoneyText value={price} />,
    },
    {
      title: 'Trạng thái',
      dataIndex: 'status',
      key: 'status',
      render: (status: ProductVariant['status']) =>
        status === 'ACTIVE' ? <Tag color="green">Đang bán</Tag> : <Tag>Ngừng kinh doanh</Tag>,
    },
    {
      title: 'Thao tác',
      key: 'actions',
      // Biến thể đã ngừng kinh doanh vẫn sửa được (nhường nhãn, mã) nhưng không mở bán lại (một chiều, AD-14)
      render: (_, variant) => (
        <Flex wrap align="center">
          <Link
            to={stockLedgerLink(product.id, variant.id)}
            aria-label={`Lịch sử kho Biến thể ${variantLabel(variant)}`}
            style={{ padding: '0 7px', whiteSpace: 'nowrap' }}
          >
            Lịch sử kho
          </Link>
          <Button
            type="link"
            size="small"
            aria-label={`Sửa Biến thể ${variantLabel(variant)}`}
            disabled={busy}
            onClick={() => setEditing({ mode: 'edit', product, variant })}
          >
            Sửa
          </Button>
          {variant.status === 'ACTIVE' ? (
            <Button
              type="link"
              size="small"
              danger
              aria-label={`Ngừng kinh doanh Biến thể ${variantLabel(variant)}`}
              disabled={busy}
              loading={deactivate.isPending && deactivate.variables?.variantId === variant.id}
              onClick={() => void onDeactivate(variant)}
            >
              Ngừng kinh doanh
            </Button>
          ) : null}
        </Flex>
      ),
    },
  ];

  return (
    <section aria-label="Biến thể" style={{ marginBottom: 24 }}>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 8 }}>
        <Typography.Text strong>Biến thể</Typography.Text>
        <Button
          icon={<PlusOutlined />}
          disabled={busy || addBlockedReason !== undefined}
          onClick={() => setEditing({ mode: 'add', product })}
        >
          Thêm Biến thể
        </Button>
      </Flex>
      {addBlockedReason ? (
        <Typography.Paragraph type="warning" style={{ marginBottom: 8 }}>
          {addBlockedReason}
        </Typography.Paragraph>
      ) : null}
      {deactivate.isError ? <ErrorAlert error={deactivate.error} onClose={() => deactivate.reset()} /> : null}
      {product.hasVariants ? (
        <>
          <Typography.Paragraph type="secondary">
            Thêm, sửa, ngừng kinh doanh Biến thể được lưu ngay, không cần bấm &quot;Lưu&quot;.
          </Typography.Paragraph>
          <Table<ProductVariant>
            rowKey="id"
            size="small"
            columns={columns}
            dataSource={product.variants}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        </>
      ) : (
        <Typography.Paragraph type="secondary">
          Sản phẩm này có một Biến thể. Bấm &quot;Thêm Biến thể&quot; khi cần bán theo màu, size…; mỗi Biến thể có mã, giá
          và Tồn kho riêng.
        </Typography.Paragraph>
      )}
      {editing ? (
        <VariantFormModal
          key={editing.mode === 'edit' ? editing.variant.id : 'add'}
          editing={editing}
          onClose={() => setEditing(null)}
          onSaved={onChange}
        />
      ) : null}
    </section>
  );
}
