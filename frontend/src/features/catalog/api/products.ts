/**
 * API Sản phẩm (FR-1, SP-01; FR-2, SP-02; FR-3, SP-03; FR-5, SP-05). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Bảng dùng khóa `['catalog', 'products', params]`, `params` gồm bộ lọc và trang. Thêm thành công thì invalidate `['catalog', 'products']`,
 * `['inventory', 'stock']` (Sản phẩm mới có dòng Tồn kho, kèm Tồn kho đầu kỳ nếu có) và `['catalog', 'categories']`
 * (số Sản phẩm của Danh mục đổi). Thêm Sản phẩm là một chứng từ (Tồn kho đầu kỳ `OPENING`) nên có
 * `Idempotency-Key` (AD-7). Sửa xong (thành công hay lỗi) thì invalidate `['catalog', 'products']` (bảng hiện giá mới
 * ngay), `['catalog', 'categories']`, `['inventory', 'stock']` và `['sales', 'sellable']` (giá, tên, mã ở màn bán
 * hàng). Ngừng kinh doanh xong (thành công hay lỗi) thì invalidate `['catalog', 'products']`, `['inventory', 'stock']`
 * và `['sales', 'sellable']` (Sản phẩm ẩn khỏi màn bán hàng). Tải lên, gỡ ảnh (SP-01b) xong (thành công hay lỗi) thì
 * invalidate `['catalog', 'products']` (ảnh thu nhỏ ở bảng đổi theo `imageUrl`). Thêm, sửa, ngừng kinh doanh, tải lên, gỡ
 * ảnh cũng invalidate `['inventory', 'ledger']` (KHO-07: thêm có thể ghi dòng Tồn đầu kỳ; sổ kho hiện tên Sản phẩm).
 * Nhập từ Excel (SP-06) xong (thành công hay lỗi) thì invalidate `['catalog', 'products']`, `['catalog', 'categories']`
 * (Danh mục mới, số Sản phẩm), `['inventory', 'stock']` và `['inventory', 'ledger']` (Tồn đầu kỳ của các dòng đã nhập).
 * Biến thể (FR-7, SP-07): thêm Sản phẩm có thể kèm nhiều Biến thể (`CreateProductWithVariantsRequest`); thêm, sửa,
 * ngừng từng Biến thể xong (thành công hay lỗi) thì invalidate `['catalog', 'products']`, `['inventory', 'stock']`,
 * `['sales', 'sellable']` và `['inventory', 'ledger']`.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef } from 'react';
import { api, isApiError, newIdempotencyKey, shouldReuseIdempotencyKey } from '../../../shared/api/client';
import { pageRequestToQuery } from '../../../shared/api/pagination';
import type { PageRequest, PageResponse } from '../../../shared/api/pagination';
import type {
  AddVariantRequest,
  CreateProductRequest,
  CreateProductWithVariantsRequest,
  Product,
  ProductSearchParams,
  ProductVariant,
  UpdateProductRequest,
  UpdateVariantRequest,
} from '../../../shared/types/catalog';
import type { Uuid } from '../../../shared/types/common';
import { categoriesQueryKey } from './categories';
import { stockLedgerQueryKey } from './stockLedger';

export const PRODUCTS_PATH = '/api/v1/catalog/products';

export const productsQueryKey = ['catalog', 'products'] as const;
const stockQueryKey = ['inventory', 'stock'] as const;
const sellableQueryKey = ['sales', 'sellable'] as const;

export const IDEMPOTENCY_KEY_REUSE = '/errors/common/idempotency-key-reuse';
/** Đơn vị tính khi bỏ trống (như backend). */
export const DEFAULT_UNIT = 'cái';

/** Biến thể mặc định: giá, SKU, mã vạch của dòng trong bảng và của form sửa (backend trả nó đứng đầu). */
export function defaultVariant(product: Product): ProductVariant | undefined {
  return product.variants.find((v) => v.isDefault) ?? product.variants[0];
}

/** Sản phẩm nhiều Biến thể tối đa 50 Biến thể khi tạo, tối thiểu 2 (như backend). */
export const VARIANTS_MIN = 2;
export const VARIANTS_MAX = 50;
/** Tối đa 3 thuộc tính mỗi Biến thể (như backend). */
export const VARIANT_ATTRIBUTES_MAX = 3;
/** Nhãn ghép từ giá trị thuộc tính khi bỏ trống nhãn (như backend). */
export const VARIANT_LABEL_SEPARATOR = ' / ';
export const VARIANT_NAME_TAKEN = '/errors/catalog/variant-name-taken';
export const LAST_ACTIVE_VARIANT = '/errors/catalog/last-active-variant';

