/**
 * Sản phẩm (FR-1, SP-01). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò (route `ALL_ROLES`).
 *
 * Bảng Sản phẩm đơn giản, mới nhất trước, phân trang phía server (`DataTable`, khóa `['catalog', 'products', …]`).
 * Chỉ Chủ cửa hàng thấy nút "Thêm Sản phẩm" (backend vẫn kiểm quyền, AD-16). Thêm xong bảng về trang đầu và tải lại
 * ngay, nên Sản phẩm mới nằm đầu bảng. Bảng không có Giá vốn (Nhân viên cũng xem). Tìm kiếm, lọc là SP-05; sửa là
 * SP-02; ngừng kinh doanh là SP-03.
 */
import { PlusOutlined } from '@ant-design/icons';
import { Button, Flex, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { useCurrentUser } from '../../../shared/api/session';
import { DataTable, MoneyText, useDocumentTitle } from '../../../shared/components';
import type { Product, ProductVariant } from '../../../shared/types/catalog';
import { fetchProducts, productsQueryKey } from '../api/products';
import { CreateProductModal } from '../components/CreateProductModal';

const EMPTY = '—';

function defaultVariant(product: Product): ProductVariant | undefined {
  return product.variants.find((v) => v.isDefault) ?? product.variants[0];
}

const columns: TableColumnsType<Product> = [
  { title: 'Tên Sản phẩm', dataIndex: 'name', key: 'name' },
  {
    title: 'Danh mục',
    dataIndex: 'categoryName',
    key: 'categoryName',
    render: (name: string | null) => name ?? EMPTY,
  },
  { title: 'SKU', key: 'sku', render: (_, product) => defaultVariant(product)?.sku ?? EMPTY },
  { title: 'Mã vạch', key: 'barcode', render: (_, product) => defaultVariant(product)?.barcode ?? EMPTY },
  { title: 'Đơn vị tính', dataIndex: 'unit', key: 'unit' },
  {
    title: 'Giá bán',
    key: 'salePrice',
    align: 'right',
    render: (_, product) => <MoneyText value={defaultVariant(product)?.salePrice} />,
  },
  {
    title: 'Trạng thái',
    dataIndex: 'status',
    key: 'status',
    render: (status: Product['status']) =>
      status === 'ACTIVE' ? <Tag color="green">Đang kinh doanh</Tag> : <Tag>Ngừng kinh doanh</Tag>,
  },
];

export function ProductsPage() {
  useDocumentTitle('Sản phẩm');
  const { role } = useCurrentUser();
  const isOwner = role === 'OWNER';
  const [adding, setAdding] = useState(false);
  // Đổi key thì DataTable dựng lại từ trang đầu: Sản phẩm vừa thêm (mới nhất) nằm đầu bảng.
  const [tableKey, setTableKey] = useState(0);

  return (
    <div>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          Sản phẩm
        </Typography.Title>
        {isOwner ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setAdding(true)}>
            Thêm Sản phẩm
          </Button>
        ) : null}
      </Flex>
      <DataTable<Product>
        key={tableKey}
        rowKey="id"
        queryKey={productsQueryKey}
        fetchPage={fetchProducts}
        columns={columns}
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: isOwner ? 'Chưa có Sản phẩm nào. Bấm "Thêm Sản phẩm" để tạo.' : 'Chưa có Sản phẩm nào.',
        }}
      />
      {adding ? (
        <CreateProductModal onClose={() => setAdding(false)} onCreated={() => setTableKey((key) => key + 1)} />
      ) : null}
    </div>
  );
}
