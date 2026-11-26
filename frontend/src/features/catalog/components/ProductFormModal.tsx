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
 *
 * Nhiều Biến thể (FR-7, SP-07): khi thêm, bật "Có nhiều Biến thể" thì ô giá bán, SKU, mã vạch, Tồn kho đầu kỳ, Giá vốn
 * cấp Sản phẩm ẩn đi, thay bằng bảng Biến thể (`VariantRowsField`): tên thuộc tính chung, mỗi dòng một Biến thể (2–50,
 * dòng đầu là mặc định); lỗi của backend theo `variants[i].<trường>` hiện dưới ô của dòng đó. Khi sửa, phần "Biến thể"
 * (`ProductVariantsSection`) thêm, sửa, ngừng từng Biến thể, lưu ngay; Sản phẩm nhiều Biến thể thì form không có ô giá,
 * SKU, mã vạch và `PUT` không gửi ba trường này (backend giữ nguyên). Sản phẩm một Biến thể mà ô giá, SKU, mã vạch đang
 * có thay đổi chưa lưu thì chưa thêm được Biến thể (ba ô sẽ biến mất cùng chữ đã gõ): phải bấm "Lưu" trước.
 *
 * Ô Ảnh (SP-01b): chọn ảnh (antd `Upload`, không tự tải lên) thì kiểm kiểu phía client (JPEG, PNG, WebP; kiểu rỗng
 * hay không rõ thì để backend kiểm theo nội dung), thu nhỏ ở trình duyệt nếu lớn hơn 1 MB hoặc cạnh dài hơn 1280 px
 * (`downscaleImage`), rồi mới kiểm 1 MB và xem trước; ảnh chỉ gửi khi bấm "Thêm"/"Lưu". Tệp sai thì bỏ luôn ảnh đã chọn
 * trước đó. Thêm là hai bước: tạo Sản phẩm xong mới tải ảnh; tải ảnh lỗi thì Sản phẩm vẫn đã thêm, hộp thoại vẫn đóng
 * (gửi lại sẽ tạo trùng) và báo để tải lại ảnh bằng "Sửa". Sửa: lưu các trường trước (chỉ khi có đổi), rồi tải ảnh mới
 * hoặc gỡ ảnh; chỉ đổi ảnh thì không gửi `PUT` Sản phẩm; lưu trường xong mà ảnh lỗi thì báo phần nào đã lưu. Lỗi tệp
 * của backend (sai kiểu, quá cỡ, lố giới hạn máy chủ) hiện dưới ô Ảnh.
 */
import { DeleteOutlined, UploadOutlined } from '@ant-design/icons';
import { Alert, App, Button, Checkbox, Col, Flex, Form, Input, Modal, Row, Upload } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { isApiError } from '../../../shared/api/client';
import { ErrorAlert } from '../../../shared/components';
import { PROBLEM_MESSAGES, describeError } from '../../../shared/messages.vi';
import type {
  CreateProductRequest,
  CreateProductWithVariantsRequest,
  Product,
  UpdateProductRequest,
} from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import {
  DEFAULT_UNIT,
  IDEMPOTENCY_KEY_REUSE,
  PRODUCT_IMAGE_MAX_BYTES,
  PRODUCT_IMAGE_TYPES,
  defaultVariant,
  useCreateProduct,
  useRemoveProductImage,
  useUpdateProduct,
  useUploadProductImage,
} from '../api/products';
import { ProductThumbnail } from './ProductThumbnail';
import { ProductVariantsSection } from './ProductVariantsSection';
import { VariantRowsField } from './VariantRowsField';
import { downscaleImage } from './imageResize';
import {
  CODE_MAX,
  DESCRIPTION_MAX,
  NAME_MAX,
  QUANTITY_MAX,
  UNIT_MAX,
  barcodeRules,
  openingUnitCostRules,
  optionalText,
  salePriceRules,
  textRules,
  wholeNumber,
} from './productFormRules';
import { groupThousands, normalizeText, parseWholeNumber } from './textUtils';
import { ProductCategorySelect } from './ProductCategorySelect';
import { useCatalogDialog } from './useCatalogDialog';
import { emptyAttributeNames, emptyVariantRow, variantInputsOf, variantRowsProblems } from './variantRows';
import type { VariantRowValues } from './variantRows';

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
  /** Thêm Sản phẩm nhiều Biến thể (SP-07): giá, mã, tồn đầu kỳ nằm ở từng dòng `variants`. */
  multi?: boolean;
  /** Tên thuộc tính chung của các Biến thể (tối đa 3). */
  attributeNames?: string[];
  variants?: VariantRowValues[];
}

