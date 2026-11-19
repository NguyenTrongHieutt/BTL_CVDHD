/**
 * Dữ liệu của bảng Biến thể khi thêm Sản phẩm nhiều Biến thể (FR-7, SP-07, `VariantRowsField`): dòng trống, đổi các
 * dòng thành `VariantInput` để gửi, kiểm trùng giữa các dòng. Người giữ: Hiếu — features/catalog (AD-20).
 */
import type { ProductVariant, VariantInput } from '../../../shared/types/catalog';
import { VARIANT_ATTRIBUTES_MAX, VARIANT_LABEL_SEPARATOR } from '../api/products';
import { optionalText, sameKey, variantLabelOf } from './productFormRules';
import { parseWholeNumber } from './textUtils';

/** Đường dẫn của một ô trong form (như `NamePath` của antd). */
export type NamePath = (string | number)[];

/** Một dòng Biến thể trong form: chữ người dùng gõ (ô số đổi thành số khi gửi). */
export interface VariantRowValues {
  /** Giá trị của thuộc tính thứ `i` (theo `attributeNames`). */
  values?: string[];
  name?: string;
  salePrice?: string;
  sku?: string;
  barcode?: string;
  openingQuantity?: string;
  openingUnitCost?: string;
}

/** Dòng mới: chưa có gì, tồn đầu kỳ 0. */
export function emptyVariantRow(): VariantRowValues {
  return { values: ['', '', ''], openingQuantity: '0', openingUnitCost: '0' };
}

/** Ba ô tên thuộc tính chung, ban đầu trống. */
export function emptyAttributeNames(): string[] {
  return Array.from({ length: VARIANT_ATTRIBUTES_MAX }, () => '');
}

/** Thuộc tính (tên → giá trị) của một dòng; thuộc tính chưa có tên hay chưa có giá trị thì bỏ. */
export function attributesOfRow(names: readonly (string | undefined)[], row: VariantRowValues): Record<string, string> {
  const attributes: Record<string, string> = {};
  names.forEach((rawName, i) => {
    const name = optionalText(rawName);
    const value = optionalText(row.values?.[i]);
    if (name !== undefined && value !== undefined) attributes[name] = value;
  });
  return attributes;
}

/** Nhãn backend sẽ lưu cho dòng này (`''` khi chưa có nhãn lẫn thuộc tính). */
export function labelOfRow(names: readonly (string | undefined)[], row: VariantRowValues): string {
  return variantLabelOf(row.name, attributesOfRow(names, row));
}

/** Các Biến thể đã chuẩn hóa để gửi (`POST …/products` có `variants`); ô trống thì không gửi. */
export function variantInputsOf(
  names: readonly (string | undefined)[],
  rows: readonly VariantRowValues[],
): VariantInput[] {
  return rows.map((row) => {
    const attributes = attributesOfRow(names, row);
    const name = optionalText(row.name);
    return {
      ...(name === undefined ? {} : { name }),
      ...(Object.keys(attributes).length === 0 ? {} : { attributes }),
      sku: optionalText(row.sku),
      barcode: optionalText(row.barcode),
      // Luật kiểm của ô đã chặn giá trống hoặc sai
      salePrice: parseWholeNumber(row.salePrice) ?? 0,
      openingQuantity: parseWholeNumber(row.openingQuantity) ?? undefined,
      openingUnitCost: parseWholeNumber(row.openingUnitCost) ?? undefined,
    };
  });
}

/**
 * Trùng nhau giữa các dòng (không phân biệt hoa thường, như backend): nhãn, SKU, mã vạch. Trả lỗi đặt dưới ô của dòng
 * đứng sau; rỗng khi không trùng.
 */
export function variantRowsProblems(
  names: readonly (string | undefined)[],
  rows: readonly VariantRowValues[],
): { name: NamePath; errors: string[] }[] {
  const problems: { name: NamePath; errors: string[] }[] = [];
  const seen = { name: new Set<string>(), sku: new Set<string>(), barcode: new Set<string>() };
  rows.forEach((row, i) => {
    const values = { name: labelOfRow(names, row), sku: optionalText(row.sku), barcode: optionalText(row.barcode) };
    for (const field of ['name', 'sku', 'barcode'] as const) {
      const value = values[field];
      if (!value) continue;
      const key = sameKey(value);
      if (seen[field].has(key)) {
        const what = field === 'name' ? 'nhãn' : field === 'sku' ? 'SKU' : 'mã vạch';
        problems.push({ name: ['variants', i, field], errors: [`Trùng ${what} với Biến thể khác ở trên`] });
      }
      seen[field].add(key);
    }
  });
  return problems;
}

/** Nhãn để hiện: nhãn của Biến thể, hoặc "Mặc định" khi Biến thể chưa có nhãn (Sản phẩm một Biến thể). */
export function variantLabel(variant: ProductVariant): string {
  return variant.name ?? 'Mặc định';
}

/**
 * Tên thuộc tính của Biến thể theo thứ tự ghép nhãn: backend lưu `attributes` dạng jsonb, không giữ thứ tự khóa, nên
 * xếp theo vị trí giá trị trong nhãn (tách bằng " / "); tên không tìm thấy trong nhãn xếp sau, theo thứ tự khóa.
 */
export function orderedAttributeNames(variant: Pick<ProductVariant, 'name' | 'attributes'>): string[] {
  const remaining = Object.keys(variant.attributes);
  const ordered: string[] = [];
  for (const part of (variant.name ?? '').split(VARIANT_LABEL_SEPARATOR)) {
    const found = remaining.find((key) => variant.attributes[key] === part.trim());
    if (found !== undefined) {
      ordered.push(found);
      remaining.splice(remaining.indexOf(found), 1);
    }
  }
  return [...ordered, ...remaining];
}
