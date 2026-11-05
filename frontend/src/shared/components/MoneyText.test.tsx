import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MoneyText } from './MoneyText';
import { EMPTY_MONEY, formatMoney } from './money';

/** Intl dùng khoảng trắng không ngắt dòng (U+00A0) trước "₫"; so sánh theo khoảng trắng thường. */
const plain = (text: string | null) => (text ?? '').replace(/ /g, ' ');

describe('formatMoney', () => {
  it('định dạng số nguyên đồng kiểu vi-VN', () => {
    expect(plain(formatMoney(1234000))).toBe('1.234.000 ₫');
    expect(plain(formatMoney(0))).toBe('0 ₫');
    expect(plain(formatMoney(500))).toBe('500 ₫');
    expect(plain(formatMoney(9_007_199_254_740_991))).toBe('9.007.199.254.740.991 ₫');
  });

  it('số âm (tiền hoàn) có dấu trừ', () => {
    expect(plain(formatMoney(-25000))).toBe('-25.000 ₫');
  });

  it('không có giá trị thì hiện gạch ngang', () => {
    expect(formatMoney(null)).toBe(EMPTY_MONEY);
    expect(formatMoney(undefined)).toBe(EMPTY_MONEY);
    expect(formatMoney(Number.NaN)).toBe(EMPTY_MONEY);
  });

  it('giữ số và "₫" trên cùng một dòng', () => {
    expect(formatMoney(1000)).toContain(' ₫');
  });
});

describe('<MoneyText>', () => {
  it('hiển thị số tiền đã định dạng', () => {
    render(<MoneyText value={1234000} strong />);
    const node = screen.getByText((_, element) => element?.classList.contains('money-text') ?? false);
    expect(plain(node.textContent)).toBe('1.234.000 ₫');
  });
});