/**
 * Danh sách Sản phẩm có Tìm kiếm thường và lọc (FR-5, SP-05). Bộ lọc trống không gửi; không có `sort` thì backend
 * sắp theo tên không dấu.
 */
export function fetchProducts(
  request: PageRequest,
  filters: ProductSearchParams = {},
  signal?: AbortSignal,
): Promise<PageResponse<Product>> {
  const { q, categoryId, status, minPrice, maxPrice } = filters;
  return api.get<PageResponse<Product>>(PRODUCTS_PATH, {
    query: { ...pageRequestToQuery(request), q: q || undefined, categoryId, status, minPrice, maxPrice },
    signal,
  });
}

/** Lỗi mà Sản phẩm có thể đã được tạo ở lần gửi trước: mất mạng, 5xx, hoặc khóa cũ gửi lại với nội dung khác. */
export function mayHaveBeenCreated(error: unknown): boolean {
  return shouldReuseIdempotencyKey(error) || (isApiError(error) && error.type === IDEMPOTENCY_KEY_REUSE);
}

/**
 * Thêm Sản phẩm. Giữ một `Idempotency-Key` qua các lần gửi lại cho tới khi thành công; sau lỗi mà
 * `shouldReuseIdempotencyKey` trả `false` (dữ liệu sai, trùng mã vạch…) thì lần gửi sau dùng khóa mới (AD-7).
 * Hook sống cùng hộp thoại thêm: mở hộp thoại mới là khóa mới. Lỗi mà Sản phẩm có thể đã được tạo
 * (`mayHaveBeenCreated`) cũng làm mới bảng, để người dùng thấy Sản phẩm đó trước khi thêm lại.
 */
export function useCreateProduct() {
  const queryClient = useQueryClient();
  const idempotencyKey = useRef<string | null>(null);
  const refreshAfterCreate = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: productsQueryKey }),
      queryClient.invalidateQueries({ queryKey: stockQueryKey }),
      queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
      queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
    ]);
  return useMutation({
    mutationFn: (request: CreateProductRequest | CreateProductWithVariantsRequest) => {
      idempotencyKey.current ??= newIdempotencyKey();
      return api.post<Product>(PRODUCTS_PATH, request, { idempotencyKey: idempotencyKey.current });
    },
    onSuccess: async () => {
      idempotencyKey.current = null;
      await refreshAfterCreate();
    },
    onError: (error) => {
      if (mayHaveBeenCreated(error)) void refreshAfterCreate();
      if (!shouldReuseIdempotencyKey(error)) idempotencyKey.current = null;
    },
  });
}

function productPath(id: Uuid): string {
  return `${PRODUCTS_PATH}/${encodeURIComponent(id)}`;
}

/**
 * Chi tiết một Sản phẩm, cả hàng đã ngừng kinh doanh (`GET /api/v1/catalog/products/{id}`): ô chọn Sản phẩm của màn
 * Lịch sử xuất nhập kho dùng khi mở từ link có `productId`. Khóa `['catalog', 'products', { id }]`.
 */
export function fetchProduct(id: Uuid, signal?: AbortSignal): Promise<Product> {
  return api.get<Product>(productPath(id), { signal });
}

/**
 * Sửa Sản phẩm (FR-2, SP-02), chỉ Chủ cửa hàng: `PUT /api/v1/catalog/products/{id}`, thay toàn bộ (trường tùy chọn
 * không gửi thì xóa, trừ SKU là giữ nguyên), trả chi tiết Sản phẩm. Không có `Idempotency-Key`: gửi lại cùng nội dung
 * ra cùng kết quả. Xong, kể cả khi lỗi, thì tải lại bảng Sản phẩm (giá mới hiện ngay; `404`, `409 product-inactive`
 * cho thấy bảng đang cũ), Danh mục (số Sản phẩm đổi khi đổi Danh mục), Tồn kho và hàng bán được (giá, tên, mã đổi);
 * mất phản hồi có thể là máy chủ đã lưu rồi.
 */
export function useUpdateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, request }: { id: Uuid; request: UpdateProductRequest }) =>
      api.put<Product>(productPath(id), request),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockQueryKey }),
        queryClient.invalidateQueries({ queryKey: sellableQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
      ]),
  });
}

function deactivateProductPath(id: Uuid): string {
  return `${productPath(id)}/deactivate`;
}

