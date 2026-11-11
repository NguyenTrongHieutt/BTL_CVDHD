import { beforeEach, describe, expect, it, vi } from 'vitest';
import { IMAGE_MAX_EDGE, JPEG_QUALITY, downscaleImage } from './imageResize';

const ONE_MB = 1_048_576;

function file(name: string, type: string, size: number): File {
  return new File([new Uint8Array(size)], name, { type, lastModified: 1_700_000_000_000 });
}

/** Ảnh đã giải mã giả, kích thước cho trước. */
function bitmap(width: number, height: number) {
  return { width, height, close: vi.fn<() => void>() };
}

/** Canvas 2D giả: ghi lại các lần vẽ; `toBlob` trả `blob` (hoặc `null`) và ghi lại kích thước canvas lúc xuất. */
function fakeCanvas(blob: Blob | null) {
  const context = { fillStyle: '', fillRect: vi.fn<() => void>(), drawImage: vi.fn<() => void>() };
  const exported: { width: number; height: number; type?: string; quality?: unknown }[] = [];
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as never);
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(function toBlob(
    this: HTMLCanvasElement,
    callback: BlobCallback,
    type?: string,
    quality?: unknown,
  ) {
    exported.push({ width: this.width, height: this.height, type, quality });
    callback(blob);
  });
  return { context, exported };
}

function stubDecoder(decoded: ReturnType<typeof bitmap> | Error) {
  const decode = vi.fn<(source: ImageBitmapSource) => Promise<ImageBitmap>>(async () => {
    if (decoded instanceof Error) throw decoded;
    return decoded as unknown as ImageBitmap;
  });
  vi.stubGlobal('createImageBitmap', decode);
  return decode;
}

describe('downscaleImage', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it('trình duyệt không có createImageBitmap: trả tệp gốc', async () => {
    vi.stubGlobal('createImageBitmap', undefined);
    const original = file('anh.png', 'image/png', 3 * ONE_MB);
    expect(await downscaleImage(original)).toBe(original);
  });

  it('không giải mã được (không phải ảnh, HEIC trên Chrome…): trả tệp gốc', async () => {
    stubDecoder(new Error('không giải mã được'));
    const original = file('anh.heic', 'image/heic', 3 * ONE_MB);
    expect(await downscaleImage(original)).toBe(original);
  });

  it('ảnh nhỏ (không quá 1 MB, cạnh không quá 1280 px): trả tệp gốc, không vẽ lại', async () => {
    const decoded = bitmap(IMAGE_MAX_EDGE, 900);
    stubDecoder(decoded);
    const { context } = fakeCanvas(new Blob(['x']));
    const original = file('anh.png', 'image/png', ONE_MB);
    expect(await downscaleImage(original)).toBe(original);
    expect(context.drawImage).not.toHaveBeenCalled();
    expect(decoded.close).toHaveBeenCalled();
  });

  it('ảnh chụp điện thoại 4000×3000: thu nhỏ còn 1280×960, nền trắng, JPEG 0,85, đổi đuôi .jpg', async () => {
    const decoded = bitmap(4000, 3000);
    stubDecoder(decoded);
    const jpeg = new Blob([new Uint8Array(300 * 1024)], { type: 'image/jpeg' });
    const { context, exported } = fakeCanvas(jpeg);
    const original = file('IMG_0001.HEIC.png', 'image/png', 5 * ONE_MB);

    const result = await downscaleImage(original);

    expect(result).not.toBe(original);
    expect(result.name).toBe('IMG_0001.HEIC.jpg');
    expect(result.type).toBe('image/jpeg');
    expect(result.size).toBe(300 * 1024);
    expect(exported).toEqual([{ width: 1280, height: 960, type: 'image/jpeg', quality: JPEG_QUALITY }]);
    expect(context.fillStyle).toBe('#ffffff');
    expect(context.fillRect).toHaveBeenCalledWith(0, 0, 1280, 960);
    expect(context.drawImage).toHaveBeenCalledWith(decoded, 0, 0, 1280, 960);
    expect(decoded.close).toHaveBeenCalled();
  });

  it('ảnh dọc chỉ lớn về cạnh dài: cạnh dài thành 1280', async () => {
    stubDecoder(bitmap(1000, 2000));
    const { exported } = fakeCanvas(new Blob(['x'], { type: 'image/jpeg' }));
    await downscaleImage(file('doc.webp', 'image/webp', 200 * 1024));
    expect(exported).toEqual([{ width: 640, height: 1280, type: 'image/jpeg', quality: JPEG_QUALITY }]);
  });

  it('nặng hơn 1 MB nhưng cạnh không quá 1280 px: giữ kích thước, chỉ nén lại JPEG', async () => {
    stubDecoder(bitmap(1200, 800));
    const { exported } = fakeCanvas(new Blob(['x'], { type: 'image/jpeg' }));
    const result = await downscaleImage(file('nang.png', 'image/png', 2 * ONE_MB));
    expect(exported).toEqual([{ width: 1200, height: 800, type: 'image/jpeg', quality: JPEG_QUALITY }]);
    expect(result.name).toBe('nang.jpg');
  });

  it('canvas không có 2D hay không xuất được: trả tệp gốc', async () => {
    const decoded = bitmap(4000, 3000);
    stubDecoder(decoded);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    const original = file('anh.png', 'image/png', 3 * ONE_MB);
    expect(await downscaleImage(original)).toBe(original);

    fakeCanvas(null);
    expect(await downscaleImage(original)).toBe(original);
    expect(decoded.close).toHaveBeenCalledTimes(2);
  });
});
