/**
 * Khách hàng (FR-18 KH-01, FR-19 KH-02). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò (route
 * `ALL_ROLES`): Chủ cửa hàng và Nhân viên đều thêm, sửa được.
 *
 * Bảng Khách hàng phân trang phía server (`DataTable`, khóa `['catalog', 'customers', { q, page, size, sort }]`), backend
 * sắp theo tên không dấu (chưa có tên xếp sau), rồi số điện thoại. Ô tìm kiếm phía trên: tên không dấu hoặc số điện
 * thoại khớp một phần ("345 678", "+84 912"); đổi từ khóa thì bảng về trang đầu. Thêm, sửa trong hộp thoại
 * (`CustomerFormModal`); thêm chỉ cần số điện thoại. Thêm xong thì ô tìm kiếm điền số vừa thêm, nên Khách hàng mới hiện
 * ngay trong bảng dù danh sách dài. Tìm một số điện thoại đầy đủ mà không ra ai thì có nút "Thêm Khách hàng với số
 * này" (hộp thoại thêm điền sẵn số). Không có xóa Khách hàng. Tab Lịch sử mua (FR-20, KH-03) làm ở GĐ2, gọi
 * `GET /api/v1/sales/orders?customerId=` của Anh qua shared/api, không import features/sales.
 */
import { PlusOutlined } from '@ant-design/icons';
import { Button, Flex, Typography } from 'antd';
import type { TableColumnsType } from 'antd';
import { useState } from 'react';
import { DataTable, SearchInput, useDocumentTitle } from '../../../shared/components';
import { VIETNAM_TIME_ZONE } from '../../../shared/components/dateRange';
import type { Customer } from '../../../shared/types/catalog';
import { customerLabel, customersQueryKey, fetchCustomers, formatPhone, normalizePhone } from '../api/customers';
import { CustomerFormModal } from '../components/CustomerFormModal';
import type { CustomerEditing } from '../components/CustomerFormModal';

const EMPTY = '—';
/** Bảng rộng cố định để ghi chú dài bị cắt (ellipsis) thay vì kéo giãn bảng; màn hẹp thì cuộn ngang. */
const TABLE_SCROLL = { x: 720 };

/** Ngày theo lịch Việt Nam, "dd/MM/yyyy", dù máy đặt múi giờ nào. */
const createdDateFormatter = new Intl.DateTimeFormat('vi-VN', {
  timeZone: VIETNAM_TIME_ZONE,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
});

export function CustomersPage() {
  useDocumentTitle('Khách hàng');
  const [q, setQ] = useState('');
  // Đổi key thì ô tìm kiếm dựng lại với từ khóa mới (sau khi thêm Khách hàng).
  const [searchKey, setSearchKey] = useState(0);
  const [editing, setEditing] = useState<CustomerEditing | null>(null);
  // Tìm theo một số điện thoại đầy đủ mà không ra ai: mời thêm Khách hàng với số đó.
  const searchedPhone = normalizePhone(q);

  // Khách hàng vừa thêm hiện ngay: tìm theo đúng số của nó (bảng về trang đầu).
  const onCreated = (customer: Customer) => {
    setQ(customer.phone);
    setSearchKey((key) => key + 1);
  };

  const columns: TableColumnsType<Customer> = [
    {
      title: 'Tên Khách hàng',
      dataIndex: 'name',
      key: 'name',
      render: (name: string | null) => name ?? <Typography.Text type="secondary">Chưa có tên</Typography.Text>,
    },
    {
      title: 'Số điện thoại',
      dataIndex: 'phone',
      key: 'phone',
      render: (phone: string) => formatPhone(phone),
    },
    {
      title: 'Ghi chú',
      dataIndex: 'note',
      key: 'note',
      ellipsis: true,
      render: (note: string | null) => note ?? EMPTY,
    },
    {
      title: 'Ngày tạo',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 120,
      render: (createdAt: string) => createdDateFormatter.format(new Date(createdAt)),
    },
    {
      title: 'Thao tác',
      key: 'actions',
      width: 100,
      render: (_, customer) => (
        <Button
          type="link"
          size="small"
          aria-label={`Sửa ${customerLabel(customer)}`}
          onClick={() => setEditing({ mode: 'edit', customer })}
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
          Khách hàng
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing({ mode: 'create' })}>
          Thêm Khách hàng
        </Button>
      </Flex>
      <SearchInput
        key={searchKey}
        aria-label="Tìm Khách hàng"
        placeholder="Tìm theo tên hoặc số điện thoại"
        defaultValue={q}
        onSearch={setQ}
        style={{ width: 320, maxWidth: '100%', marginBottom: 16 }}
      />
      <DataTable<Customer>
        rowKey="customerId"
        queryKey={[...customersQueryKey, { q }]}
        fetchPage={(request, signal) => fetchCustomers(request, q, signal)}
        columns={columns}
        scroll={TABLE_SCROLL}
        locale={{
          emptyText: q ? (
            <Flex vertical align="center" gap={8}>
              <span>{`Không có Khách hàng nào khớp "${q}".`}</span>
              <Flex wrap justify="center" gap={8}>
                {searchedPhone ? (
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={() => setEditing({ mode: 'create', phone: formatPhone(searchedPhone) })}
                  >
                    Thêm Khách hàng với số này
                  </Button>
                ) : null}
                <Button
                  onClick={() => {
                    setQ('');
                    setSearchKey((key) => key + 1);
                  }}
                >
                  Xóa tìm kiếm
                </Button>
              </Flex>
            </Flex>
          ) : (
            'Chưa có Khách hàng nào. Bấm "Thêm Khách hàng" để tạo.'
          ),
        }}
      />
      {editing ? (
        <CustomerFormModal
          key={editing.mode === 'edit' ? editing.customer.customerId : 'create'}
          editing={editing}
          onClose={() => setEditing(null)}
          onCreated={onCreated}
          onOpenExisting={(customer) => setEditing({ mode: 'edit', customer })}
        />
      ) : null}
    </div>
  );
}
