/**
 * Hộp thoại thêm, sửa Khách hàng (FR-18, KH-01). Người giữ: Hiếu — features/catalog (AD-20). Cả hai vai trò.
 *
 * Chỉ số điện thoại là bắt buộc (tạo nhanh, FR-14); tên, ghi chú tùy chọn. Kiểm phía client giống backend (số
 * `0`/`+84` + 9 chữ số, được có dấu cách, dấu chấm, gạch ngang; chữ chuẩn hóa như `TextFields`, độ dài đếm theo ký
 * tự). Trùng số (`409 customer-phone-taken`) hiện ngay dưới ô số điện thoại kèm nút "Mở Khách hàng đã có": bấm thì
 * tải Khách hàng đó (`existingCustomerId`) và chuyển hộp thoại sang sửa nó. Lỗi `errors[]` của backend hiện dưới ô
 * tương ứng; lỗi khác hiện ở khung báo lỗi phía trên form. Đang gửi thì không đóng được hộp thoại.
 */
import { App, Button, Form, Input, Modal } from 'antd';
import { useState } from 'react';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { Customer, CustomerRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import {
  CUSTOMER_PHONE_TAKEN,
  PHONE_INVALID,
  customerBody,
  customerLabel,
  existingCustomerIdOf,
  fetchCustomer,
  formatPhone,
  normalizePhone,
  useCreateCustomer,
  useUpdateCustomer,
} from '../api/customers';
import { hasInvalidChars, normalizeText, textLength } from './textUtils';

const NAME_MAX = 100;
const NOTE_MAX = 500;

/** Thêm (có thể điền sẵn số điện thoại, ví dụ số vừa tìm không ra) hoặc sửa một Khách hàng. */
export type CustomerEditing = { mode: 'create'; phone?: string } | { mode: 'edit'; customer: Customer };

interface CustomerFormValues {
  phone: string;
  name?: string;
  note?: string;
}

type FormField = keyof CustomerFormValues;
const FORM_FIELDS: readonly string[] = ['phone', 'name', 'note'] satisfies FormField[];

/**
 * Lỗi của backend đặt được hết dưới các ô của form; `null` nếu có phần không thuộc ô nào (khi đó hiện khung báo lỗi
 * chung).
 */
function formErrorsOf(error: unknown): { name: FormField; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  if (error.type === CUSTOMER_PHONE_TAKEN) {
    // Gợi ý "Mở Khách hàng đã có…" chỉ khi có nút mở (backend trả `existingCustomerId`)
    const { message, hint } = PROBLEM_MESSAGES[CUSTOMER_PHONE_TAKEN];
    return [{ name: 'phone', errors: hint && existingCustomerIdOf(error) ? [message, hint] : [message] }];
  }
  if (error.errors.length === 0 || !error.errors.every((e) => FORM_FIELDS.includes(e.field))) return null;
  const byField = new Map<FormField, string[]>();
  for (const { field, message } of error.errors) {
    const name = field as FormField;
    byField.set(name, [...(byField.get(name) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

/** Độ dài tối đa và ký tự không hợp lệ, kiểm như backend. */
function textRules(label: string, max: number, multiline = false) {
  return [
    {
      validator: (_: unknown, value?: string) =>
        value === undefined || !hasInvalidChars(value, multiline)
          ? Promise.resolve()
          : Promise.reject(new Error(`${label} có ký tự không hợp lệ`)),
    },
    {
      validator: (_: unknown, value?: string) =>
        value === undefined || textLength(value, multiline) <= max
          ? Promise.resolve()
          : Promise.reject(new Error(`${label} tối đa ${max} ký tự`)),
    },
  ];
}

const phoneRule = {
  validator: (_: unknown, value?: string) => {
    if (normalizeText(value ?? '') === '') return Promise.reject(new Error('Nhập số điện thoại'));
    return normalizePhone(value) ? Promise.resolve() : Promise.reject(new Error(PHONE_INVALID));
  },
};

/** Hồ sơ sau khi chuẩn hóa không khác Khách hàng đang sửa: khỏi gửi. */
function isUnchanged(customer: Customer, request: CustomerRequest): boolean {
  const body = customerBody(request);
  return body.phone === customer.phone && (body.name ?? null) === customer.name && (body.note ?? null) === customer.note;
}

export interface CustomerFormModalProps {
  editing: CustomerEditing;
  onClose: () => void;
  /** Gọi sau khi thêm thành công, trước khi đóng hộp thoại. */
  onCreated: (customer: Customer) => void;
  /** Mở Khách hàng đã có (trùng số): hộp thoại chuyển sang sửa Khách hàng đó. */
  onOpenExisting: (customer: Customer) => void;
}

export function CustomerFormModal({ editing, onClose, onCreated, onOpenExisting }: CustomerFormModalProps) {
  const [form] = Form.useForm<CustomerFormValues>();
  const { message } = App.useApp();
  const create = useCreateCustomer();
  const update = useUpdateCustomer();
  const mutation = editing.mode === 'create' ? create : update;
  /** Id Khách hàng đang dùng số vừa gửi (409); xóa khi sửa ô số điện thoại. */
  const [takenBy, setTakenBy] = useState<Uuid | null>(null);
  const [opening, setOpening] = useState(false);
  const [openError, setOpenError] = useState<unknown>(null);
  // Đang gửi thì không cho đóng: đóng sẽ bỏ mất kết quả của yêu cầu đang chạy.
  const pending = mutation.isPending || opening;

  const showErrors = (error: unknown) => {
    const errors = formErrorsOf(error);
    if (errors) form.setFields(errors);
    setTakenBy(existingCustomerIdOf(error));
  };

  const onFinish = (values: CustomerFormValues) => {
    const request: CustomerRequest = { phone: values.phone, name: values.name, note: values.note };
    setOpenError(null);
    if (editing.mode === 'create') {
      create.mutate(request, {
        onSuccess: (customer) => {
          void message.success(`Đã thêm Khách hàng "${customerLabel(customer)}"`);
          onCreated(customer);
          onClose();
        },
        onError: showErrors,
      });
      return;
    }
    if (isUnchanged(editing.customer, request)) {
      onClose();
      return;
    }
    update.mutate(
      { id: editing.customer.customerId, request },
      {
        onSuccess: (customer) => {
          void message.success(`Đã lưu Khách hàng "${customerLabel(customer)}"`);
          onClose();
        },
        onError: showErrors,
      },
    );
  };

  const openExisting = async (id: Uuid) => {
    setOpening(true);
    setOpenError(null);
    try {
      onOpenExisting(await fetchCustomer(id));
    } catch (error) {
      setOpenError(error);
    } finally {
      setOpening(false);
    }
  };

  const initialValues: CustomerFormValues =
    editing.mode === 'edit'
      ? {
          phone: formatPhone(editing.customer.phone),
          name: editing.customer.name ?? '',
          note: editing.customer.note ?? '',
        }
      : { phone: editing.phone ?? '', name: '', note: '' };

  return (
    <Modal
      open
      title={editing.mode === 'create' ? 'Thêm Khách hàng' : 'Sửa Khách hàng'}
      okText={editing.mode === 'create' ? 'Thêm' : 'Lưu'}
      cancelText="Hủy"
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) onClose();
      }}
      confirmLoading={mutation.isPending}
      okButtonProps={{ disabled: opening }}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {mutation.isError && !formErrorsOf(mutation.error) ? <ErrorAlert error={mutation.error} /> : null}
      {openError ? <ErrorAlert error={openError} /> : null}
      <Form<CustomerFormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending}
        initialValues={initialValues}
        onValuesChange={(changed: Partial<CustomerFormValues>) => {
          if ('phone' in changed) setTakenBy(null);
        }}
      >
        <Form.Item
          name="phone"
          label="Số điện thoại"
          rules={[phoneRule]}
          extra={
            takenBy ? (
              <Button
                size="small"
                style={{ marginTop: 4 }}
                loading={opening}
                disabled={mutation.isPending}
                onClick={() => void openExisting(takenBy)}
              >
                Mở Khách hàng đã có
              </Button>
            ) : (
              'Ví dụ 0912 345 678 hoặc +84 912 345 678.'
            )
          }
        >
          <Input autoFocus inputMode="tel" autoComplete="off" />
        </Form.Item>
        <Form.Item name="name" label="Tên Khách hàng" rules={textRules('Tên Khách hàng', NAME_MAX)}>
          <Input placeholder="Không bắt buộc" autoComplete="off" />
        </Form.Item>
        <Form.Item name="note" label="Ghi chú" rules={textRules('Ghi chú', NOTE_MAX, true)}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
