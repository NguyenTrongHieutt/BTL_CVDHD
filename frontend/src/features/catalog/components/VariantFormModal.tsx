/**
 * Hộp thoại thêm hoặc sửa một Biến thể của Sản phẩm có sẵn (FR-7, SP-07), mở từ form sửa Sản phẩm. Người giữ: Hiếu —
 * features/catalog (AD-20). Chỉ Chủ cửa hàng.
 *
 * Thêm: `POST …/products/{id}/variants` kèm `Idempotency-Key`, có Tồn kho đầu kỳ, Giá vốn ban đầu riêng. Sản phẩm đang
 * một Biến thể thì phải đặt nhãn cho Biến thể hiện có (`existingVariantName`), sau đó Sản phẩm thành nhiều Biến thể.
 * Thêm vào Sản phẩm đã nhiều Biến thể thì tên thuộc tính điền sẵn theo Biến thể mặc định. Sửa: `PUT …/variants/{id}`,
 * thay toàn bộ (nhãn trống thì ghép giá trị thuộc tính, SKU trống là giữ SKU hiện tại, mã vạch trống là xóa); không
 * đổi gì thì đóng luôn. Lưu ngay khi bấm (không chờ "Lưu" của form Sản phẩm).
 *
 * Kiểm phía client như backend, thêm trùng nhãn với Biến thể khác của Sản phẩm (không phân biệt hoa thường, kể cả Biến
 * thể đã ngừng). Lỗi theo trường, trùng nhãn, SKU, mã vạch của backend hiện dưới ô; lỗi khác ở khung báo lỗi. Sản phẩm
 * đã ngừng kinh doanh hay không còn thì chỉ còn đóng được.
 */
import { Alert, App, Col, Form, Input, Modal, Row, Typography } from 'antd';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert, formatMoney } from '../../../shared/components';
import { PROBLEM_MESSAGES } from '../../../shared/messages.vi';
import type { AddVariantRequest, Product, ProductVariant, UpdateVariantRequest } from '../../../shared/types/catalog';
import {
  IDEMPOTENCY_KEY_REUSE,
  VARIANT_ATTRIBUTES_MAX,
  VARIANT_NAME_TAKEN,
  defaultVariant,
  useAddVariant,
  useUpdateVariant,
} from '../api/products';
import {
  ATTRIBUTE_MAX,
  CODE_MAX,
  QUANTITY_MAX,
  VARIANT_LABEL_MAX,
  attributesOf,
  barcodeRules,
  openingUnitCostRules,
  optionalText,
  salePriceRules,
  sameKey,
  textRules,
  variantLabelOf,
  wholeNumber,
} from './productFormRules';
import { groupThousands, normalizeText, parseWholeNumber, textLength } from './textUtils';
import { orderedAttributeNames } from './variantRows';

/** Thêm Biến thể vào Sản phẩm, hoặc sửa một Biến thể của Sản phẩm nhiều Biến thể. */
export type VariantEditing =
  | { mode: 'add'; product: Product }
  | { mode: 'edit'; product: Product; variant: ProductVariant };

interface AttributePair {
  name?: string;
  value?: string;
}

interface VariantFormValues {
  existingVariantName?: string;
  attributes: AttributePair[];
  name?: string;
  salePrice?: string;
  sku?: string;
  barcode?: string;
  openingQuantity?: string;
  openingUnitCost?: string;
}

type VariantField = Exclude<keyof VariantFormValues, 'attributes'>;
const VARIANT_FIELDS: readonly VariantField[] = [
  'existingVariantName',
  'name',
  'salePrice',
  'sku',
  'barcode',
  'openingQuantity',
  'openingUnitCost',
];

const SKU_TAKEN = '/errors/catalog/sku-taken';
const BARCODE_TAKEN = '/errors/catalog/barcode-taken';
const PRODUCT_INACTIVE = '/errors/catalog/product-inactive';
const LABEL_REQUIRED = 'Nhập nhãn hoặc thuộc tính của Biến thể';
const NAME_EXAMPLES: readonly (string | undefined)[] = ['Ví dụ: Màu', 'Ví dụ: Size'];
const VALUE_EXAMPLES: readonly (string | undefined)[] = ['Ví dụ: Đỏ', 'Ví dụ: L'];
const LABEL_TAKEN = 'Sản phẩm đã có Biến thể dùng nhãn này';

/** Lỗi 409 của backend thuộc về một ô cụ thể. */
const CONFLICT_FIELDS: Readonly<Record<string, VariantField>> = {
  [SKU_TAKEN]: 'sku',
  [BARCODE_TAKEN]: 'barcode',
  [VARIANT_NAME_TAKEN]: 'name',
};