/**
 * Ngừng kinh doanh (FR-3, SP-03), chỉ Chủ cửa hàng: `POST /api/v1/catalog/products/{id}/deactivate`, không có body,
 * trả chi tiết Sản phẩm. Một chiều; gọi lại với Sản phẩm đã ngừng vẫn `200` nên không cần `Idempotency-Key`.
 * Xong, kể cả khi lỗi, thì tải lại bảng Sản phẩm (dòng đổi trạng thái), Tồn kho và hàng bán được (Sản phẩm ẩn khỏi
 * màn bán hàng, tìm kiếm): mất phản hồi có thể là máy chủ đã ngừng rồi, `404` cho thấy bảng đang cũ.
 */
export function useDeactivateProduct() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.post<Product>(deactivateProductPath(id)),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockQueryKey }),
        queryClient.invalidateQueries({ queryKey: sellableQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
      ]),
  });
}

function variantsPath(productId: Uuid): string {
  return `${productPath(productId)}/variants`;
}

function variantPath(productId: Uuid, variantId: Uuid): string {
  return `${variantsPath(productId)}/${encodeURIComponent(variantId)}`;
}

/** Sau mọi thao tác Biến thể (cả khi lỗi): bảng Sản phẩm, Tồn kho, hàng bán được, sổ kho tải lại. */
function useRefreshAfterVariantChange() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: productsQueryKey }),
      queryClient.invalidateQueries({ queryKey: stockQueryKey }),
      queryClient.invalidateQueries({ queryKey: sellableQueryKey }),
      queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
    ]);
}

/**
 * Thêm một Biến thể (SP-07), chỉ Chủ cửa hàng: `POST /api/v1/catalog/products/{id}/variants` → chi tiết Sản phẩm.
 * Kèm Tồn kho đầu kỳ nên có `Idempotency-Key` (AD-7), giữ qua các lần gửi lại như `useCreateProduct`; hook sống cùng
 * hộp thoại thêm Biến thể. Xong, kể cả khi lỗi, thì tải lại bảng Sản phẩm, Tồn kho, hàng bán được, sổ kho.
 */
export function useAddVariant() {
  const refresh = useRefreshAfterVariantChange();
  const idempotencyKey = useRef<string | null>(null);
  return useMutation({
    mutationFn: ({ productId, request }: { productId: Uuid; request: AddVariantRequest }) => {
      idempotencyKey.current ??= newIdempotencyKey();
      return api.post<Product>(variantsPath(productId), request, { idempotencyKey: idempotencyKey.current });
    },
    onSuccess: () => {
      idempotencyKey.current = null;
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) idempotencyKey.current = null;
    },
    onSettled: refresh,
  });
}

/**
 * Sửa một Biến thể (SP-07): `PUT /api/v1/catalog/products/{id}/variants/{variantId}`, thay toàn bộ, trả chi tiết Sản
 * phẩm. Không có `Idempotency-Key`. Xong, kể cả khi lỗi, thì tải lại các bảng liên quan.
 */
export function useUpdateVariant() {
  const refresh = useRefreshAfterVariantChange();
  return useMutation({
    mutationFn: ({ productId, variantId, request }: { productId: Uuid; variantId: Uuid; request: UpdateVariantRequest }) =>
      api.put<Product>(variantPath(productId, variantId), request),
    onSettled: refresh,
  });
}

/**
 * Ngừng kinh doanh một Biến thể (SP-07): `POST …/variants/{variantId}/deactivate`, không có body, trả chi tiết Sản
 * phẩm. Một chiều; gọi lại vẫn `200`. Biến thể đang bán cuối cùng: `409 last-active-variant`. Xong, kể cả khi lỗi, thì
 * tải lại các bảng liên quan (Biến thể ẩn khỏi màn bán hàng).
 */
export function useDeactivateVariant() {
  const refresh = useRefreshAfterVariantChange();
  return useMutation({
    mutationFn: ({ productId, variantId }: { productId: Uuid; variantId: Uuid }) =>
      api.post<Product>(`${variantPath(productId, variantId)}/deactivate`),
    onSettled: refresh,
  });
}

/** Kiểu ảnh Sản phẩm backend nhận (SP-01b); backend kiểm theo byte đầu tệp, không theo tên hay kiểu khai báo. */
export const PRODUCT_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
/** Ảnh Sản phẩm tối đa 1 MB, như backend. */
export const PRODUCT_IMAGE_MAX_BYTES = 1_048_576;

function productImagePath(id: Uuid): string {
  return `${productPath(id)}/image`;
}

