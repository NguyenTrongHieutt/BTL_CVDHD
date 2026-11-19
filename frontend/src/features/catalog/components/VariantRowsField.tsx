/**
 * Bảng Biến thể của form thêm Sản phẩm ở chế độ "nhiều Biến thể" (FR-7, SP-07). Người giữ: Hiếu — features/catalog
 * (AD-20). Nằm trong `Form` của `ProductFormModal`, dùng các ô `attributeNames` (tối đa 3 tên thuộc tính chung, ví dụ
 * Màu, Size) và danh sách `variants`: mỗi Biến thể có giá trị của từng thuộc tính, nhãn (trống thì ghép giá trị bằng
 * " / ", như backend), giá bán, SKU, mã vạch, Tồn kho đầu kỳ, Giá vốn ban đầu riêng. Từ 2 đến 50 Biến thể; Biến thể
 * đầu tiên là mặc định. Không sinh tự động mọi tổ hợp thuộc tính: mỗi dòng do người dùng nhập.
 *
 * Kiểm từng ô như backend; trùng nhãn, SKU, mã vạch giữa các dòng kiểm lúc gửi (`variantRowsProblems` ở
 * `variantRows.ts`).
 */
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { Button, Col, Flex, Form, Input, Row, Typography } from 'antd';
import { VARIANTS_MAX, VARIANTS_MIN, VARIANT_ATTRIBUTES_MAX } from '../api/products';
import {
  ATTRIBUTE_MAX,
  CODE_MAX,
  QUANTITY_MAX,
  VARIANT_LABEL_MAX,
  barcodeRules,
  openingUnitCostRules,
  optionalText,
  salePriceRules,
  sameKey,
  textRules,
  wholeNumber,
} from './productFormRules';
import { groupThousands, textLength } from './textUtils';
import { emptyVariantRow, labelOfRow } from './variantRows';
import type { NamePath, VariantRowValues } from './variantRows';

