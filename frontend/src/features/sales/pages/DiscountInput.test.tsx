import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import type { DiscountIntent } from '../../../shared/types/sales';
import { DiscountInput } from './DiscountInput';

it('chỉ áp dụng giảm giá khi xác nhận và cho phép bỏ giảm đã lưu', async () => {
  const onChange = vi.fn<(value: DiscountIntent | undefined) => void>();
  const view = render(<DiscountInput label="Giảm giá cả đơn" scope="order" value={undefined} onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: 'Giảm giá cả đơn' }));
  const dialog = await screen.findByRole('dialog', { name: 'Giảm giá cả đơn' });
  fireEvent.click(within(dialog).getByRole('radio', { name: 'Phần trăm (%)' }));
  const percent = within(dialog).getByRole('spinbutton', { name: 'Phần trăm giảm' });
  fireEvent.change(percent, { target: { value: '10' } });
  expect(onChange).not.toHaveBeenCalled();
  fireEvent.click(within(dialog).getByRole('button', { name: 'Áp dụng giảm giá' }));
  expect(onChange).toHaveBeenCalledWith({ type: 'PERCENT', value: 10 });

  view.rerender(<DiscountInput label="Giảm giá cả đơn" scope="order"
    value={{ type: 'PERCENT', value: 10 }} onChange={onChange} />);
  fireEvent.click(screen.getByRole('button', { name: /Giảm giá cả đơn, đã áp dụng 10%/ }));
  const edit = await screen.findByRole('dialog', { name: 'Giảm giá cả đơn' });
  fireEvent.click(within(edit).getByRole('button', { name: 'Bỏ giảm giá' }));
  expect(onChange).toHaveBeenLastCalledWith(undefined);
});