/**
 * Tải lên hoặc thay ảnh Sản phẩm (SP-01b), chỉ Chủ cửa hàng: `PUT /api/v1/catalog/products/{id}/image`,
 * `multipart/form-data` với tệp ở phần `file`, trả chi tiết Sản phẩm có `imageUrl` mới. Không có `Idempotency-Key`
 * (gửi lại chỉ thay ảnh thêm một lần). Thêm Sản phẩm là hai bước: tạo xong mới tải ảnh. Xong, kể cả khi lỗi, thì tải lại
 * bảng Sản phẩm: mất phản hồi có thể là máy chủ đã lưu ảnh rồi.
 */
export function useUploadProductImage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, file }: { id: Uuid; file: File }) => {
      const form = new FormData();
      form.append('file', file);
      return api.put<Product>(productImagePath(id), form);
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
      ]),
  });
}

/** File mẫu nhập Sản phẩm từ Excel (SP-06), chỉ Chủ cửa hàng. */
export const PRODUCT_IMPORT_TEMPLATE_PATH = `${PRODUCTS_PATH}/import-template`;
export const PRODUCT_IMPORT_PATH = `${PRODUCTS_PATH}/import`;
/** Tên file mẫu khi tải về (như `Content-Disposition` của backend). */
export const PRODUCT_IMPORT_TEMPLATE_FILENAME = 'mau-nhap-san-pham.xlsx';
/** File nhập tối đa 2 MB, tối đa 1000 dòng dữ liệu, như backend. */
export const PRODUCT_IMPORT_MAX_BYTES = 2 * 1024 * 1024;
export const PRODUCT_IMPORT_MAX_ROWS = 1000;
/** File không đọc được, sai mẫu (thiếu cột bắt buộc, quá 1000 dòng…): `detail` của backend nêu lý do cụ thể. */
export const IMPORT_INVALID_FILE = '/errors/catalog/import-invalid-file';

/** Một lỗi của dòng không nhập được: số dòng Excel (dòng 1 là tiêu đề), tiêu đề cột như file mẫu, lý do. */
export interface ProductImportRowError {
  row: number;
  column: string | null;
  message: string;
}

/** Kết quả một lần nhập: số Sản phẩm đã tạo, các dòng lỗi (theo dòng), tên Danh mục vừa tạo. */
export interface ProductImportResult {
  imported: number;
  failed: ProductImportRowError[];
  createdCategories: string[];
}

/** Tải file mẫu `.xlsx` (`GET /api/v1/catalog/products/import-template`). */
export function fetchProductImportTemplate(): Promise<Blob> {
  return api.get<Blob>(PRODUCT_IMPORT_TEMPLATE_PATH, { responseType: 'blob' });
}

/**
 * Nhập Sản phẩm từ Excel (FR-6, SP-06), chỉ Chủ cửa hàng: `POST /api/v1/catalog/products/import`,
 * `multipart/form-data` với file ở phần `file` → `200 ProductImportResult` (kể cả khi không dòng nào nhập được). Một
 * lần nhập là một chứng từ (`Idempotency-Key`, AD-7): giữ khóa cho cùng một file qua các lần gửi lại sau lỗi mạng, 5xx
 * (backend trả lại kết quả lần đầu, không nhập hai lần); chọn file khác, nhập xong, hay lỗi khác thì dùng khóa mới. Xong,
 * kể cả khi lỗi (mất phản hồi có thể là máy chủ đã nhập rồi), thì tải lại bảng Sản phẩm, Danh mục, Tồn kho, sổ kho.
 */
export function useImportProducts() {
  const queryClient = useQueryClient();
  const idempotency = useRef<{ file: File; key: string } | null>(null);
  return useMutation({
    mutationFn: (file: File) => {
      if (idempotency.current?.file !== file) idempotency.current = { file, key: newIdempotencyKey() };
      const form = new FormData();
      form.append('file', file);
      return api.post<ProductImportResult>(PRODUCT_IMPORT_PATH, form, { idempotencyKey: idempotency.current.key });
    },
    onSuccess: () => {
      idempotency.current = null;
    },
    onError: (error) => {
      if (!shouldReuseIdempotencyKey(error)) idempotency.current = null;
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: categoriesQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
      ]),
  });
}

/**
 * Gỡ ảnh Sản phẩm (SP-01b), chỉ Chủ cửa hàng: `DELETE /api/v1/catalog/products/{id}/image` → `204` (cả khi chưa có
 * ảnh). Xong, kể cả khi lỗi, thì tải lại bảng Sản phẩm.
 */
export function useRemoveProductImage() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: Uuid) => api.delete<void>(productImagePath(id)),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: productsQueryKey }),
        queryClient.invalidateQueries({ queryKey: stockLedgerQueryKey }),
      ]),
  });
}