export function VariantRowsField() {
  const form = Form.useFormInstance();
  const attributeNames = (Form.useWatch('attributeNames', form) as (string | undefined)[] | undefined) ?? [];
  const rows = (Form.useWatch('variants', form) as VariantRowValues[] | undefined) ?? [];
  // Chỉ hiện ô giá trị cho thuộc tính đã có tên
  const named = Array.from({ length: VARIANT_ATTRIBUTES_MAX }, (_, i) => i).filter(
    (i) => optionalText(attributeNames[i]) !== undefined,
  );
  // Ô Nhãn lấp phần còn lại của hàng (mỗi ô giá trị chiếm 1/3)
  const labelSpan = named.length === 0 || named.length === VARIANT_ATTRIBUTES_MAX ? 24 : 8 * (3 - named.length);
  const numberInputProps = { inputMode: 'numeric' as const, autoComplete: 'off' };
  const nameDependencies: NamePath[] = Array.from({ length: VARIANT_ATTRIBUTES_MAX }, (_, i) => ['attributeNames', i]);

  return (
    <>
      <Typography.Text strong>Thuộc tính chung (tùy chọn, tối đa {VARIANT_ATTRIBUTES_MAX})</Typography.Text>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        Ví dụ Màu, Size. Mỗi Biến thể nhập giá trị riêng; nhãn để trống thì ghép các giá trị (Đỏ / L).
      </Typography.Paragraph>
      <Row gutter={16}>
        {Array.from({ length: VARIANT_ATTRIBUTES_MAX }, (_, i) => (
          <Col xs={24} sm={8} key={i}>
            <Form.Item
              name={['attributeNames', i]}
              label={`Thuộc tính ${i + 1}`}
              dependencies={nameDependencies.filter((_dependency, j) => j !== i)}
              rules={[
                ...textRules('Tên thuộc tính', ATTRIBUTE_MAX),
                ({ getFieldValue }) => ({
                  validator: (_rule: unknown, value?: string) => {
                    const name = optionalText(value);
                    if (name === undefined) return Promise.resolve();
                    const earlier = Array.from({ length: i }, (_item, j) =>
                      optionalText(getFieldValue(['attributeNames', j]) as string | undefined),
                    ).some((other) => other !== undefined && sameKey(other) === sameKey(name));
                    return earlier ? Promise.reject(new Error('Tên thuộc tính bị lặp')) : Promise.resolve();
                  },
                }),
              ]}
            >
              <Input placeholder={i === 0 ? 'Ví dụ: Màu' : i === 1 ? 'Ví dụ: Size' : undefined} autoComplete="off" />
            </Form.Item>
          </Col>
        ))}
      </Row>
      <Form.List name="variants">
        {(fields, { add, remove }) => (
          <>
            {fields.map((field, index) => {
              const generated = labelOfRow(attributeNames, { ...rows[index], name: undefined });
              return (
                <section
                  key={field.key}
                  aria-label={`Biến thể ${index + 1}`}
                  style={{ border: '1px solid rgba(5, 5, 5, 0.06)', borderRadius: 8, padding: '12px 16px', marginBottom: 12 }}
                >
                  <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
                    <Typography.Text strong>
                      Biến thể {index + 1}
                      {index === 0 ? ' (mặc định)' : ''}
                    </Typography.Text>
                    <Button
                      type="text"
                      icon={<DeleteOutlined />}
                      aria-label={`Xóa Biến thể ${index + 1}`}
                      disabled={fields.length <= VARIANTS_MIN}
                      onClick={() => remove(field.name)}
                    />
                  </Flex>
                  <Row gutter={16}>
                    {named.map((i) => (
                      <Col xs={24} sm={8} key={i}>
                        <Form.Item
                          name={[field.name, 'values', i]}
                          label={optionalText(attributeNames[i])}
                          rules={textRules('Giá trị thuộc tính', ATTRIBUTE_MAX)}
                        >
                          <Input autoComplete="off" />
                        </Form.Item>
                      </Col>
                    ))}
                    <Col xs={24} sm={labelSpan}>
                      <Form.Item
                        name={[field.name, 'name']}
                        label="Nhãn"
                        dependencies={[
                          ...nameDependencies,
                          ...Array.from({ length: VARIANT_ATTRIBUTES_MAX }, (_, i) => ['variants', field.name, 'values', i]),
                        ]}
                        rules={[
                          ...textRules('Nhãn Biến thể', VARIANT_LABEL_MAX),
                          ({ getFieldValue }) => ({
                            validator: (_: unknown, value?: string) => {
                              const names = getFieldValue('attributeNames') as (string | undefined)[] | undefined;
                              const row = getFieldValue(['variants', field.name]) as VariantRowValues | undefined;
                              const label = labelOfRow(names ?? [], { ...row, name: value });
                              if (label === '') {
                                return Promise.reject(new Error('Nhập nhãn hoặc giá trị thuộc tính'));
                              }
                              if (textLength(label) > VARIANT_LABEL_MAX) {
                                return Promise.reject(
                                  new Error(`Nhãn tối đa ${VARIANT_LABEL_MAX} ký tự. Hãy nhập nhãn ngắn hơn`),
                                );
                              }
                              return Promise.resolve();
                            },
                          }),
                        ]}
                      >
                        <Input placeholder={generated || 'Ví dụ: Đỏ / L'} autoComplete="off" />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Row gutter={16}>
                    <Col xs={24} sm={8}>
                      <Form.Item
                        name={[field.name, 'salePrice']}
                        label="Giá bán"
                        normalize={groupThousands}
                        rules={salePriceRules()}
                      >
                        <Input {...numberInputProps} suffix="₫" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item name={[field.name, 'sku']} label="SKU" rules={textRules('SKU', CODE_MAX)}>
                        <Input placeholder="Để trống để tự sinh" autoComplete="off" />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={8}>
                      <Form.Item name={[field.name, 'barcode']} label="Mã vạch" rules={barcodeRules()}>
                        <Input autoComplete="off" />
                      </Form.Item>
                    </Col>
                  </Row>
                  <Row gutter={16}>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={[field.name, 'openingQuantity']}
                        label="Tồn kho đầu kỳ"
                        normalize={groupThousands}
                        rules={[wholeNumber('Tồn kho đầu kỳ', QUANTITY_MAX)]}
                      >
                        <Input {...numberInputProps} />
                      </Form.Item>
                    </Col>
                    <Col xs={24} sm={12}>
                      <Form.Item
                        name={[field.name, 'openingUnitCost']}
                        label="Giá vốn ban đầu"
                        dependencies={[['variants', field.name, 'openingQuantity']]}
                        normalize={groupThousands}
                        rules={openingUnitCostRules(['variants', field.name, 'openingQuantity'])}
                      >
                        <Input {...numberInputProps} suffix="₫" />
                      </Form.Item>
                    </Col>
                  </Row>
                </section>
              );
            })}
            <Button
              icon={<PlusOutlined />}
              disabled={fields.length >= VARIANTS_MAX}
              onClick={() => add(emptyVariantRow())}
              style={{ marginBottom: 16 }}
            >
              Thêm dòng Biến thể
            </Button>
          </>
        )}
      </Form.List>
    </>
  );
}
