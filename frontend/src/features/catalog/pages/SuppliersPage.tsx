/**
 * Nhà cung cấp (FR-23, KHO-02). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng (route `OWNER_ONLY`,
 * giao diện của Nhân viên không có màn này; backend vẫn cho Nhân viên `GET` vì `SecurityConfig` mở
 * `GET /api/v1/catalog/**` cho cả hai vai trò, còn thêm, sửa là `403`).
 *
 * Bảng Nhà cung cấp phân trang phía server (`DataTable`, khóa `['catalog', 'suppliers', { q, newestFirst, page, size,
 * sort }]`), backend sắp theo tên không dấu, rồi mã. Ô tìm kiếm phía trên: tên không dấu, mã (bắt đầu bằng) hoặc số
 * điện thoại khớp một phần; đổi từ khóa thì bảng về trang đầu, thứ tự theo tên. Thêm, sửa trong hộp thoại
 * (`SupplierFormModal`). Thêm xong thì như màn Sản phẩm: bỏ tìm kiếm, sắp mới thêm trước (`sort=createdAt,desc`), về
 * trang đầu, nên Nhà cung cấp vừa thêm nằm đầu bảng dù mã ngắn hay tên trùng. Không có xóa Nhà cung cấp, công nợ, lịch
 * sử nhập theo Nhà cung cấp.
 */
import { PlusOutlined } from '@ant-design/icons';
import { Button, Flex, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { DataTable, SearchInput, useDocumentTitle } from '../../../shared/components';
import type { Supplier } from '../../../shared/types/catalog';
import { NEWEST_FIRST, fetchSuppliers, suppliersQueryKey } from '../api/suppliers';
import { SupplierFormModal } from '../components/SupplierFormModal';
import type { SupplierEditing } from '../components/SupplierFormModal';

const EMPTY = '—';
/** Bảng rộng cố định để địa chỉ, ghi chú dài bị cắt (ellipsis) thay vì kéo giãn bảng; màn hẹp thì cuộn ngang. */
const TABLE_SCROLL = { x: 860 };

const orEmpty = (text: string | null) => text ?? EMPTY;

export function SuppliersPage() {
  useDocumentTitle('Nhà cung cấp');
  const [q, setQ] = useState('');
  // Đổi key thì ô tìm kiếm dựng lại với từ khóa mới (xóa tìm kiếm, sau khi thêm Nhà cung cấp).
  const [searchKey, setSearchKey] = useState(0);
  // Đổi key thì bảng dựng lại từ trang đầu (sau khi thêm Nhà cung cấp).
  const [tableKey, setTableKey] = useState(0);
  // Sau khi thêm: mới thêm trước, tới khi tìm kiếm lại.
  const [newestFirst, setNewestFirst] = useState(false);
  const [editing, setEditing] = useState<SupplierEditing | null>(null);

  const onSearch = (value: string) => {
    setQ(value);
    setNewestFirst(false);
  };

  const clearSearch = () => {
    onSearch('');
    setSearchKey((key) => key + 1);
  };

  // Nhà cung cấp vừa thêm nằm đầu bảng: bỏ tìm kiếm (có thể không khớp), mới thêm trước, về trang đầu.
  const onCreated = () => {
    setQ('');
    setSearchKey((key) => key + 1);
    setNewestFirst(true);
    setTableKey((key) => key + 1);
  };

  const columns: TableColumnsType<Supplier> = [
    { title: 'Mã', dataIndex: 'code', key: 'code', width: 140, render: orEmpty },
    { title: 'Tên Nhà cung cấp', dataIndex: 'name', key: 'name' },
    { title: 'Số điện thoại', dataIndex: 'phone', key: 'phone', width: 160, render: orEmpty },
    { title: 'Địa chỉ', dataIndex: 'address', key: 'address', ellipsis: true, render: orEmpty },
    { title: 'Ghi chú', dataIndex: 'note', key: 'note', ellipsis: true, render: orEmpty },
    {
      title: 'Thao tác',
      key: 'actions',
      width: 100,
      render: (_, supplier) => (
        <Button
          type="link"
          size="small"
          aria-label={`Sửa ${supplier.name}`}
          onClick={() => setEditing({ mode: 'edit', supplier })}
        >
          Sửa
        </Button>
      ),
    },
  ];

  return (
    <div>
      <Flex justify="space-between" align="center" wrap gap={8} style={{ marginBottom: 16 }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          Nhà cung cấp
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ mode: 'create' })}>
          Thêm Nhà cung cấp
        </Button>
      </Flex>
      <SearchInput
        key={`search-${searchKey}`}
        aria-label="Tìm Nhà cung cấp"
        placeholder="Tìm theo tên, mã hoặc số điện thoại"
        defaultValue={q}
        onSearch={onSearch}
        style={{ width: 320, maxWidth: '100%', marginBottom: 16 }}
      />
      <DataTable<Supplier>
        key={`table-${tableKey}`}
        rowKey="supplierId"
        queryKey={[...suppliersQueryKey, { q, newestFirst }]}
        // Cột không sắp được, nên thứ tự chỉ do trang quyết: mặc định theo tên (backend), sau khi thêm thì mới trước.
        fetchPage={(request, signal) =>
          fetchSuppliers({ ...request, sort: newestFirst ? NEWEST_FIRST : undefined }, q, signal)
        }
        columns={columns}
        scroll={TABLE_SCROLL}
        locale={{
          emptyText: q ? (
            <Flex vertical align="center" gap={8}>
              <span>{`Không có Nhà cung cấp nào khớp "${q}".`}</span>
              <Button onClick={clearSearch}>Xóa tìm kiếm</Button>
            </Flex>
          ) : (
            'Chưa có Nhà cung cấp nào. Bấm "Thêm Nhà cung cấp" để tạo.'
          ),
        }}
      />
      {editing ? (
        <SupplierFormModal
          key={editing.mode === 'edit' ? editing.supplier.supplierId : 'create'}
          editing={editing}
          onClose={() => setEditing(null)}
          onCreated={onCreated}
        />
      ) : null}
    </div>
  );
}
