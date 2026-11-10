/**
 * Hộp thoại thêm (FR-1, SP-01) và sửa (FR-2, SP-02) Sản phẩm, dùng chung một form. Người giữ: Hiếu — features/catalog
 * (AD-20). Chỉ Chủ cửa hàng mở được.
 *
 * Thêm: tạo Sản phẩm cùng Biến thể mặc định; có thể kèm Tồn kho đầu kỳ và Giá vốn ban đầu (backend chuyển sang Tồn
 * kho, không lưu ở Sản phẩm). Sửa: form có sẵn dữ liệu của Sản phẩm, không có hai ô Tồn kho đầu kỳ, Giá vốn ban đầu
 * (Giá vốn chỉ đổi qua nhập hàng, AD-15); `PUT` thay toàn bộ (ô tùy chọn để trống thì xóa giá trị cũ, trừ SKU để trống
 * là giữ SKU hiện tại); không đổi gì thì đóng luôn, không gửi. Đổi giá bán thì backend ghi Nhật ký thao tác.
 *
 * Kiểm phía client giống backend (chuẩn hóa chuỗi như `TextFields`, độ dài đếm theo ký tự); lỗi của backend theo
 * trường (`errors[]`), trùng mã vạch, trùng SKU hiện ngay dưới ô tương ứng, lỗi khác (Sản phẩm đã ngừng kinh doanh,
 * không còn, mất mạng…) hiện ở khung báo lỗi phía trên form. Sửa mà Sản phẩm đã ngừng kinh doanh hay không còn (`404`)
 * thì hộp thoại chỉ còn đóng được: form và nút "Lưu" bị khóa. Đang gửi thì không đóng được hộp thoại.
 *
 * Ô tiền, số lượng là ô chữ (không dùng `InputNumber`, vốn lặng lẽ bỏ hoặc kẹp giá trị sai): chỉ nhận chữ số, dấu
 * chấm là dấu nhóm nghìn (tự nhóm khi gõ); có dấu phẩy, dấu trừ hay ký tự khác thì báo lỗi dưới ô.
 */
import { Alert, App, Col, Form, Input, Modal, Row, Select } from 'antd';
import { useRef } from 'react';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { CreateProductRequest, Product, UpdateProductRequest } from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { useCategoryOptions } from '../api/categories';
import { DEFAULT_UNIT, IDEMPOTENCY_KEY_REUSE, defaultVariant, useCreateProduct, useUpdateProduct } from '../api/products';
import {
  MONEY_MAX,
  foldText,
  groupThousands,
  hasInvalidChars,
  normalizeText,
  parseWholeNumber,
  textLength,
} from './textUtils';

const NAME_MAX = 200;
const UNIT_MAX = 30;
const DESCRIPTION_MAX = 2000;
const CODE_MAX = 50;
const BARCODE_CHARS = /^[A-Za-z0-9]*$/;
const QUANTITY_MAX = 2_147_483_647;

/** Thêm hoặc sửa một Sản phẩm. */
export type ProductEditing = { mode: 'create' } | { mode: 'edit'; product: Product };

/** Ô tiền, số lượng giữ nguyên chữ người dùng gõ; đổi thành số khi gửi (`parseWholeNumber`). */
interface ProductFormValues {
  name: string;
  categoryId?: Uuid | null;
  unit?: string;
  salePrice?: string;
  sku?: string;
  barcode?: string;
  openingQuantity?: string;
  openingUnitCost?: string;
  description?: string;
}

type FormField = keyof ProductFormValues;
const FORM_FIELDS: readonly FormField[] = [
  'name',
  'categoryId',
  'unit',
  'salePrice',
  'sku',
  'barcode',
  'openingQuantity',
  'openingUnitCost',
  'description',
];

function isFormField(field: string): field is FormField {
  return (FORM_FIELDS as readonly string[]).includes(field);
}

/** Lỗi 409 của backend thuộc về một ô cụ thể. */
const CONFLICT_FIELDS: Readonly<Record<string, FormField>> = {
  '/errors/catalog/barcode-taken': 'barcode',
  '/errors/catalog/sku-taken': 'sku',
};

/** Sửa Sản phẩm đã ngừng kinh doanh (một chiều, không mở bán lại). */
const PRODUCT_INACTIVE = '/errors/catalog/product-inactive';

/** Khi sửa, SKU để trống là giữ SKU hiện tại (không tự sinh như khi thêm): hướng dẫn riêng cho trùng SKU. */
const EDIT_SKU_TAKEN_HINT = 'Nhập SKU khác, hoặc để trống để giữ SKU hiện tại.';

