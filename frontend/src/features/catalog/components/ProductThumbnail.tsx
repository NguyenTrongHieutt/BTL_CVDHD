/**
 * Ảnh Sản phẩm thu nhỏ (SP-01b), ở bảng Sản phẩm và ô xem trước của form. Người giữ: Hiếu — features/catalog (AD-20).
 *
 * Khung vuông, ảnh cắt phủ kín khung (backend không thu nhỏ ảnh, trình duyệt tự co). Chưa có ảnh, hoặc ảnh không tải được
 * (đã bị gỡ ở nơi khác, mất mạng), thì hiện biểu tượng ảnh trên nền xám nhạt. Không có chữ nào trong khung, nên ô chứa
 * nó vẫn chỉ có tên Sản phẩm.
 */
import { PictureOutlined } from '@ant-design/icons';
import { theme } from 'antd';
import { useState } from 'react';

export interface ProductThumbnailProps {
  /** `imageUrl` của Sản phẩm hoặc URL xem trước của tệp vừa chọn; `null` khi không có ảnh. */
  src: string | null;
  /** Cạnh khung, px. */
  size?: number;
  /** Mặc định rỗng: ảnh trang trí đứng cạnh tên Sản phẩm. */
  alt?: string;
}

export function ProductThumbnail({ src, size = 40, alt = '' }: ProductThumbnailProps) {
  const { token } = theme.useToken();
  // Ảnh không tải được thì nhớ đúng URL đó; URL đổi (ảnh mới) thì thử lại
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const box = {
    width: size,
    height: size,
    flex: 'none',
    borderRadius: token.borderRadiusSM,
    background: token.colorFillTertiary,
  } as const;

  if (src === null || src === failedSrc) {
    return (
      <span
        aria-hidden
        style={{
          ...box,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: token.colorTextQuaternary,
          fontSize: Math.round(size / 2),
        }}
      >
        <PictureOutlined />
      </span>
    );
  }
  return (
    <img
      src={src}
      alt={alt}
      width={size}
      height={size}
      loading="lazy"
      decoding="async"
      style={{ ...box, display: 'block', objectFit: 'cover' }}
      onError={() => setFailedSrc(src)}
    />
  );
}
