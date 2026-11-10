/**
 * Đổi mã `type` của Problem Details sang câu tiếng Việt kèm hướng xử lý (AD-20).
 * Người giữ: Anh. Mỗi chủ module thêm mã của nhóm mình (danh sách gốc: `docs/errors.md`).
 *
 * Thứ tự tra: theo `type` → theo mã trạng thái → câu chung.
 */
import { ApiError, INVALID_RESPONSE_TYPE, NETWORK_ERROR_TYPE, isApiError } from './api/client';

export interface UserMessage {
  /** Câu báo lỗi ngắn. */
  message: string;
  /** Người dùng nên làm gì tiếp. */
  hint?: string;
}

export interface DescribedError extends UserMessage {
  /** Mã yêu cầu để báo cho nhóm khi cần dò log (`X-Request-Id`). */
  requestId?: string;
  /** Lỗi theo từng ô nhập, khi backend trả `errors[]`. */
  fieldErrors: { field: string; message: string }[];
}

export const PROBLEM_MESSAGES: Readonly<Record<string, UserMessage>> = {
  // common
  '/errors/common/validation': {
    message: 'Dữ liệu nhập chưa hợp lệ.',
    hint: 'Kiểm tra lại các ô được đánh dấu rồi gửi lại.',
  },
  '/errors/common/unauthorized': {
    message: 'Phiên đăng nhập đã hết hạn.',
    hint: 'Vui lòng đăng nhập lại.',
  },
  // ai-service dùng tên này cho 401
  '/errors/common/unauthenticated': {
    message: 'Phiên đăng nhập đã hết hạn.',
    hint: 'Vui lòng đăng nhập lại.',
  },
  '/errors/common/forbidden': {
    message: 'Bạn không có quyền thực hiện thao tác này.',
    hint: 'Liên hệ Chủ cửa hàng nếu bạn cần quyền này.',
  },
  '/errors/common/not-found': {
    message: 'Không tìm thấy dữ liệu.',
    hint: 'Dữ liệu có thể đã bị xóa. Tải lại trang để xem danh sách mới nhất.',
  },
  '/errors/common/idempotency-key-reuse': {
    message: 'Yêu cầu này trùng mã với một yêu cầu trước đó nhưng nội dung khác.',
    hint: 'Tải lại trang rồi thao tác lại. Chứng từ trước đó (nếu có) đã được ghi.',
  },
  '/errors/common/internal': {
    message: 'Máy chủ gặp lỗi.',
    hint: 'Thử lại sau ít phút. Nếu vẫn lỗi, báo mã yêu cầu cho nhóm hỗ trợ.',
  },

  // platform
  '/errors/platform/login-taken': {
    message: 'Số điện thoại, email hoặc tên đăng nhập này đã được dùng.',
    hint: 'Chọn tên đăng nhập khác, hoặc đăng nhập bằng tài khoản đã có.',
  },
  '/errors/platform/invalid-credentials': {
    message: 'Sai thông tin đăng nhập hoặc mật khẩu.',
    hint: 'Kiểm tra lại rồi thử lần nữa. Nhân viên quên mật khẩu thì nhờ Chủ cửa hàng đặt lại.',
  },
  '/errors/platform/account-locked': {
    message: 'Tài khoản này đã bị khóa.',
    hint: 'Liên hệ Chủ cửa hàng để mở lại tài khoản.',
  },

  // catalog
  '/errors/catalog/category-name-taken': {
    message: 'Cửa hàng đã có Danh mục tên này.',
    hint: 'Tên Danh mục không phân biệt chữ hoa, chữ thường. Hãy chọn tên khác.',
  },
  '/errors/catalog/category-in-use': {
    message: 'Danh mục này còn Sản phẩm nên không xóa được.',
    hint: 'Mọi Sản phẩm thuộc Danh mục đều tính, kể cả Sản phẩm đã ngừng kinh doanh. Chuyển các Sản phẩm sang Danh mục khác rồi xóa lại.',
  },
  '/errors/catalog/barcode-taken': {
    message: 'Cửa hàng đã có Sản phẩm dùng mã vạch này.',
    hint: 'Kiểm tra lại mã vạch, hoặc tìm Sản phẩm đã có mã vạch này thay vì thêm mới.',
  },
  '/errors/catalog/sku-taken': {
    message: 'Cửa hàng đã có Sản phẩm dùng SKU này.',
    hint: 'Nhập SKU khác, hoặc để trống để hệ thống tự sinh.',
  },

  // sales
  '/errors/sales/invalid-state': {
    message: 'Đơn hàng đã đổi trạng thái nên không làm được thao tác này.',
    hint: 'Đơn có thể đã bị hủy hoặc đã có Phiếu trả hàng. Tải lại Đơn hàng để xem trạng thái mới nhất.',
  },
  '/errors/sales/cart-stale': {
    message: 'Giá hoặc Tồn kho của hàng trong giỏ vừa thay đổi.',
    hint: 'Hệ thống đã tính lại tiền. Kiểm tra tổng tiền (và mã QR mới nếu chuyển khoản) rồi xác nhận lại.',
  },

  // inventory
  '/errors/inventory/variant-inactive': {
    message: 'Sản phẩm này đã ngừng kinh doanh nên không nhập hàng được.',
    hint: 'Bỏ dòng này khỏi Phiếu nhập. Kiểm kho và trả hàng vẫn làm được với hàng còn tồn.',
  },

  // ai
  '/errors/ai/quota-exceeded': {
    message: 'Cửa hàng đã dùng hết lượt hỏi Trợ lý AI hôm nay.',
    hint: 'Hạn mức được làm mới lúc 0 giờ (giờ Việt Nam). Bạn vẫn xem được số liệu trong mục Báo cáo.',
  },
  '/errors/ai/unavailable': {
    message: 'Dịch vụ AI tạm thời chưa sẵn sàng.',
    hint: 'Thử lại sau ít phút. Tìm kiếm thường và Báo cáo vẫn dùng được.',
  },
  '/errors/ai/not-implemented': {
    message: 'Chức năng AI này chưa làm xong.',
  },

  // chỉ sinh ở frontend
  [NETWORK_ERROR_TYPE]: {
    message: 'Không kết nối được máy chủ.',
    hint: 'Kiểm tra kết nối mạng rồi thử lại. Dữ liệu đang nhập vẫn còn trên màn hình.',
  },
  [INVALID_RESPONSE_TYPE]: {
    message: 'Máy chủ trả về dữ liệu không đọc được.',
    hint: 'Tải lại trang. Nếu vẫn lỗi, báo mã yêu cầu cho nhóm hỗ trợ.',
  },
};