type FormField = Exclude<keyof ProductFormValues, 'multi' | 'attributeNames' | 'variants'>;
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

/** Sản phẩm một Biến thể còn thay đổi giá, mã chưa lưu: chưa cho thêm Biến thể (SP-07). */
const ADD_VARIANT_BLOCKED = 'Lưu thay đổi Giá bán, SKU, Mã vạch trước khi thêm Biến thể';

/** Khi sửa, SKU để trống là giữ SKU hiện tại (không tự sinh như khi thêm): hướng dẫn riêng cho trùng SKU. */
const EDIT_SKU_TAKEN_HINT = 'Nhập SKU khác, hoặc để trống để giữ SKU hiện tại.';

/** Đường dẫn ô của form: ô cấp Sản phẩm, hoặc ô của một dòng Biến thể (`['variants', i, trường]`). */
type FormPath = FormField | (string | number)[];

/** Ô của một dòng Biến thể khi thêm Sản phẩm nhiều Biến thể (SP-07). */
const VARIANT_ROW_FIELDS: ReadonlySet<string> = new Set([
  'name',
  'salePrice',
  'sku',
  'barcode',
  'openingQuantity',
  'openingUnitCost',
]);
const VARIANT_FIELD = /^variants\[(\d+)\]\.(\w+)$/;
/** Các ô cấp Sản phẩm vẫn có ở chế độ nhiều Biến thể. */
const PRODUCT_LEVEL_FIELDS: ReadonlySet<string> = new Set(['name', 'categoryId', 'unit', 'description']);

/** Ô của form ứng với tên trường của backend; `null` khi form không có ô đó (ẩn ở chế độ đang dùng). */
function formPathOf(field: string, multi: boolean): FormPath | null {
  const row = VARIANT_FIELD.exec(field);
  if (row) {
    // Thuộc tính không có một ô riêng: báo dưới ô Nhãn của dòng
    const name = row[2] === 'attributes' ? 'name' : row[2];
    return multi && VARIANT_ROW_FIELDS.has(name) ? ['variants', Number(row[1]), name] : null;
  }
  if (!isFormField(field)) return null;
  return multi && !PRODUCT_LEVEL_FIELDS.has(field) ? null : field;
}

/**
 * Lỗi của backend đặt được hết dưới các ô của form; `null` nếu có phần không thuộc ô nào (khi đó hiện khung báo
 * lỗi chung). `multi`: form không có ô giá, mã cấp Sản phẩm (thêm nhiều Biến thể, sửa Sản phẩm nhiều Biến thể); khi đó
 * trùng SKU, mã vạch không biết của Biến thể nào nên cũng hiện ở khung báo lỗi.
 */
function formErrorsOf(
  error: unknown,
  mode: ProductEditing['mode'] = 'create',
  multi = false,
): { name: FormPath; errors: string[] }[] | null {
  if (!isApiError(error)) return null;
  const conflictField = CONFLICT_FIELDS[error.type];
  if (conflictField) {
    if (multi) return null;
    const { message, hint } = PROBLEM_MESSAGES[error.type];
    const guide = mode === 'edit' && conflictField === 'sku' ? EDIT_SKU_TAKEN_HINT : hint;
    return [{ name: conflictField, errors: guide ? [message, guide] : [message] }];
  }
  if (error.errors.length === 0 || !error.errors.every((e) => formPathOf(e.field, multi) !== null)) return null;
  const byField = new Map<string, { name: FormPath; errors: string[] }>();
  for (const { field, message } of error.errors) {
    const name = formPathOf(field, multi);
    if (name === null) continue;
    const key = JSON.stringify(name);
    const entry = byField.get(key) ?? { name, errors: [] };
    entry.errors.push(message);
    byField.set(key, entry);
  }
  return [...byField.values()];
}

