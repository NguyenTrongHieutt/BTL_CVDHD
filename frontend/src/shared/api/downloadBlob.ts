export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  // Thu hồi URL sau khi trình duyệt đã nhận lệnh tải.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
