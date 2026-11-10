/**
 * Sản phẩm (FR-1, SP-01; FR-3, SP-03; FR-5, SP-05). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò
 * (route `ALL_ROLES`).
 *
 * Bảng Sản phẩm phân trang phía server (`DataTable`, khóa `['catalog', 'products', { ...bộ lọc, page, size, sort }]`),
 * mặc định theo tên không dấu (của backend); bấm tiêu đề cột Tên, Giá bán, Ngày tạo để sắp. Thanh bộ lọc phía trên:
 * Tìm kiếm thường theo tên, SKU, mã vạch; Danh mục; khoảng giá bán; trạng thái; "Xóa bộ lọc". Bộ lọc nằm trên URL
 * query (`?q=&categoryId=&status=&minPrice=&maxPrice=`), nên tải lại trang hay gửi link vẫn giữ; đổi bộ lọc thì bảng về
 * trang đầu. Lọc theo Tồn kho ở màn Tồn kho, không ở đây.
 *
 * Chỉ Chủ cửa hàng thấy nút "Thêm Sản phẩm" và cột thao tác "Ngừng kinh doanh" (backend vẫn kiểm quyền, AD-16).
 * Thêm xong thì bỏ bộ lọc, sắp mới nhất trước và về trang đầu, nên Sản phẩm mới nằm đầu bảng (SP-01). Ngừng kinh
 * doanh có hộp xác nhận; xong thì bảng tải lại
 * tại chỗ, dòng đổi sang "Ngừng kinh doanh" và nút biến mất (thao tác một chiều, AD-14). Bảng không có Giá vốn
 * (Nhân viên cũng xem). Sửa là SP-02.
 */
