import { useDeferredValue, useId, useRef, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { SearchOutlined } from '@ant-design/icons';
import { Button, Input, Modal, Space, Typography } from 'antd';
import type { InputRef } from 'antd';
import {
  createCustomer, customersQueryKey, existingCustomerIdOf, fetchCustomer, fetchCustomers,
} from '../api/customers';
import { useStableIdempotencyKey } from '../api/useStableIdempotencyKey';
import type { CustomerSummary } from '../types/catalog';
import { ErrorAlert } from './ErrorAlert';

export interface CustomerPickerProps {
  value?: CustomerSummary | null;
  /** `null` khi bỏ chọn (khách lẻ). */
  onChange: (customer: CustomerSummary | null) => void;
  allowQuickCreate?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

const pageSize = 8;

function customerLabel(customer: CustomerSummary) {
  return customer.name ? `${customer.name} · ${customer.phone}` : customer.phone;
}

export function CustomerPicker({
  value, onChange, allowQuickCreate, disabled, placeholder = 'Tìm và chọn khách',
}: CustomerPickerProps) {
  const queryClient = useQueryClient();
  const createAttempt = useStableIdempotencyKey();
  const inputId = useId();
  const searchInput = useRef<InputRef>(null);
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const [phone, setPhone] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const q = useDeferredValue(search.trim());
  const customers = useQuery({
    queryKey: [...customersQueryKey, 'pos-search', q, page],
    queryFn: ({ signal }) => fetchCustomers({ page, size: pageSize }, q, signal),
    enabled: open && !disabled && q === search.trim(),
    placeholderData: keepPreviousData,
  });
  const loading = q !== search.trim() || customers.isPending || customers.isPlaceholderData;
  const items = loading || customers.isError ? [] : customers.data?.items ?? [];
  const pages = Math.max(1, Math.ceil((customers.data?.total ?? 0) / pageSize));

  function select(customer: CustomerSummary | null) {
    onChange(customer);
    setOpen(false);
  }

  const quickCreate = useMutation({
    mutationFn: async (input: string) => {
      try {
        return await createCustomer({ phone: input }, createAttempt.keyFor({ phone: input }));
      } catch (error) {
        const existingId = existingCustomerIdOf(error);
        if (!existingId) throw error;
        return fetchCustomer(existingId);
      }
    },
    onSuccess: (customer) => {
      createAttempt.succeeded();
      select(customer);
      setPhone(null);
    },
    onError: createAttempt.failed,
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: customersQueryKey });
    },
  });

  function openPicker() {
    setSearch('');
    setPage(0);
    setOpen(true);
  }

  function openQuickCreate(searchText: string) {
    quickCreate.reset();
    setOpen(false);
    setPhone(/^[+\d.\s-]+$/u.test(searchText) ? searchText.trim() : '');
  }

  function submitPhone() {
    const digits = phone?.trim();
    if (digits && !disabled && !quickCreate.isPending) quickCreate.mutate(digits);
  }

  return (
    <>
      <div className="customer-picker">
        <Button className="customer-picker-trigger" icon={<SearchOutlined aria-hidden="true" />} aria-haspopup="dialog"
          disabled={disabled} onClick={openPicker}>
          <span>{value ? customerLabel(value) : placeholder}</span>
        </Button>
        {value && <Button disabled={disabled} onClick={() => select(null)}>Bỏ chọn</Button>}
        {allowQuickCreate && (
          <Button disabled={disabled} onClick={() => openQuickCreate('')}>Tạo khách nhanh</Button>
        )}
      </div>
      <Modal open={open && !disabled} title="Chọn khách hàng" onCancel={() => setOpen(false)}
        afterOpenChange={(visible) => { if (visible) searchInput.current?.focus(); }} width={500}
        footer={(
          <div className="customer-picker-pages" aria-label="Phân trang khách hàng" role="group">
            <Button disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>Trang trước</Button>
            <Typography.Text role="status">Trang {page + 1}/{Math.max(pages, page + 1)}</Typography.Text>
            <Button disabled={loading || customers.isError || page + 1 >= pages}
              onClick={() => setPage(page + 1)}>Trang sau</Button>
          </div>
        )}>
        <Space orientation="vertical" className="customer-picker-modal">
          <label htmlFor={inputId}>Tên hoặc số điện thoại</label>
          <Input id={inputId} ref={searchInput} value={search} allowClear
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }} />
          <div className="customer-picker-results" aria-busy={loading}>
            {loading ? <Typography.Text role="status">Đang tìm khách hàng…</Typography.Text>
              : customers.isError ? <ErrorAlert error={customers.error} onRetry={() => void customers.refetch()}
                retrying={customers.isFetching} retryLabel="Thử tìm lại" />
                : items.length ? (
                  <ul>
                    {items.map((customer) => (
                      <li key={customer.customerId}>
                        <Button type="text" block onClick={() => select(customer)}>{customerLabel(customer)}</Button>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <Space orientation="vertical">
                    <Typography.Text>Không tìm thấy khách hàng.</Typography.Text>
                    {allowQuickCreate && (
                      <Button onClick={() => openQuickCreate(search)}>Tạo khách nhanh</Button>
                    )}
                  </Space>
                )}
          </div>
        </Space>
      </Modal>
      {allowQuickCreate && (
        <Modal
          open={phone !== null}
          title="Tạo khách nhanh"
          okText="Tạo và chọn khách"
          cancelText="Hủy"
          confirmLoading={quickCreate.isPending}
          okButtonProps={{ disabled: !phone?.trim() || disabled }}
          cancelButtonProps={{ disabled: quickCreate.isPending }}
          onOk={submitPhone}
          onCancel={() => { if (!quickCreate.isPending) setPhone(null); }}
        >
          {quickCreate.isError && <ErrorAlert error={quickCreate.error} />}
          <label>
            Số điện thoại
            <Input value={phone ?? ''} inputMode="tel" placeholder="Ví dụ 0912 345 678"
              disabled={quickCreate.isPending} onChange={(event) => setPhone(event.target.value)}
              onPressEnter={submitPhone} />
          </label>
        </Modal>
      )}
    </>
  );
}
