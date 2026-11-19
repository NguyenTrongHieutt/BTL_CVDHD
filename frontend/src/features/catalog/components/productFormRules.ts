/**
 * Luật kiểm phía client dùng chung cho form Sản phẩm (SP-01, SP-02) và form Biến thể (SP-07), giống backend: chuẩn hóa
 * chuỗi như `TextFields`, độ dài đếm theo ký tự, ô tiền và số lượng là ô chữ (chữ số, dấu chấm nhóm nghìn). Người giữ:
 * Hiếu — features/catalog (AD-20).
 */
import type { FormRule } from 'antd';
import { VARIANT_LABEL_SEPARATOR } from '../api/products';
import { MONEY_MAX, hasInvalidChars, normalizeText, parseWholeNumber, textLength } from './textUtils';

export const NAME_MAX = 200;
export const UNIT_MAX = 30;
export const DESCRIPTION_MAX = 2000;
export const CODE_MAX = 50;
export const BARCODE_CHARS = /^[A-Za-z0-9]*$/;
export const QUANTITY_MAX = 2_147_483_647;
/** Nhãn Biến thể tối đa 100 ký tự; tên, giá trị thuộc tính tối đa 50 (SP-07). */
export const VARIANT_LABEL_MAX = 100;
export const ATTRIBUTE_MAX = 50;

/** Chuỗi tùy chọn: chuẩn hóa, bỏ trống thì không gửi. */
export function optionalText(value: string | undefined, multiline = false): string | undefined {
  const text = value === undefined ? '' : normalizeText(value, multiline);
  return text === '' ? undefined : text;
}

/** Độ dài tối đa và ký tự không hợp lệ, kiểm như backend. */
export function textRules(label: string, max: number, multiline = false): FormRule[] {
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

/** Mã vạch: độ dài, ký tự như backend (chỉ chữ cái không dấu và chữ số). */
export function barcodeRules(): FormRule[] {
  return [
    ...textRules('Mã vạch', CODE_MAX),
    {
      transform: (value?: string) => (value === undefined ? undefined : normalizeText(value)),
      pattern: BARCODE_CHARS,
      message: 'Mã vạch chỉ gồm chữ cái không dấu và chữ số',
    },
  ];
}

/** Số nguyên từ 0 tới `max`; bỏ trống thì để luật `required` (nếu có) báo. */
export function wholeNumber(label: string, max: number): FormRule {
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

/** Giá bán bắt buộc, số nguyên đồng. */
export function salePriceRules(): FormRule[] {
  return [{ required: true, whitespace: true, message: 'Nhập giá bán' }, wholeNumber('Giá bán', MONEY_MAX)];
}

/** Giá trị hợp lệ của ô số (đã qua `wholeNumber`), hoặc `null` khi trống hay sai. */
export function validNumber(text: string | undefined, max: number): number | null {
  const value = parseWholeNumber(text);
  return value === null || Number.isNaN(value) || value > max ? null : value;
}

/**
 * Luật của ô Giá vốn ban đầu, đọc ô Tồn kho đầu kỳ ở `quantityPath` (ô phải khai báo `dependencies` tới đó): có Tồn
 * kho thì Giá vốn phải lớn hơn 0, và tích hai số không quá giới hạn tiền (như backend).
 */
export function openingUnitCostRules(quantityPath: (string | number)[]): FormRule[] {
  return [
    wholeNumber('Giá vốn ban đầu', MONEY_MAX),
    ({ getFieldValue }) => ({
      validator: (_: unknown, text?: string) => {
        const quantity = validNumber(getFieldValue(quantityPath) as string | undefined, QUANTITY_MAX);
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
  ];
}

/** Thuộc tính đã chuẩn hóa từ các cặp (tên, giá trị); cặp mà tên hoặc giá trị trống thì bỏ. */
export function attributesOf(pairs: readonly { name?: string; value?: string }[]): Record<string, string> {
  const attributes: Record<string, string> = {};
  for (const pair of pairs) {
    const name = optionalText(pair.name);
    const value = optionalText(pair.value);
    if (name !== undefined && value !== undefined) attributes[name] = value;
  }
  return attributes;
}

/** Nhãn backend sẽ lưu: nhãn đã nhập, hoặc giá trị thuộc tính ghép bằng " / " khi bỏ trống; `''` khi không có gì. */
export function variantLabelOf(name: string | undefined, attributes: Record<string, string>): string {
  return optionalText(name) ?? Object.values(attributes).join(VARIANT_LABEL_SEPARATOR);
}

/** Khóa so trùng nhãn, SKU, mã vạch như backend: chuẩn hóa rồi không phân biệt hoa thường. */
export function sameKey(value: string): string {
  return normalizeText(value).toLowerCase();
}