function isKeyReuse(error: unknown): boolean {
  return isApiError(error) && error.type === IDEMPOTENCY_KEY_REUSE;
}

/** Không sửa được nữa: Sản phẩm đã ngừng kinh doanh, hoặc không còn (`404`). */
function cannotEditAnyMore(error: unknown): boolean {
  return isApiError(error) && (error.type === PRODUCT_INACTIVE || error.status === 404);
}

/**
 * Ảnh trong form: giữ như hiện có (khi thêm: không có ảnh), tải tệp vừa chọn lên (`previewUrl` là URL tạm để xem trước),
 * hoặc gỡ ảnh hiện có.
 */
type ImageChoice = { kind: 'keep' } | { kind: 'upload'; file: File; previewUrl: string } | { kind: 'remove' };

const IMAGE_TYPE = '/errors/catalog/image-type';
const IMAGE_TOO_LARGE = '/errors/catalog/image-too-large';
/** Ảnh quá cỡ: giới hạn 1 MB của ảnh, hoặc lố giới hạn chung của máy chủ (bị chặn trước controller). */
const IMAGE_SIZE_ERRORS: ReadonlySet<string> = new Set([IMAGE_TOO_LARGE, '/errors/common/payload-too-large']);

/** Câu hiện dưới ô Ảnh cho lỗi tệp của backend; `null` khi lỗi không thuộc ô Ảnh (hiện ở khung báo lỗi chung). */
function imageFieldMessage(error: unknown): string | null {
  if (!isApiError(error)) return null;
  if (error.type === IMAGE_TYPE) return PROBLEM_MESSAGES[IMAGE_TYPE].message;
  if (IMAGE_SIZE_ERRORS.has(error.type)) return PROBLEM_MESSAGES[IMAGE_TOO_LARGE].message;
  return null;
}

function isImageFileError(error: unknown): boolean {
  return imageFieldMessage(error) !== null;
}

/** Kiểu trình duyệt không nhận ra (tệp không đuôi…): không chặn ở client, để backend kiểm theo nội dung tệp. */
const UNKNOWN_FILE_TYPES: ReadonlySet<string> = new Set(['', 'application/octet-stream']);
const IMAGE_TYPE_PROBLEM = 'Ảnh phải là JPEG, PNG hoặc WebP.';
const IMAGE_SIZE_PROBLEM = 'Ảnh tối đa 1 MB. Hãy chọn ảnh nhỏ hơn.';

function hasAcceptedType(file: File): boolean {
  return UNKNOWN_FILE_TYPES.has(file.type) || (PRODUCT_IMAGE_TYPES as readonly string[]).includes(file.type);
}

/** Các trường cấp Sản phẩm, đã chuẩn hóa như backend; ô tùy chọn bỏ trống thì không gửi. */
function productFields(values: ProductFormValues): UpdateProductRequest {
  return {
    name: normalizeText(values.name),
    categoryId: values.categoryId ?? undefined,
    unit: optionalText(values.unit),
    description: optionalText(values.description, true),
  };
}

/**
 * Các trường chung của thêm và sửa Sản phẩm một Biến thể, đã chuẩn hóa như backend; ô tùy chọn bỏ trống thì không
 * gửi.
 */
function commonFields(values: ProductFormValues): UpdateProductRequest & { salePrice: number } {
  const { name, ...rest } = productFields(values);
  return {
    name,
    // `required` và `wholeNumber` đã chặn giá trống hoặc sai
    salePrice: parseWholeNumber(values.salePrice) ?? 0,
    ...rest,
    sku: optionalText(values.sku),
    barcode: optionalText(values.barcode),
  };
}

/**
 * Dữ liệu sửa (đã chuẩn hóa) không khác Sản phẩm đang sửa: khỏi gửi. So như backend: đơn vị trống là "cái", SKU trống
 * là giữ SKU cũ, SKU và mã vạch không phân biệt hoa thường (backend lưu chữ HOA). Sản phẩm nhiều Biến thể chỉ so các
 * trường cấp Sản phẩm (không gửi giá, mã).
 */