import { PlusOutlined } from '@ant-design/icons';
import { App, Button, Flex, Tag, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { useCurrentUser } from '../../../shared/api/session';
import { DataTable, ErrorAlert, MoneyText, useConfirm, useDocumentTitle } from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { Product, ProductSearchParams, ProductVariant } from '../../../shared/types/catalog';
import { fetchProducts, productsQueryKey, useDeactivateProduct } from '../api/products';
import { CreateProductModal } from '../components/CreateProductModal';
import { ProductFilterBar } from '../components/ProductFilterBar';
import {
  hasProductFilters,
  productFiltersFromSearchParams,
  withProductFilters,
} from '../components/productFilterParams';

const EMPTY = '—';
/** Thứ tự của bảng ngay sau khi thêm Sản phẩm (SP-01: Sản phẩm mới nằm đầu bảng). */
const NEWEST_FIRST = 'createdAt,desc';

/** Ngày theo lịch Việt Nam, "dd/MM/yyyy", dù máy đặt múi giờ nào. */
const createdDateFormatter = new Intl.DateTimeFormat('vi-VN', {
  timeZone: VIETNAM_TIME_ZONE,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

function defaultVariant(product: Product): ProductVariant | undefined {
  return product.variants.find((v) => v.isDefault) ?? product.variants[0];
}

/** Chiều sắp ban đầu của cột `key` khi bảng mở với `sort` (`<field>,asc|desc`), để cột hiện đúng mũi tên. */
function sortOrderOf(sort: string | undefined, key: string): 'ascend' | 'descend' | null {
  if (sort === `${key},asc`) return 'ascend';
  if (sort === `${key},desc`) return 'descend';
  return null;
}

/**
 * Cột của bảng. Tên, giá bán, ngày tạo sắp được (phía server, `sort=name|salePrice|createdAt,asc|desc`); bỏ sắp thì
 * về mặc định của backend (theo tên không dấu).
 */
function buildColumns(initialSort: string | undefined): TableColumnsType<Product> {
  const sortable = (key: string) => ({ sorter: true, defaultSortOrder: sortOrderOf(initialSort, key) });
  return [
    { title: 'Tên Sản phẩm', dataIndex: 'name', key: 'name', ...sortable('name') },
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
      ...sortable('salePrice'),
      render: (_, product) => <MoneyText value={defaultVariant(product)?.salePrice} />,
    },
    {
      title: 'Trạng thái',
      dataIndex: 'status',
      key: 'status',
      render: (status: Product['status']) =>
        status === 'ACTIVE' ? <Tag color="green">Đang bán</Tag> : <Tag>Ngừng kinh doanh</Tag>,
    },
    {
      title: 'Ngày tạo',
      dataIndex: 'createdAt',
      key: 'createdAt',
      ...sortable('createdAt'),
      render: (createdAt: string) => createdDateFormatter.format(new Date(createdAt)),
    },
  ];
}

export function ProductsPage() {
  useDocumentTitle('Sản phẩm');
  const { role } = useCurrentUser();
  const isOwner = role === 'OWNER';
  const [adding, setAdding] = useState(false);
  // Đổi key thì DataTable dựng lại từ trang đầu với thứ tự `initialSort` (sau khi thêm Sản phẩm).
  const [tableKey, setTableKey] = useState(0);
  const [initialSort, setInitialSort] = useState<string | undefined>(undefined);
  const columns = useMemo(() => buildColumns(initialSort), [initialSort]);
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => productFiltersFromSearchParams(searchParams), [searchParams]);
  const filtered = hasProductFilters(filters);
  // Thay URL hiện tại (không thêm vào lịch sử) để mỗi lần gõ không thành một bước "Quay lại".
  const onFiltersChange = (next: ProductSearchParams) =>
    setSearchParams((current) => withProductFilters(current, next), { replace: true });
  const clearFilters = () => onFiltersChange({});
  // SP-01: Sản phẩm vừa thêm nằm đầu bảng: bỏ bộ lọc (có thể không khớp), sắp mới nhất trước, về trang đầu.
  const onCreated = () => {
    clearFilters();
    setInitialSort(NEWEST_FIRST);
    setTableKey((key) => key + 1);
  };
  const deactivate = useDeactivateProduct();
  const confirm = useConfirm();
  const { message } = App.useApp();

  const onDeactivate = async (product: Product) => {
    deactivate.reset();
    const ok = await confirm({
      title: `Ngừng kinh doanh "${product.name}"?`,
      content:
        'Sản phẩm sẽ ẩn khỏi màn bán hàng và kết quả tìm kiếm, không bán được nữa. Lịch sử Đơn hàng, Tồn kho và ' +
        'báo cáo vẫn được giữ. Thao tác này không hoàn tác được.',
      okText: 'Ngừng kinh doanh',
      danger: true,
    });
    if (!ok) return;
    // Bảng tải lại tại chỗ (không về trang đầu): dòng đổi trạng thái, nút biến mất.
    deactivate.mutate(product.id, {
      onSuccess: () => {
        void message.success(`Đã ngừng kinh doanh "${product.name}"`);
      },
    });
  };

  const actionsColumn: TableColumnsType<Product>[number] = {
    title: 'Thao tác',
    key: 'actions',
    render: (_, product) =>
      product.status === 'ACTIVE' ? (
        <Button
          type="link"
          size="small"
          danger
          aria-label={`Ngừng kinh doanh ${product.name}`}
          disabled={deactivate.isPending}
          loading={deactivate.isPending && deactivate.variables === product.id}
          onClick={() => void onDeactivate(product)}
        >
          Ngừng kinh doanh
        </Button>
      ) : null,
  };

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
      {deactivate.isError ? <ErrorAlert error={deactivate.error} onClose={() => deactivate.reset()} /> : null}
      <ProductFilterBar value={filters} onChange={onFiltersChange} />
      <DataTable<Product>
        key={tableKey}
        rowKey="id"
        queryKey={[...productsQueryKey, { ...filters }]}
        fetchPage={(request, signal) => fetchProducts(request, filters, signal)}
        defaultSort={initialSort}
        columns={isOwner ? [...columns, actionsColumn] : columns}
        scroll={{ x: 'max-content' }}
        locale={{
          emptyText: filtered ? (
            <Flex vertical align="center" gap={8}>
              <span>Không có Sản phẩm nào khớp bộ lọc.</span>
              <Button onClick={clearFilters}>Xóa bộ lọc</Button>
            </Flex>
          ) : isOwner ? (
            'Chưa có Sản phẩm nào. Bấm "Thêm Sản phẩm" để tạo.'
          ) : (
            'Chưa có Sản phẩm nào.'
          ),
        }}
      />
      {adding ? <CreateProductModal onClose={() => setAdding(false)} onCreated={onCreated} /> : null}
    </div>
  );
}
