/**
 * Hộp thoại thêm, sửa Nhà cung cấp (FR-23, KHO-02). Người giữ: Hiếu — features/catalog (AD-20). Chỉ Chủ cửa hàng.
 *
 * Chỉ tên là bắt buộc; mã, số điện thoại, địa chỉ, ghi chú tùy chọn. Kiểm phía client giống backend (chữ chuẩn hóa như
 * `TextFields`, độ dài đếm theo ký tự; mã chỉ chữ cái không dấu, chữ số, `-`, `_`, gửi đi chữ HOA). Trùng mã
 * (`409 supplier-code-taken`) hiện ngay dưới ô mã. Lỗi `errors[]` của backend hiện dưới ô tương ứng; lỗi khác hiện ở
 * khung báo lỗi phía trên form. Đang gửi thì không đóng được hộp thoại.
 */
import { App, Form, Input, Modal } from 'antd';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { Supplier, SupplierRequest } from '../../../shared/types/catalog';
import {
  CODE_INVALID,
  SUPPLIER_CODE_TAKEN,
  isValidSupplierCode,
  supplierBody,
  supplierPhoneError,
  useCreateSupplier,
  useUpdateSupplier,
} from '../api/suppliers';
import { hasInvalidChars, normalizeText, textLength } from './textUtils';

const NAME_MAX = 200;
const CODE_MAX = 30;
const PHONE_MAX = 20;
const ADDRESS_MAX = 300;
const NOTE_MAX = 500;

/** Thêm hoặc sửa một Nhà cung cấp. */
export type SupplierEditing = { mode: 'create' } | { mode: 'edit'; supplier: Supplier };

interface SupplierFormValues {
  name: string;
  code?: string;
  phone?: string;
  address?: string;
  note?: string;
}

type FormField = keyof SupplierFormValues;
const FORM_FIELDS: readonly string[] = ['name', 'code', 'phone', 'address', 'note'] satisfies FormField[];

/**
 * Lỗi của backend đặt được hết dưới các ô của form; `null` nếu có phần không thuộc ô nào (khi đó hiện khung báo lỗi
 * chung).
 */
function formErrorsOf(error: unknown): { name: FormField; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  if (error.type === SUPPLIER_CODE_TAKEN) {
    const { message, hint } = PROBLEM_MESSAGES[SUPPLIER_CODE_TAKEN];
    return [{ name: 'code', errors: hint ? [message, hint] : [message] }];
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

const nameRequired = {
  validator: (_: unknown, value?: string) =>
    normalizeText(value ?? '') === '' ? Promise.reject(new Error('Nhập tên Nhà cung cấp')) : Promise.resolve(),
};

const codeChars = {
  validator: (_: unknown, value?: string) =>
    hasInvalidChars(value ?? '') || isValidSupplierCode(value)
      ? Promise.resolve()
      : Promise.reject(new Error(CODE_INVALID)),
};

const phoneChars = {
  validator: (_: unknown, value?: string) => {
    const error = hasInvalidChars(value ?? '') ? null : supplierPhoneError(value);
    return error ? Promise.reject(new Error(error)) : Promise.resolve();
  },
};

/** Dữ liệu sau khi chuẩn hóa không khác Nhà cung cấp đang sửa: khỏi gửi. */
function isUnchanged(supplier: Supplier, request: SupplierRequest): boolean {
  const body = supplierBody(request);
  return (
    body.name === supplier.name &&
    (body.code ?? null) === supplier.code &&
    (body.phone ?? null) === supplier.phone &&
    (body.address ?? null) === supplier.address &&
    (body.note ?? null) === supplier.note
  );
}

export interface SupplierFormModalProps {
  editing: SupplierEditing;
  onClose: () => void;
  /** Gọi sau khi thêm thành công, trước khi đóng hộp thoại. */
  onCreated: (supplier: Supplier) => void;
}

export function SupplierFormModal({ editing, onClose, onCreated }: SupplierFormModalProps) {
  const [form] = Form.useForm<SupplierFormValues>();
  const { message } = App.useApp();
  const create = useCreateSupplier();
  const update = useUpdateSupplier();
  const mutation = editing.mode === 'create' ? create : update;
  // Đang gửi thì không cho đóng: đóng sẽ bỏ mất kết quả của yêu cầu đang chạy.
  const pending = mutation.isPending;

  const showErrors = (error: unknown) => {
    const errors = formErrorsOf(error);
    if (errors) form.setFields(errors);
  };

  const onFinish = (values: SupplierFormValues) => {
    const request: SupplierRequest = {
      name: values.name,
      code: values.code,
      phone: values.phone,
      address: values.address,
      note: values.note,
    };
    if (editing.mode === 'create') {
      create.mutate(request, {
        onSuccess: (supplier) => {
          void message.success(`Đã thêm Nhà cung cấp "${supplier.name}"`);
          onCreated(supplier);
          onClose();
        },
        onError: showErrors,
      });
      return;
    }
    if (isUnchanged(editing.supplier, request)) {
      onClose();
      return;
    }
    update.mutate(
      { id: editing.supplier.supplierId, request },
      {
        onSuccess: (supplier) => {
          void message.success(`Đã lưu Nhà cung cấp "${supplier.name}"`);
          onClose();
        },
        onError: showErrors,
      },
    );
  };

  const initialValues: SupplierFormValues =
    editing.mode === 'edit'
      ? {
          name: editing.supplier.name,
          code: editing.supplier.code ?? '',
          phone: editing.supplier.phone ?? '',
          address: editing.supplier.address ?? '',
          note: editing.supplier.note ?? '',
        }
      : { name: '', code: '', phone: '', address: '', note: '' };

  return (
    <Modal
      open
      title={editing.mode === 'create' ? 'Thêm Nhà cung cấp' : 'Sửa Nhà cung cấp'}
      okText={editing.mode === 'create' ? 'Thêm' : 'Lưu'}
      cancelText="Hủy"
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) onClose();
      }}
      confirmLoading={pending}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {mutation.isError && !formErrorsOf(mutation.error) ? <ErrorAlert error={mutation.error} /> : null}
      <Form<SupplierFormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending}
        initialValues={initialValues}
      >
        <Form.Item
          name="name"
          label="Tên Nhà cung cấp"
          rules={[nameRequired, ...textRules('Tên Nhà cung cấp', NAME_MAX)]}
        >
          <Input autoFocus autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="code"
          label="Mã Nhà cung cấp"
          rules={[...textRules('Mã Nhà cung cấp', CODE_MAX), codeChars]}
          extra="Không bắt buộc. Chữ cái không dấu, chữ số, dấu - và dấu _ (bắt đầu bằng chữ hoặc số); lưu chữ in hoa."
        >
          <Input placeholder="Ví dụ NCC-01" autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label="Số điện thoại" rules={[...textRules('Số điện thoại', PHONE_MAX), phoneChars]}>
          <Input placeholder="Không bắt buộc" inputMode="tel" autoComplete="off" />
        </Form.Item>
        <Form.Item name="address" label="Địa chỉ" rules={textRules('Địa chỉ', ADDRESS_MAX)}>
          <Input placeholder="Không bắt buộc" autoComplete="off" />
        </Form.Item>
        <Form.Item name="note" label="Ghi chú" rules={textRules('Ghi chú', NOTE_MAX, true)}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
