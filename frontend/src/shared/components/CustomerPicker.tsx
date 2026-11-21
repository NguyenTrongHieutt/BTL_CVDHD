import { useDeferredValue, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Input, Modal, Select, Space } from 'antd';
import {
  createCustomer, customersQueryKey, existingCustomerIdOf, fetchCustomer, fetchCustomers,
} from '../api/customers';
import type { CustomerSummary } from '../types/catalog';
import type { Uuid } from '../types/common';
import { ErrorAlert } from './ErrorAlert';

export interface CustomerPickerProps {
  value?: CustomerSummary | null;
  /** `null` khi bỏ chọn (khách lẻ). */
  onChange: (customer: CustomerSummary | null) => void;
  allowQuickCreate?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export function CustomerPicker({
  value, onChange, allowQuickCreate, disabled, placeholder = 'Khách lẻ (gõ tên hoặc số điện thoại)',
}: CustomerPickerProps) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState(false);
  const [phone, setPhone] = useState<string | null>(null);
  const q = useDeferredValue(search.trim());
  const customers = useQuery({
    queryKey: [...customersQueryKey, 'pos-search', q],
    queryFn: ({ signal }) => fetchCustomers({ page: 0, size: 20 }, q, signal),
    enabled: open && !disabled,
  });

  function select(customer: CustomerSummary | null) {
    onChange(customer);
    setSearch('');
    setOpen(false);
  }

  const quickCreate = useMutation({
    mutationFn: async (input: string) => {
      try {
        return await createCustomer({ phone: input });
      } catch (error) {
        const existingId = existingCustomerIdOf(error);
        if (!existingId) throw error;
        return fetchCustomer(existingId);
      }
    },
    onSuccess: (customer) => {
      select(customer);
      setPhone(null);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: customersQueryKey });
    },
  });

  const items: CustomerSummary[] = customers.data?.items ?? [];
  const choices = value && !items.some(({ customerId }) => customerId === value.customerId)
    ? [value, ...items]
    : items;

  function submitPhone() {
    const digits = phone?.trim();
    if (digits && !disabled && !quickCreate.isPending) quickCreate.mutate(digits);
  }

  return (
    <>
      <Space.Compact style={{ width: '100%' }}>
        <Select<Uuid>
          aria-label="Khách hàng"
          style={{ width: '100%' }}
          disabled={disabled}
          placeholder={placeholder}
          value={value?.customerId}
          open={open && !disabled}
          onOpenChange={setOpen}
          allowClear
          showSearch={{ searchValue: search, onSearch: setSearch, filterOption: false }}
          loading={customers.isFetching}
          notFoundContent={customers.isFetching ? 'Đang tìm khách…' : 'Không tìm thấy khách hàng.'}
          options={choices.map((customer) => ({
            value: customer.customerId,
            label: customer.name ? `${customer.name} · ${customer.phone}` : customer.phone,
          }))}
          onChange={(id) => select(choices.find((customer) => customer.customerId === id) ?? null)}
        />
        {allowQuickCreate && (
          <Button disabled={disabled} onClick={() => {
            quickCreate.reset();
            setOpen(false);
            setPhone(/^[+\d.\s-]+$/u.test(search) ? search.trim() : '');
          }}>Tạo khách nhanh</Button>
        )}
      </Space.Compact>
      {open && customers.isError && <ErrorAlert error={customers.error} />}
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
