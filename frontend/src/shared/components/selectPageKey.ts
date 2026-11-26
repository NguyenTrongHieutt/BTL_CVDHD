import type { KeyboardEvent } from 'react';

export interface SelectPaging {
  page: number;
  pageCount: number;
  onPageChange: (page: number) => void;
}

export function handleSelectPageKey(event: KeyboardEvent, { page, pageCount, onPageChange }: SelectPaging) {
  if (pageCount <= 1 || (event.key !== 'PageUp' && event.key !== 'PageDown')) return;
  event.preventDefault();
  event.stopPropagation();
  const next = page + (event.key === 'PageUp' ? -1 : 1);
  if (next >= 0 && next < pageCount) onPageChange(next);
}
