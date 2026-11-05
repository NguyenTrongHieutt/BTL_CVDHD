/**
 * Khung báo lỗi API bằng tiếng Việt (dùng `messages.vi.ts`). Người giữ: Anh.
 */
import { Alert } from 'antd';
import type { CSSProperties } from 'react';
import { describeError } from '../messages.vi';

export interface ErrorAlertProps {
  error: unknown;
  style?: CSSProperties;
}

export function ErrorAlert({ error, style }: ErrorAlertProps) {
  const described = describeError(error);
  const lines = [
    described.hint,
    ...described.fieldErrors.map((e) => (e.field ? `${e.field}: ${e.message}` : e.message)),
    described.requestId ? `Mã yêu cầu: ${described.requestId}` : undefined,
  ].filter((line): line is string => Boolean(line));
  return (
    <Alert
      type="error"
      showIcon
      title={described.message}
      description={lines.length > 0 ? lines.map((line) => <div key={line}>{line}</div>) : undefined}
      style={{ marginBottom: 16, ...style }}
    />
  );
}
