/** Chuyển chữ Việt về dạng không dấu để so khớp từ khóa. */
export function foldText(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').replace(/[đĐ]/g, 'd').toLowerCase();
}
