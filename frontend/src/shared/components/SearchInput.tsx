/**
 * Ô tìm kiếm có debounce. Người giữ: Anh.
 *
 * Gõ xong `delay` ms thì gọi `onSearch`; bấm Enter thì gọi ngay. Máy quét mã vạch hoạt động như
 * bàn phím và gửi Enter sau mã (Consistency Conventions, dòng "Giao diện"), nên quét xong là tìm ngay.
 */
import { SearchOutlined } from '@ant-design/icons';
import { Input } from 'antd';
import type { InputProps, InputRef } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { Ref } from 'react';

export const SEARCH_DEBOUNCE_MS = 300;

export interface SearchInputProps extends Omit<InputProps, 'value' | 'defaultValue' | 'onChange' | 'onPressEnter'> {
  defaultValue?: string;
  /** Nhận chuỗi đã bỏ khoảng trắng hai đầu. */
  onSearch: (value: string) => void;
  delay?: number;
  ref?: Ref<InputRef>;
}

export function SearchInput({
  defaultValue = '',
  onSearch,
  delay = SEARCH_DEBOUNCE_MS,
  placeholder = 'Tìm kiếm',
  ref,
  ...inputProps
}: SearchInputProps) {
  const [text, setText] = useState(defaultValue);
  const lastEmitted = useRef(defaultValue.trim());
  const onSearchRef = useRef(onSearch);

  useEffect(() => {
    onSearchRef.current = onSearch;
  }, [onSearch]);

  useEffect(() => {
    const value = text.trim();
    if (value === lastEmitted.current) return undefined;
    const timer = setTimeout(() => {
      lastEmitted.current = value;
      onSearchRef.current(value);
    }, delay);
    return () => clearTimeout(timer);
  }, [text, delay]);

  const emitNow = () => {
    const value = text.trim();
    lastEmitted.current = value;
    onSearchRef.current(value);
  };

  return (
    <Input
      ref={ref}
      allowClear
      prefix={<SearchOutlined />}
      placeholder={placeholder}
      {...inputProps}
      value={text}
      onChange={(event) => setText(event.target.value)}
      onPressEnter={emitNow}
    />
  );
}