/** Câu chung theo mã trạng thái, dùng khi `type` chưa có trong bảng trên. */
export const STATUS_MESSAGES: Readonly<Record<number, UserMessage>> = {
  0: PROBLEM_MESSAGES[NETWORK_ERROR_TYPE],
  400: PROBLEM_MESSAGES['/errors/common/validation'],
  401: PROBLEM_MESSAGES['/errors/common/unauthorized'],
  403: PROBLEM_MESSAGES['/errors/common/forbidden'],
  404: PROBLEM_MESSAGES['/errors/common/not-found'],
  409: {
    message: 'Dữ liệu đã thay đổi so với lúc bạn mở màn hình.',
    hint: 'Tải lại trang rồi thao tác lại.',
  },
  422: {
    message: 'Thao tác vi phạm quy tắc nghiệp vụ.',
  },
  429: {
    message: 'Bạn đã gửi quá nhiều yêu cầu.',
    hint: 'Đợi một lúc rồi thử lại.',
  },
  503: PROBLEM_MESSAGES['/errors/ai/unavailable'],
};

export const FALLBACK_MESSAGE: UserMessage = {
  message: 'Đã có lỗi xảy ra.',
  hint: 'Thử lại. Nếu vẫn lỗi, báo mã yêu cầu cho nhóm hỗ trợ.',
};

function messageFor(error: ApiError): UserMessage {
  const byType = PROBLEM_MESSAGES[error.type];
  if (byType) return byType;
  // Lỗi nghiệp vụ chưa đăng ký: dùng `detail` của backend (đã viết bằng tiếng Việt) nếu có.
  if (error.status === 422 && error.detail) return { message: error.detail };
  const byStatus = STATUS_MESSAGES[error.status];
  if (byStatus) return byStatus;
  if (error.status >= 500) return PROBLEM_MESSAGES['/errors/common/internal'];
  return FALLBACK_MESSAGE;
}

/** Câu báo lỗi cho người dùng từ một lỗi bất kỳ (thường là `ApiError`). */
export function describeError(error: unknown): DescribedError {
  if (isApiError(error)) {
    return {
      ...messageFor(error),
      requestId: error.requestId,
      fieldErrors: error.errors.map(({ field, message }) => ({ field, message })),
    };
  }
  return { ...FALLBACK_MESSAGE, fieldErrors: [] };
}