/**
 * Lỗi của backend đặt được hết dưới các ô của form; `null` nếu có phần không thuộc ô nào (khi đó hiện khung báo
 * lỗi chung).
 */
function formErrorsOf(
  error: unknown,
  mode: ProductEditing['mode'] = 'create',
): { name: FormField; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  const conflictField = CONFLICT_FIELDS[error.type];
  if (conflictField) {
    const { message, hint } = PROBLEM_MESSAGES[error.type];
    const guide = mode === 'edit' && conflictField === 'sku' ? EDIT_SKU_TAKEN_HINT : hint;
    return [{ name: conflictField, errors: guide ? [message, guide] : [message] }];
  }
  if (error.errors.length === 0 || !error.errors.every((e) => isFormField(e.field))) return null;
  const byField = new Map<FormField, string[]>();
  for (const { field, message } of error.errors) {
    if (isFormField(field)) byField.set(field, [...(byField.get(field) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

function isKeyReuse(error: unknown): boolean {
  return isApiError(error) && error.type === IDEMPOTENCY_KEY_REUSE;
}

/** Không sửa được nữa: Sản phẩm đã ngừng kinh doanh, hoặc không còn (`404`). */
function cannotEditAnyMore(error: unknown): boolean {
  return isApiError(error) && (error.type === PRODUCT_INACTIVE || error.status === 404);
}

/** Chuỗi tùy chọn: chuẩn hóa, bỏ trống thì không gửi. */
function optionalText(value: string | undefined, multiline = false): string | undefined {
  const text = value === undefined ? '' : normalizeText(value, multiline);
  return text === '' ? undefined : text;
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

/** Số nguyên từ 0 tới `max`; bỏ trống thì để luật `required` (nếu có) báo. */
function wholeNumber(label: string, max: number) {
  return {
    validator: (_: unknown, text?: string) => {
      const value = parseWholeNumber(text);
      if (value === null) return Promise.resolve();
      if (Number.isNaN(value)) {
        return Promise.reject(
          new Error(text?.trim().startsWith('-') ? `${label} không được âm` : `${label} phải là số nguyên`),
        );
      }
      if (value > max) return Promise.reject(new Error(`${label} quá lớn`));
      return Promise.resolve();
    },
  };
}

/** Giá trị hợp lệ của ô số (đã qua `wholeNumber`), hoặc `null` khi trống hay sai. */
function validNumber(text: string | undefined, max: number): number | null {
  const value = parseWholeNumber(text);
  return value === null || Number.isNaN(value) || value > max ? null : value;
}

/** Các trường chung của thêm và sửa, đã chuẩn hóa như backend; ô tùy chọn bỏ trống thì không gửi. */
function commonFields(values: ProductFormValues): UpdateProductRequest {
  return {
    name: normalizeText(values.name),
    // `required` và `wholeNumber` đã chặn giá trống hoặc sai
    salePrice: parseWholeNumber(values.salePrice) ?? 0,
    categoryId: values.categoryId ?? undefined,
    unit: optionalText(values.unit),
    description: optionalText(values.description, true),
    sku: optionalText(values.sku),
    barcode: optionalText(values.barcode),
  };
}

/**
 * Dữ liệu sửa (đã chuẩn hóa) không khác Sản phẩm đang sửa: khỏi gửi. So như backend: đơn vị trống là "cái", SKU trống
 * là giữ SKU cũ, SKU và mã vạch không phân biệt hoa thường (backend lưu chữ HOA).
 */
function isUnchanged(product: Product, request: UpdateProductRequest): boolean {
  const variant = defaultVariant(product);
  return (
    variant !== undefined &&
    request.name === product.name &&
    (request.categoryId ?? null) === product.categoryId &&
    (request.unit ?? DEFAULT_UNIT) === product.unit &&
    (request.description ?? null) === product.description &&
    request.salePrice === variant.salePrice &&
    (request.sku === undefined || request.sku.toUpperCase() === variant.sku) &&
    (request.barcode?.toUpperCase() ?? null) === variant.barcode
  );
}

/** Giá trị ban đầu của form: trống khi thêm, dữ liệu hiện tại của Sản phẩm (và Biến thể mặc định) khi sửa. */
function initialValuesOf(editing: ProductEditing): Partial<ProductFormValues> {
  if (editing.mode === 'create') return { openingQuantity: '0', openingUnitCost: '0' };
  const { product } = editing;
  const variant = defaultVariant(product);
  return {
    name: product.name,
    categoryId: product.categoryId,
    unit: product.unit,
    salePrice: variant ? groupThousands(String(variant.salePrice)) : undefined,
    sku: variant?.sku ?? '',
    barcode: variant?.barcode ?? '',
    description: product.description ?? '',
  };
}

/** Thêm thì có `onCreated`; sửa thì không (không bao giờ gọi). */
export type ProductFormModalProps =
  | {
      editing: Extract<ProductEditing, { mode: 'create' }>;
      onClose: () => void;
      /** Gọi sau khi thêm thành công, trước khi đóng hộp thoại. */
      onCreated: (product: Product) => void;
    }
  | {
      editing: Extract<ProductEditing, { mode: 'edit' }>;
      onClose: () => void;
      onCreated?: never;
    };

export function ProductFormModal({ editing, onClose, onCreated }: ProductFormModalProps) {
  const [form] = Form.useForm<ProductFormValues>();
  const { message } = App.useApp();
  const create = useCreateProduct();
  const update = useUpdateProduct();
  const mutation = editing.mode === 'create' ? create : update;
  const categories = useCategoryOptions();
  // Đã gửi PUT trong hộp thoại này: máy chủ có thể đã khác dữ liệu lúc mở (mất phản hồi sau khi đã lưu), nên từ đó
  // luôn gửi, không so với dữ liệu lúc mở để bỏ qua.
  const attempted = useRef(false);
  // Đang gửi thì không cho đóng: đóng sẽ bỏ mất kết quả của yêu cầu đang chạy.
  const pending = mutation.isPending;
  // Sản phẩm đã ngừng kinh doanh hay không còn: chỉ còn đóng hộp thoại
  const closeOnly = editing.mode === 'edit' && update.isError && cannotEditAnyMore(update.error);

  const showErrors = (error: unknown) => {
    const errors = formErrorsOf(error, editing.mode);
    if (errors) form.setFields(errors);
  };

  const onFinish = (values: ProductFormValues) => {
    if (closeOnly) return;
    if (editing.mode === 'create') {
      const request: CreateProductRequest = {
        ...commonFields(values),
        openingQuantity: parseWholeNumber(values.openingQuantity) ?? undefined,
        openingUnitCost: parseWholeNumber(values.openingUnitCost) ?? undefined,
      };
      create.mutate(request, {
        onSuccess: (product) => {
          void message.success(`Đã thêm Sản phẩm "${product.name}"`);
          onCreated?.(product);
          onClose();
        },
        onError: showErrors,
      });
      return;
    }
    const request = commonFields(values);
    if (!attempted.current && isUnchanged(editing.product, request)) {
      onClose();
      return;
    }
    attempted.current = true;
    update.mutate(
      { id: editing.product.id, request },
      {
        onSuccess: (product) => {
          void message.success(`Đã lưu Sản phẩm "${product.name}"`);
          onClose();
        },
        onError: showErrors,
      },
    );
  };

  const categoryOptions = (categories.data?.items ?? []).map((c) => ({ value: c.id, label: c.name }));
  // Danh mục hiện tại của Sản phẩm đang sửa luôn có tên để hiện, kể cả khi danh sách chưa tải xong hay tải lỗi
  if (
    editing.mode === 'edit' &&
    editing.product.categoryId !== null &&
    !categoryOptions.some((option) => option.value === editing.product.categoryId)
  ) {
    categoryOptions.push({
      value: editing.product.categoryId,
      label: editing.product.categoryName ?? 'Danh mục hiện tại',
    });
  }

  const numberInputProps = { inputMode: 'numeric' as const, autoComplete: 'off' };

  return (
    <Modal
      open
      title={editing.mode === 'create' ? 'Thêm Sản phẩm' : 'Sửa Sản phẩm'}
      okText={editing.mode === 'create' ? 'Thêm' : 'Lưu'}
      cancelText={closeOnly ? 'Đóng' : 'Hủy'}
      width={720}
      onOk={() => form.submit()}
      onCancel={() => {
        if (!pending) onClose();
      }}
      confirmLoading={pending}
      okButtonProps={{ disabled: closeOnly }}
      cancelButtonProps={{ disabled: pending }}
      closable={!pending}
      keyboard={!pending}
      mask={{ closable: false }}
    >
      {editing.mode === 'create' && create.isError && isKeyReuse(create.error) ? (
        <Alert
          type="warning"
          showIcon
          title="Sản phẩm có thể đã được tạo ở lần gửi trước, hãy kiểm tra bảng."
          description="Bảng Sản phẩm đã được tải lại. Nếu chưa thấy Sản phẩm này, bấm Thêm để gửi lại."
          style={{ marginBottom: 16 }}
        />
      ) : null}
      {mutation.isError && !isKeyReuse(mutation.error) && !formErrorsOf(mutation.error) ? (
        <ErrorAlert error={mutation.error} />
      ) : null}
      <Form<ProductFormValues>
        form={form}
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending || closeOnly}
        initialValues={initialValuesOf(editing)}
      >
        <Form.Item
          name="name"
          label="Tên Sản phẩm"
          rules={[{ required: true, whitespace: true, message: 'Nhập tên Sản phẩm' }, ...textRules('Tên Sản phẩm', NAME_MAX)]}
        >
          <Input autoFocus autoComplete="off" />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} sm={16}>
            <Form.Item
              name="categoryId"
              label="Danh mục"
              help={categories.isError ? 'Không tải được danh sách Danh mục. Đóng hộp thoại rồi mở lại.' : undefined}
              validateStatus={categories.isError ? 'warning' : undefined}
            >
              <Select
                allowClear
                placeholder="Không chọn"
                loading={categories.isLoading}
                options={categoryOptions}
                showSearch={{
                  filterOption: (input, option) => foldText(String(option?.label ?? '')).includes(foldText(input)),
                }}
                notFoundContent="Chưa có Danh mục nào"
                // Ô không có luật kiểm, nên lỗi của backend (Danh mục không tồn tại) phải tự xóa khi chọn lại
                onChange={() => form.setFields([{ name: 'categoryId', errors: [] }])}
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="unit" label="Đơn vị tính" rules={textRules('Đơn vị tính', UNIT_MAX)}>
              <Input placeholder={DEFAULT_UNIT} autoComplete="off" />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={16}>
          <Col xs={24} sm={8}>
            <Form.Item
              name="salePrice"
              label="Giá bán"
              normalize={groupThousands}
              rules={[{ required: true, whitespace: true, message: 'Nhập giá bán' }, wholeNumber('Giá bán', MONEY_MAX)]}
            >
              <Input {...numberInputProps} suffix="₫" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="sku" label="SKU" rules={textRules('SKU', CODE_MAX)}>
              <Input
                placeholder={editing.mode === 'create' ? 'Để trống để tự sinh' : 'Để trống để giữ SKU cũ'}
                autoComplete="off"
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item
              name="barcode"
              label="Mã vạch"
              rules={[
                ...textRules('Mã vạch', CODE_MAX),
                {
                  transform: (value?: string) => (value === undefined ? undefined : normalizeText(value)),
                  pattern: BARCODE_CHARS,
                  message: 'Mã vạch chỉ gồm chữ cái không dấu và chữ số',
                },
              ]}
            >
              <Input autoComplete="off" />
            </Form.Item>
          </Col>
        </Row>
        {editing.mode === 'create' ? (
          <Row gutter={16}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="openingQuantity"
                label="Tồn kho đầu kỳ"
                extra="Số hàng đang có khi bắt đầu dùng hệ thống."
                normalize={groupThousands}
                rules={[wholeNumber('Tồn kho đầu kỳ', QUANTITY_MAX)]}
              >
                <Input {...numberInputProps} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="openingUnitCost"
                label="Giá vốn ban đầu"
                extra="Giá nhập của mỗi đơn vị hàng tồn đầu kỳ."
                dependencies={['openingQuantity']}
                normalize={groupThousands}
                rules={[
                  wholeNumber('Giá vốn ban đầu', MONEY_MAX),
                  ({ getFieldValue }) => ({
                    validator: (_: unknown, text?: string) => {
                      const quantity = validNumber(
                        getFieldValue('openingQuantity') as string | undefined,
                        QUANTITY_MAX,
                      );
                      if (!quantity) return Promise.resolve();
                      const cost = parseWholeNumber(text);
                      if (cost === null || cost === 0) {
                        return Promise.reject(new Error('Có Tồn kho đầu kỳ thì nhập Giá vốn ban đầu lớn hơn 0'));
                      }
                      const validCost = validNumber(text, MONEY_MAX);
                      // Như backend: tổng giá trị tồn đầu kỳ phải còn là một số tiền hợp lệ
                      if (validCost !== null && BigInt(quantity) * BigInt(validCost) > BigInt(MONEY_MAX)) {
                        return Promise.reject(new Error('Tồn kho đầu kỳ × Giá vốn ban đầu quá lớn'));
                      }
                      return Promise.resolve();
                    },
                  }),
                ]}
              >
                <Input {...numberInputProps} suffix="₫" />
              </Form.Item>
            </Col>
          </Row>
        ) : null}
        <Form.Item name="description" label="Mô tả" rules={textRules('Mô tả', DESCRIPTION_MAX, true)}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
