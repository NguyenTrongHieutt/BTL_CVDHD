/**
 * Chọn khoảng ngày cho báo cáo và tra cứu. Người giữ: Anh.
 *
 * Phát ra `{ fromDate, toDate }` dạng `yyyy-MM-dd`, bao gồm cả hai đầu (AD-19); chặn chọn quá
 * `maxDays` ngày (mặc định 366). Hiển thị theo kiểu Việt Nam `dd/MM/yyyy`.
 */
import { DatePicker } from 'antd';
import type { CSSProperties } from 'react';
import { useId, useMemo } from 'react';
import type { DateRange } from '../types/common';
import { DISPLAY_DATE_FORMAT, MAX_RANGE_DAYS, dateRangePresets, fromDateRange, toDateRange } from './dateRange';

export interface DateRangePickerProps {
  value?: DateRange | null;
  onChange: (range: DateRange | null) => void;
  /** Số ngày tối đa, tính cả hai đầu. */
  maxDays?: number;
  allowClear?: boolean;
  disabled?: boolean;
  style?: CSSProperties;
}

export function DateRangePicker({
  value,
  onChange,
  maxDays = MAX_RANGE_DAYS,
  allowClear = false,
  disabled,
  style,
}: DateRangePickerProps) {
  const presets = useMemo(() => dateRangePresets(), []);
  const id = useId();
  const startId = `${id}-start`;
  const endId = `${id}-end`;
  return (
    <>
      <label className="visually-hidden" htmlFor={startId}>Từ ngày</label>
      <label className="visually-hidden" htmlFor={endId}>Đến ngày</label>
      <DatePicker.RangePicker
        id={{ start: startId, end: endId }}
        placeholder={['Từ ngày', 'Đến ngày']}
        value={fromDateRange(value)}
        onChange={(dates) => onChange(toDateRange(dates))}
        format={DISPLAY_DATE_FORMAT}
        presets={presets}
        allowClear={allowClear}
        disabled={disabled}
        style={{ overflowX: 'clip', ...style }}
        disabledDate={(current, { from }) => (from ? Math.abs(current.diff(from, 'day')) >= maxDays : false)}
      />
    </>
  );
}
