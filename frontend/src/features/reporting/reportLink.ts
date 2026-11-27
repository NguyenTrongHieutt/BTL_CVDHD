import type { DateRange } from '../../shared/types/common';

function isoDay(value: string | null): number | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const time = Date.parse(value);
  if (!Number.isFinite(time) || new Date(time).toISOString().slice(0, 10) !== value) return undefined;
  return time;
}

export function linkedReportRange(fallback: () => DateRange): DateRange {
  const url = new URLSearchParams(window.location.search);
  const fromDate = url.get('fromDate');
  const toDate = url.get('toDate');
  const from = isoDay(fromDate);
  const to = isoDay(toDate);
  if (!fromDate || !toDate || from === undefined || to === undefined || to < from || to - from >= 366 * 86_400_000) {
    return fallback();
  }
  return { fromDate, toDate };
}
