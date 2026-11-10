/**
 * Thu nhỏ ảnh Sản phẩm ở trình duyệt trước khi tải lên (SP-01b). Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Backend nhận tối đa 1 MB và không thu nhỏ ảnh, còn ảnh chụp điện thoại thường 2–6 MB, cạnh 3000–4000 px (bảng Sản
 * phẩm cũng tải nguyên ảnh). Ảnh lớn hơn 1 MB hoặc có cạnh dài hơn `IMAGE_MAX_EDGE` px thì vẽ lại lên canvas, cạnh dài
 * tối đa `IMAGE_MAX_EDGE`, xuất JPEG chất lượng `JPEG_QUALITY` (nền trắng thay cho phần trong suốt). Trình duyệt không
 * giải mã được (không có `createImageBitmap`, tệp không phải ảnh, HEIC trên Chrome…) hoặc có lỗi thì trả tệp gốc: bên
 * gọi vẫn kiểm 1 MB, backend vẫn kiểm theo nội dung tệp. Không bao giờ ném lỗi.
 */
import { PRODUCT_IMAGE_MAX_BYTES } from '../api/products';

/** Cạnh dài nhất của ảnh sau khi thu nhỏ, px. */
export const IMAGE_MAX_EDGE = 1280;
export const JPEG_QUALITY = 0.85;

/** Tệp nên gửi lên: ảnh đã thu nhỏ (JPEG), hoặc chính `file` khi không cần hay không thu nhỏ được. */
export async function downscaleImage(file: File): Promise<File> {
  if (typeof createImageBitmap !== 'function') return file;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    return file;
  }
  try {
    const longEdge = Math.max(bitmap.width, bitmap.height);
    if (file.size <= PRODUCT_IMAGE_MAX_BYTES && longEdge <= IMAGE_MAX_EDGE) return file;
    const scale = Math.min(1, IMAGE_MAX_EDGE / longEdge);
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.fillStyle = '#ffffff';
    context.fillRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0, width, height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY));
    if (!blob) return file;
    return new File([blob], jpegName(file.name), { type: 'image/jpeg', lastModified: file.lastModified });
  } catch {
    return file;
  } finally {
    bitmap.close();
  }
}

/** Tên tệp với đuôi `.jpg` (ảnh đã đổi sang JPEG). */
function jpegName(name: string): string {
  const dot = name.lastIndexOf('.');
  const base = dot > 0 ? name.slice(0, dot) : name;
  return `${base || 'anh'}.jpg`;
}