/** Tên trường của backend → ô của form (`attributes` không có ô riêng: báo dưới ô Nhãn). */
function fieldOf(field: string): VariantField | null {
  if (field === 'attributes') return 'name';
  return (VARIANT_FIELDS as readonly string[]).includes(field) ? (field as VariantField) : null;
}

/** Lỗi của backend đặt được hết dưới các ô; `null` nếu có phần không thuộc ô nào. */
function formErrorsOf(error: unknown, mode: VariantEditing['mode']): { name: VariantField; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  const conflictField = CONFLICT_FIELDS[error.type];
  if (conflictField) {
    const { message, hint } = PROBLEM_MESSAGES[error.type];
    const guide =
      mode === 'edit' && conflictField === 'sku' ? 'Nhập SKU khác, hoặc để trống để giữ SKU hiện tại.' : hint;
    return [{ name: conflictField, errors: guide ? [message, guide] : [message] }];
  }
  if (error.errors.length === 0 || !error.errors.every((e) => fieldOf(e.field) !== null)) return null;
  const byField = new Map<VariantField, string[]>();
  for (const { field, message } of error.errors) {
    const name = fieldOf(field);
    if (name) byField.set(name, [...(byField.get(name) ?? []), message]);
  }
  return [...byField].map(([name, errors]) => ({ name, errors }));
}

/** Không sửa được nữa: Sản phẩm đã ngừng kinh doanh, hoặc Sản phẩm, Biến thể không còn (`404`). */
function cannotEditAnyMore(error: unknown): boolean {
  return isApiError(error) && (error.type === PRODUCT_INACTIVE || error.status === 404);
}

/** Luôn đủ 3 cặp ô thuộc tính; `names` theo thứ tự hiện, `values` là giá trị theo tên (trống khi thêm). */
function attributePairs(names: string[], values: Record<string, string> = {}): AttributePair[] {
  return Array.from({ length: VARIANT_ATTRIBUTES_MAX }, (_, i) => ({
    name: names[i] ?? '',
    value: names[i] === undefined ? '' : (values[names[i]] ?? ''),
  }));
}

/**
 * Biến thể làm mẫu tên thuộc tính khi thêm Biến thể: Biến thể đang bán đầu tiên có thuộc tính (mặc định trước, rồi theo
 * thứ tự tạo). Sản phẩm chuyển từ một Biến thể có Biến thể mặc định chỉ có nhãn, không có thuộc tính.
 */
function attributeTemplateOf(product: Product): ProductVariant | undefined {
  return product.variants.find((v) => v.status === 'ACTIVE' && Object.keys(v.attributes).length > 0);
}

function initialValuesOf(editing: VariantEditing): VariantFormValues {
  if (editing.mode === 'add') {
    const template = attributeTemplateOf(editing.product);
    const names = template ? orderedAttributeNames(template) : [];
    return { attributes: attributePairs(names), openingQuantity: '0', openingUnitCost: '0' };
  }
  const { variant } = editing;
  return {
    attributes: attributePairs(orderedAttributeNames(variant), variant.attributes),
    name: variant.name ?? '',
    salePrice: groupThousands(String(variant.salePrice)),
    sku: variant.sku,
    barcode: variant.barcode ?? '',
  };
}

function sameAttributes(a: Record<string, string>, b: Record<string, string>): boolean {
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every((k) => b[k] === a[k]);
}

export interface VariantFormModalProps {
  editing: VariantEditing;
  onClose: () => void;
  /** Gọi với chi tiết Sản phẩm mới sau khi lưu thành công, trước khi đóng hộp thoại. */
  onSaved: (product: Product) => void;
}

