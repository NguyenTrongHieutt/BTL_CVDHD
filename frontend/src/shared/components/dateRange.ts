/**
 * Khoảng ngày theo AD-19: `fromDate`/`toDate` dạng `yyyy-MM-dd`, bao gồm cả hai đầu,
 * theo lịch Việt Nam, tối đa 366 ngày. Người giữ: Anh.
 */
import dayjs from 'dayjs';
import type { Dayjs } from 'dayjs';
import type { DateRange, IsoDate } from '../types/common';

export const ISO_DATE_FORMAT = 'YYYY-MM-DD';
export const DISPLAY_DATE_FORMAT = 'DD/MM/YYYY';
export const MAX_RANGE_DAYS = 366;
export const VIETNAM_TIME_ZONE = 'Asia/Ho_Chi_Minh';

const vietnamDateFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: VIETNAM_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** Ngày hôm nay theo giờ Việt Nam, dạng `yyyy-MM-dd`, dù máy đặt múi giờ nào. */
export function todayInVietnam(now: Date = new Date()): IsoDate {
  return vietnamDateFormatter.format(now);
}

/** Giá trị của RangePicker → `DateRange`. Thiếu một đầu thì `null`. */
export function toDateRange(values: readonly [Dayjs | null, Dayjs | null] | null | undefined): DateRange | null {
  if (!values) return null;
  const [from, to] = values;
  if (!from || !to) return null;
  const [start, end] = from.isAfter(to, 'day') ? [to, from] : [from, to];
  return { fromDate: start.format(ISO_DATE_FORMAT), toDate: end.format(ISO_DATE_FORMAT) };
}

/** `DateRange` → giá trị của RangePicker. */
export function fromDateRange(range: DateRange | null | undefined): [Dayjs, Dayjs] | null {
  if (!range) return null;
  return [dayjs(range.fromDate, ISO_DATE_FORMAT), dayjs(range.toDate, ISO_DATE_FORMAT)];
}

/** Số ngày của khoảng, tính cả hai đầu. */
export function countDaysInclusive(range: DateRange): number {
  return dayjs(range.toDate, ISO_DATE_FORMAT).diff(dayjs(range.fromDate, ISO_DATE_FORMAT), 'day') + 1;
}

/** Các khoảng chọn nhanh, tính từ ngày hôm nay theo giờ Việt Nam. */
export function dateRangePresets(today: IsoDate = todayInVietnam()): { label: string; value: [Dayjs, Dayjs] }[] {
  const t = dayjs(today, ISO_DATE_FORMAT);
  const lastMonth = t.subtract(1, 'month');
  return [
    { label: 'Hôm nay', value: [t, t] },
    { label: '7 ngày gần nhất', value: [t.subtract(6, 'day'), t] },
    { label: '30 ngày gần nhất', value: [t.subtract(29, 'day'), t] },
    { label: 'Tháng này', value: [t.startOf('month'), t] },
    { label: 'Tháng trước', value: [lastMonth.startOf('month'), lastMonth.endOf('month')] },
  ];
}