function isUnchanged(product: Product, request: UpdateProductRequest): boolean {
  const variant = defaultVariant(product);
  const sameProduct =
    request.name === product.name &&
    (request.categoryId ?? null) === product.categoryId &&
    (request.unit ?? DEFAULT_UNIT) === product.unit &&
    (request.description ?? null) === product.description;
  if (product.hasVariants) return sameProduct;
  return (
    variant !== undefined &&
    sameProduct &&
    request.salePrice === variant.salePrice &&
    (request.sku === undefined || request.sku.toUpperCase() === variant.sku) &&
    (request.barcode?.toUpperCase() ?? null) === variant.barcode
  );
}

/** Giá trị ban đầu của form: trống khi thêm, dữ liệu hiện tại của Sản phẩm (và Biến thể mặc định) khi sửa. */
function initialValuesOf(editing: ProductEditing): Partial<ProductFormValues> {
  if (editing.mode === 'create') {
    return {
      openingQuantity: '0',
      openingUnitCost: '0',
      multi: false,
      attributeNames: emptyAttributeNames(),
      variants: [emptyVariantRow(), emptyVariantRow()],
    };
  }
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
  const { initialInput, close, modalProps } = useCatalogDialog(onClose);
  const { message } = App.useApp();
  const create = useCreateProduct();
  const update = useUpdateProduct();
  const mutation = editing.mode === 'create' ? create : update;
  const uploadImage = useUploadProductImage();
  const removeImage = useRemoveProductImage();
  // Đã gửi PUT trong hộp thoại này: máy chủ có thể đã khác dữ liệu lúc mở (mất phản hồi sau khi đã lưu), nên từ đó
  // luôn gửi, không so với dữ liệu lúc mở để bỏ qua.
  const attempted = useRef(false);
  const [image, setImage] = useState<ImageChoice>({ kind: 'keep' });
  // Lỗi của ô Ảnh: tệp không hợp lệ (kiểm phía client) hoặc backend từ chối tệp
  const [imageError, setImageError] = useState<string | null>(null);
  // Đang thu nhỏ ảnh vừa chọn; lần chọn mới nhất (kết quả của lần chọn cũ hơn bị bỏ qua)
  const [preparingImage, setPreparingImage] = useState(false);
  const latestPick = useRef(0);
  // Lỗi khác khi tải lên hay gỡ ảnh (mất mạng, Sản phẩm vừa ngừng kinh doanh…): khung báo lỗi chung
  const imageRequestError = uploadImage.error ?? removeImage.error;
  // Đang gửi thì không cho đóng: đóng sẽ bỏ mất kết quả của yêu cầu đang chạy.
  const pending = mutation.isPending || uploadImage.isPending || removeImage.isPending;
  // Đang thu nhỏ ảnh (rất ngắn) thì cũng chưa cho đóng hay gửi, nhưng không hiện vòng xoay trên nút
  const busy = pending || preparingImage;
  // Sản phẩm đã ngừng kinh doanh hay không còn: chỉ còn đóng hộp thoại
  const closeOnly =
    editing.mode === 'edit' &&
    ((update.isError && cannotEditAnyMore(update.error)) || cannotEditAnyMore(imageRequestError));
  // Sản phẩm đang sửa, mới nhất: thêm, sửa, ngừng Biến thể (lưu ngay) trả chi tiết mới (SP-07)
  const [current, setCurrent] = useState<Product | null>(editing.mode === 'edit' ? editing.product : null);
  // Form không có ô giá, mã cấp Sản phẩm: đang thêm nhiều Biến thể, hoặc sửa Sản phẩm nhiều Biến thể
  const multiCreate = Form.useWatch('multi', form) === true;
  const multi = editing.mode === 'create' ? multiCreate : current?.hasVariants === true;
  // Sản phẩm một Biến thể mà ô giá, SKU, mã vạch đang khác dữ liệu đã lưu: thêm Biến thể sẽ làm Sản phẩm thành nhiều
  // Biến thể, ba ô biến mất và "Lưu" sau đó bỏ mất chữ đã gõ, nên phải lưu trước. Ô chưa đọc được (undefined) coi như
  // chưa đổi; SKU để trống là giữ SKU cũ (như khi lưu).
  const watchedPrice = Form.useWatch('salePrice', form) as string | undefined;
  const watchedSku = Form.useWatch('sku', form) as string | undefined;
  const watchedBarcode = Form.useWatch('barcode', form) as string | undefined;
  const savedVariant = current && !current.hasVariants ? defaultVariant(current) : undefined;
  const typedSku = optionalText(watchedSku);
  const unsavedVariantFields =
    savedVariant !== undefined &&
    ((watchedPrice !== undefined && parseWholeNumber(watchedPrice) !== savedVariant.salePrice) ||
      (typedSku !== undefined && typedSku.toUpperCase() !== savedVariant.sku) ||
      (watchedBarcode !== undefined &&
        (optionalText(watchedBarcode)?.toUpperCase() ?? null) !== savedVariant.barcode));

  // Ảnh hiện có của Sản phẩm đang sửa, và ảnh đang hiện ở ô xem trước
  const currentImageUrl = current?.imageUrl ?? null;
  const previewSrc = image.kind === 'upload' ? image.previewUrl : image.kind === 'remove' ? null : currentImageUrl;

  // Thu hồi URL xem trước khi chọn ảnh khác hoặc đóng hộp thoại
  useEffect(() => {
    if (image.kind !== 'upload') return undefined;
    const { previewUrl } = image;
    return () => URL.revokeObjectURL(previewUrl);
  }, [image]);

  const showErrors = (error: unknown) => {
    const errors = formErrorsOf(error, editing.mode, multi);
    if (errors) form.setFields(errors as Parameters<typeof form.setFields>[0]);
  };

  // Lỗi tải, gỡ ảnh của lần trước không còn đúng khi người dùng đổi lựa chọn hoặc gửi lại
  const resetImageRequests = () => {
    uploadImage.reset();
    removeImage.reset();
  };

  // Tệp sai: báo dưới ô Ảnh và bỏ luôn ảnh đã chọn trước đó, để "Lưu" không gửi ảnh cũ
  const rejectImage = (problem: string) => {
    setImageError(problem);
    setImage({ kind: 'keep' });
  };

  const chooseImage = async (picked: File) => {
    const pick = ++latestPick.current;
    resetImageRequests();
    setImageError(null);
    if (!hasAcceptedType(picked)) {
      rejectImage(IMAGE_TYPE_PROBLEM);
      return;
    }
    setPreparingImage(true);
    let file: File;
    try {
      file = await downscaleImage(picked);
    } catch {
      file = picked;
    } finally {
      if (pick === latestPick.current) setPreparingImage(false);
    }
    if (pick !== latestPick.current) return;
    if (file.size > PRODUCT_IMAGE_MAX_BYTES) {
      rejectImage(IMAGE_SIZE_PROBLEM);
      return;
    }
    setImage({ kind: 'upload', file, previewUrl: URL.createObjectURL(file) });
  };

  // Có tệp vừa chọn thì bỏ tệp đó (về ảnh hiện có); không có thì gỡ ảnh hiện có
  const clearImage = () => {
    resetImageRequests();
    setImageError(null);
    setImage(image.kind === 'upload' || currentImageUrl === null ? { kind: 'keep' } : { kind: 'remove' });
  };

  const showImageError = (error: unknown) => {
    const fieldMessage = imageFieldMessage(error);
    if (fieldMessage !== null) setImageError(fieldMessage);
  };

  /** Bước ảnh sau khi Sản phẩm đã lưu: tải ảnh mới hoặc gỡ ảnh, xong thì gọi `done` với Sản phẩm mới nhất. */
  const saveImage = (product: Product, done: (saved: Product) => void, onError: (error: unknown) => void) => {
    if (image.kind === 'keep') {
      done(product);
      return;
    }
    setImageError(null);
    uploadImage.reset();
    removeImage.reset();
    if (image.kind === 'upload') {
      uploadImage.mutate({ id: product.id, file: image.file }, { onSuccess: done, onError });
    } else {
      removeImage.mutate(product.id, { onSuccess: () => done({ ...product, imageUrl: null }), onError });
    }
  };

  const onFinish = (values: ProductFormValues) => {
    // Đang thu nhỏ ảnh vừa chọn (Enter trong ô nhập vẫn gửi form): chờ có ảnh rồi mới gửi
    if (closeOnly || preparingImage) return;
    resetImageRequests();
    if (editing.mode === 'create') {
      let request: CreateProductRequest | CreateProductWithVariantsRequest;
      if (values.multi) {
        // Nhiều Biến thể (SP-07): giá, mã, tồn đầu kỳ ở từng dòng; trùng nhãn, mã giữa các dòng báo trước khi gửi
        const names = values.attributeNames ?? [];
        const rows = values.variants ?? [];
        const problems = variantRowsProblems(names, rows);
        if (problems.length > 0) {
          form.setFields(problems as Parameters<typeof form.setFields>[0]);
          return;
        }
        request = { ...productFields(values), variants: variantInputsOf(names, rows) };
      } else {
        request = {
          ...commonFields(values),
          openingQuantity: parseWholeNumber(values.openingQuantity) ?? undefined,
          openingUnitCost: parseWholeNumber(values.openingUnitCost) ?? undefined,
        };
      }
      const added = (product: Product) => {
        void message.success(`Đã thêm Sản phẩm "${product.name}"`);
        onCreated?.(product);
        close();
      };
      create.mutate(request, {
        // Hai bước: Sản phẩm đã tạo rồi mới tải ảnh. Tải ảnh lỗi thì Sản phẩm vẫn đã có: đóng hộp thoại (gửi lại sẽ tạo
        // trùng) và báo để tải lại ảnh bằng "Sửa".
        onSuccess: (product) =>
          saveImage(product, added, (error) => {
            // Câu dài, cần hành động tiếp: để lâu hơn mặc định (3 giây)
            void message.warning(
              `Đã thêm Sản phẩm "${product.name}" nhưng chưa tải được ảnh: ${describeError(error).message} ` +
                'Bấm "Sửa" ở dòng Sản phẩm để tải lại ảnh.',
              8,
            );
            onCreated?.(product);
            close();
          }),
        onError: showErrors,
      });
      return;
    }
    const product = current ?? editing.product;
    // Sản phẩm nhiều Biến thể: không gửi giá, SKU, mã vạch (backend giữ nguyên); sửa từng Biến thể ở phần Biến thể
    const request = product.hasVariants ? productFields(values) : commonFields(values);
    const fieldsChanged = attempted.current || !isUnchanged(product, request);
    if (!fieldsChanged && image.kind === 'keep') {
      close();
      return;
    }
    const saved = (savedProduct: Product) => {
      void message.success(`Đã lưu Sản phẩm "${savedProduct.name}"`);
      close();
    };
    // Chỉ đổi ảnh: không gửi PUT Sản phẩm
    if (!fieldsChanged) {
      saveImage(product, saved, showImageError);
      return;
    }
    attempted.current = true;
    update.mutate(
      { id: editing.product.id, request },
      {
        // Các trường đã lưu, chỉ ảnh lỗi: nói rõ phần nào đã lưu; hộp thoại vẫn mở để thử lại ảnh
        onSuccess: (savedProduct) =>
          saveImage(savedProduct, saved, (error) => {
            showImageError(error);
            void message.warning(
              `Đã lưu thông tin, chưa lưu được ảnh của Sản phẩm "${savedProduct.name}": ${describeError(error).message}`,
              8,
            );
          }),
        onError: showErrors,
      },
    );
  };

  const numberInputProps = { inputMode: 'numeric' as const, autoComplete: 'off' };

  return (
    <Modal
      {...modalProps}
      className="product-form-modal"
      title={editing.mode === 'create' ? 'Thêm Sản phẩm' : 'Sửa Sản phẩm'}
      okText={editing.mode === 'create' ? 'Thêm' : 'Lưu'}
      cancelText={closeOnly ? 'Đóng' : 'Hủy'}
      width={multiCreate ? 960 : 720}
      onOk={() => form.submit()}
      onCancel={() => {
        if (!busy) close();
      }}
      confirmLoading={pending}
      okButtonProps={{ disabled: closeOnly || preparingImage }}
      cancelButtonProps={{ disabled: busy }}
      closable={!busy}
      keyboard={!busy}
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
      {mutation.isError && !isKeyReuse(mutation.error) && !formErrorsOf(mutation.error, editing.mode, multi) ? (
        <ErrorAlert error={mutation.error} />
      ) : null}
      {editing.mode === 'edit' && imageRequestError && !isImageFileError(imageRequestError) ? (
        <ErrorAlert error={imageRequestError} />
      ) : null}
      <Form<ProductFormValues>
        form={form}
        layout="vertical"
        onFinish={onFinish}
        scrollToFirstError={{ focus: true }}
        disabled={pending || closeOnly}
        initialValues={initialValuesOf(editing)}
      >
        <div className="form-section-title">Thông tin sản phẩm</div>
        <Form.Item
          name="name"
          label="Tên Sản phẩm"
          rules={[{ required: true, whitespace: true, message: 'Nhập tên Sản phẩm' }, ...textRules('Tên Sản phẩm', NAME_MAX)]}
        >
          <Input ref={initialInput} autoComplete="off" />
        </Form.Item>
        <Row gutter={16}>
          <Col xs={24} sm={16}>
            <Form.Item name="categoryId" label="Danh mục">
              <ProductCategorySelect
                ariaLabel="Danh mục"
                placeholder="Không chọn"
                currentName={editing.mode === 'edit' ? editing.product.categoryName : undefined}
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
        {editing.mode === 'create' ? (
          <Form.Item name="multi" valuePropName="checked">
            <Checkbox>Có nhiều Biến thể (ví dụ màu, size): mỗi Biến thể có mã, giá và Tồn kho riêng</Checkbox>
          </Form.Item>
        ) : null}
        {!multi ? <div className="form-section-title">Giá và mã sản phẩm</div> : null}
        {multi ? null : (
          <Row gutter={16}>
            <Col xs={24} sm={8}>
              <Form.Item name="salePrice" label="Giá bán" normalize={groupThousands} rules={salePriceRules()}>
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
              <Form.Item name="barcode" label="Mã vạch" rules={barcodeRules()}>
                <Input autoComplete="off" />
              </Form.Item>
            </Col>
          </Row>
        )}
        {editing.mode === 'create' && !multi ? <div className="form-section-title">Tồn kho đầu kỳ</div> : null}
        {editing.mode === 'create' && !multi ? (
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
                rules={openingUnitCostRules(['openingQuantity'])}
              >
                <Input {...numberInputProps} suffix="₫" />
              </Form.Item>
            </Col>
          </Row>
        ) : null}
        {editing.mode === 'create' && multi ? <VariantRowsField /> : null}
        <div className="form-section-title">Thông tin bổ sung</div>
        <Form.Item name="description" label="Mô tả" rules={textRules('Mô tả', DESCRIPTION_MAX, true)}>
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 6 }} />
        </Form.Item>
        <Form.Item
          label="Ảnh"
          validateStatus={imageError ? 'error' : undefined}
          help={imageError ?? undefined}
          extra="JPEG, PNG hoặc WebP, tối đa 1 MB."
        >
          <Flex align="center" gap={16} wrap>
            <ProductThumbnail src={previewSrc} size={96} alt="Ảnh Sản phẩm" />
            <Flex vertical gap={8} align="flex-start">
              <Upload
                accept={PRODUCT_IMAGE_TYPES.join(',')}
                showUploadList={false}
                // Không tự tải lên: chỉ giữ tệp để xem trước, gửi khi bấm Thêm/Lưu
                beforeUpload={(file) => {
                  void chooseImage(file);
                  return Upload.LIST_IGNORE;
                }}
              >
                <Button icon={<UploadOutlined />}>{previewSrc ? 'Đổi ảnh' : 'Chọn ảnh'}</Button>
              </Upload>
              {previewSrc ? (
                <Button icon={<DeleteOutlined />} onClick={clearImage}>
                  {image.kind === 'upload' ? 'Bỏ ảnh vừa chọn' : 'Gỡ ảnh'}
                </Button>
              ) : null}
            </Flex>
          </Flex>
        </Form.Item>
      </Form>
      {current ? (
        <ProductVariantsSection
          product={current}
          disabled={pending || closeOnly}
          addBlockedReason={unsavedVariantFields ? ADD_VARIANT_BLOCKED : undefined}
          onChange={setCurrent}
        />
      ) : null}
    </Modal>
  );
}