export function VariantFormModal({ editing, onClose, onSaved }: VariantFormModalProps) {
  const [form] = Form.useForm<VariantFormValues>();
  const { message } = App.useApp();
  const add = useAddVariant();
  const update = useUpdateVariant();
  const mutation = editing.mode === 'add' ? add : update;
  const { product } = editing;
  // Sản phẩm đang một Biến thể: Biến thể hiện có cần nhãn trước khi thêm Biến thể thứ hai
  const labelsExisting = editing.mode === 'add' && !product.hasVariants;
  const existing = defaultVariant(product);
  const pending = mutation.isPending;
  const closeOnly = mutation.isError && cannotEditAnyMore(mutation.error);
  const watchedPairs = Form.useWatch('attributes', form) as AttributePair[] | undefined;
  const generatedLabel = variantLabelOf(undefined, attributesOf(watchedPairs ?? []));

  /** Nhãn của các Biến thể khác (kể cả đã ngừng: vẫn giữ nhãn), để báo trùng trước khi gửi. */
  const otherLabels = new Set(
    product.variants
      .filter((v) => editing.mode === 'add' || v.id !== editing.variant.id)
      .map((v) => v.name)
      .filter((name): name is string => name !== null)
      .map(sameKey),
  );

  const onFinish = (values: VariantFormValues) => {
    if (closeOnly) return;
    const attributes = attributesOf(values.attributes);
    const name = optionalText(values.name);
    const common = {
      ...(name === undefined ? {} : { name }),
      ...(Object.keys(attributes).length === 0 ? {} : { attributes }),
      sku: optionalText(values.sku),
      barcode: optionalText(values.barcode),
      // `required` và `wholeNumber` đã chặn giá trống hoặc sai
      salePrice: parseWholeNumber(values.salePrice) ?? 0,
    };
    const saved = (updated: Product, text: string) => {
      void message.success(text);
      onSaved(updated);
      onClose();
    };
    const showErrors = (error: unknown) => {
      const errors = formErrorsOf(error, editing.mode);
      if (errors) form.setFields(errors);
    };
    const label = variantLabelOf(name, attributes);
    if (editing.mode === 'add') {
      const request: AddVariantRequest = {
        ...common,
        ...(labelsExisting ? { existingVariantName: optionalText(values.existingVariantName) } : {}),
        openingQuantity: parseWholeNumber(values.openingQuantity) ?? undefined,
        openingUnitCost: parseWholeNumber(values.openingUnitCost) ?? undefined,
      };
      add.mutate(
        { productId: product.id, request },
        { onSuccess: (updated) => saved(updated, `Đã thêm Biến thể "${label}"`), onError: showErrors },
      );
      return;
    }
    const { variant } = editing;
    const request: UpdateVariantRequest = common;
    const unchanged =
      !update.isError &&
      label === variant.name &&
      sameAttributes(attributes, variant.attributes) &&
      request.salePrice === variant.salePrice &&
      (request.sku === undefined || request.sku.toUpperCase() === variant.sku) &&
      (request.barcode?.toUpperCase() ?? null) === variant.barcode;
    if (unchanged) {
      onClose();
      return;
    }
    update.mutate(
      { productId: product.id, variantId: variant.id, request },
      { onSuccess: (updated) => saved(updated, `Đã lưu Biến thể "${label}"`), onError: showErrors },
    );
  };

  const numberInputProps = { inputMode: 'numeric' as const, autoComplete: 'off' };

  return (
    <Modal
      open
      title={editing.mode === 'add' ? 'Thêm Biến thể' : 'Sửa Biến thể'}
      okText={editing.mode === 'add' ? 'Thêm Biến thể' : 'Lưu Biến thể'}
      cancelText={closeOnly ? 'Đóng' : 'Hủy'}
      width={640}
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
      <Typography.Paragraph type="secondary">Sản phẩm: {product.name}</Typography.Paragraph>
      {mutation.isError && !formErrorsOf(mutation.error, editing.mode) ? (
        isApiError(mutation.error) && mutation.error.type === IDEMPOTENCY_KEY_REUSE ? (
          <Alert
            type="warning"
            showIcon
            title="Biến thể có thể đã được thêm ở lần gửi trước, hãy kiểm tra danh sách Biến thể."
            style={{ marginBottom: 16 }}
          />
        ) : (
          <ErrorAlert error={mutation.error} />
        )
      ) : null}
      <Form<VariantFormValues>
        form={form}
        // Tên riêng: id các ô không trùng với form Sản phẩm đang mở bên dưới (nhãn trỏ đúng ô)
        name="variant"
        layout="vertical"
        requiredMark={false}
        onFinish={onFinish}
        disabled={pending || closeOnly}
        initialValues={initialValuesOf(editing)}
      >
        {labelsExisting && existing ? (
          <Form.Item
            name="existingVariantName"
            label="Nhãn cho Biến thể hiện có"
            extra={`Biến thể đang bán: SKU ${existing.sku}, giá ${formatMoney(existing.salePrice)}. Ví dụ: "Size S".`}
            rules={[
              { required: true, whitespace: true, message: 'Nhập nhãn cho Biến thể hiện có' },
              ...textRules('Nhãn', VARIANT_LABEL_MAX),
            ]}
          >
            <Input autoComplete="off" autoFocus />
          </Form.Item>
        ) : null}
        <Typography.Text strong>Thuộc tính (tùy chọn, tối đa {VARIANT_ATTRIBUTES_MAX})</Typography.Text>
        <Form.List name="attributes">
          {(fields) =>
            fields.map((field, i) => (
              <Row gutter={16} key={field.key}>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name={[field.name, 'name']}
                    label={`Tên thuộc tính ${i + 1}`}
                    dependencies={[
                      ['attributes', field.name, 'value'],
                      ...fields.filter((other) => other.name !== field.name).map((other) => ['attributes', other.name, 'name']),
                    ]}
                    rules={[
                      ...textRules('Tên thuộc tính', ATTRIBUTE_MAX),
                      ({ getFieldValue }) => ({
                        validator: (_: unknown, value?: string) => {
                          const name = optionalText(value);
                          const pairValue = optionalText(getFieldValue(['attributes', field.name, 'value']) as string);
                          if (name === undefined) {
                            return pairValue === undefined
                              ? Promise.resolve()
                              : Promise.reject(new Error('Nhập tên thuộc tính'));
                          }
                          // Như backend: tên thuộc tính không lặp trong một Biến thể (không phân biệt hoa thường)
                          const earlier = ((getFieldValue('attributes') as AttributePair[] | undefined) ?? [])
                            .slice(0, field.name)
                            .some((pair) => {
                              const other = optionalText(pair.name);
                              return other !== undefined && sameKey(other) === sameKey(name);
                            });
                          return earlier ? Promise.reject(new Error('Tên thuộc tính bị lặp')) : Promise.resolve();
                        },
                      }),
                    ]}
                  >
                    <Input placeholder={NAME_EXAMPLES[i]} autoComplete="off" />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item
                    name={[field.name, 'value']}
                    label={`Giá trị ${i + 1}`}
                    dependencies={[['attributes', field.name, 'name']]}
                    rules={[...textRules('Giá trị thuộc tính', ATTRIBUTE_MAX)]}
                  >
                    <Input placeholder={VALUE_EXAMPLES[i]} autoComplete="off" />
                  </Form.Item>
                </Col>
              </Row>
            ))
          }
        </Form.List>
        <Form.Item
          name="name"
          label="Nhãn Biến thể"
          extra={generatedLabel ? `Để trống: "${generatedLabel}"` : 'Để trống thì ghép giá trị thuộc tính.'}
          dependencies={['attributes', 'existingVariantName']}
          rules={[
            ...textRules('Nhãn Biến thể', VARIANT_LABEL_MAX),
            ({ getFieldValue }) => ({
              validator: (_: unknown, value?: string) => {
                const pairs = (getFieldValue('attributes') as AttributePair[] | undefined) ?? [];
                const label = variantLabelOf(value, attributesOf(pairs));
                if (label === '') return Promise.reject(new Error(LABEL_REQUIRED));
                if (textLength(label) > VARIANT_LABEL_MAX) {
                  return Promise.reject(new Error(`Nhãn tối đa ${VARIANT_LABEL_MAX} ký tự. Hãy nhập nhãn ngắn hơn`));
                }
                const existingLabel = labelsExisting
                  ? normalizeText((getFieldValue('existingVariantName') as string | undefined) ?? '')
                  : '';
                const takenByExisting = existingLabel !== '' && sameKey(existingLabel) === sameKey(label);
                if (otherLabels.has(sameKey(label)) || takenByExisting) {
                  return Promise.reject(new Error(LABEL_TAKEN));
                }
                return Promise.resolve();
              },
            }),
          ]}
        >
          <Input placeholder={generatedLabel || undefined} autoComplete="off" />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} sm={8}>
            <Form.Item name="salePrice" label="Giá bán" normalize={groupThousands} rules={salePriceRules()}>
              <Input {...numberInputProps} suffix="₫" />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="sku" label="SKU" rules={textRules('SKU', CODE_MAX)}>
              <Input
                placeholder={editing.mode === 'add' ? 'Để trống để tự sinh' : 'Để trống để giữ SKU cũ'}
                autoComplete="off"
              />
            </Form.Item>
          </Col>
          <Col xs={24} sm={8}>
            <Form.Item name="barcode" label="Mã vạch" rules={barcodeRules()}>
              <Input autoComplete="off" />
            </Form.Item>
          </Col>
        </Row>
        {editing.mode === 'add' ? (
          <Row gutter={16}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="openingQuantity"
                label="Tồn kho đầu kỳ"
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
                dependencies={['openingQuantity']}
                normalize={groupThousands}
                rules={openingUnitCostRules(['openingQuantity'])}
              >
                <Input {...numberInputProps} suffix="₫" />
              </Form.Item>
            </Col>
          </Row>
        ) : null}
      </Form>
    </Modal>
  );
}
