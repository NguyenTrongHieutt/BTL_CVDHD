import { describe, expect, it } from 'vitest';
import { MAX_PAGE_SIZE, pageRequestToQuery } from '../api/pagination';
import { fromAntdChange, fromAntdPage, toAntdPage, toAntdPagination, toSortParam } from './dataTablePaging';

describe('đổi số trang backend (từ 0) ↔ antd (từ 1)', () => {
  it('toAntdPage / fromAntdPage là nghịch đảo', () => {
    expect(toAntdPage(0)).toBe(1);
    expect(toAntdPage(4)).toBe(5);
    expect(fromAntdPage(1)).toBe(0);
    expect(fromAntdPage(5)).toBe(4);
    for (const page of [0, 1, 7, 99]) expect(fromAntdPage(toAntdPage(page))).toBe(page);
  });

  it('chặn giá trị không hợp lệ', () => {
    expect(fromAntdPage(undefined)).toBe(0);
    expect(fromAntdPage(0)).toBe(0);
    expect(toAntdPage(-3)).toBe(1);
  });

  it('toAntdPagination ưu tiên page/size backend trả về', () => {
    expect(toAntdPagination({ items: [], total: 45, page: 2, size: 20 }, { page: 0, size: 50 })).toEqual({
      current: 3,
      pageSize: 20,
      total: 45,
    });
    expect(toAntdPagination(undefined, { page: 1, size: 10 })).toEqual({ current: 2, pageSize: 10, total: 0 });
  });
});

describe('fromAntdChange', () => {
  it('chuyển trang: current antd → page backend', () => {
    expect(fromAntdChange({ current: 3, pageSize: 20 }, {}, { page: 0, size: 20 })).toEqual({ page: 2, size: 20 });
  });

  it('đổi kích thước trang thì về trang đầu, tối đa 100', () => {
    expect(fromAntdChange({ current: 3, pageSize: 50 }, {}, { page: 2, size: 20 })).toEqual({ page: 0, size: 50 });
    expect(fromAntdChange({ current: 1, pageSize: 500 }, {}, { page: 0, size: 20 }).size).toBe(MAX_PAGE_SIZE);
  });

  it('đổi sắp xếp thì về trang đầu và gửi sort=<field>,asc|desc', () => {
    expect(fromAntdChange({ current: 4, pageSize: 20 }, { field: 'name', order: 'ascend' }, { page: 3, size: 20 })).toEqual({
      page: 0,
      size: 20,
      sort: 'name,asc',
    });
    expect(
      fromAntdChange({ current: 2, pageSize: 20 }, { field: 'name', order: 'ascend' }, { page: 0, size: 20, sort: 'name,asc' }),
    ).toEqual({ page: 1, size: 20, sort: 'name,asc' });
  });
});

describe('toSortParam', () => {
  it('ưu tiên columnKey, nối dataIndex lồng nhau', () => {
    expect(toSortParam({ columnKey: 'createdAt', field: 'x', order: 'descend' })).toBe('createdAt,desc');
    expect(toSortParam({ field: ['customer', 'name'], order: 'ascend' })).toBe('customer.name,asc');
    expect(toSortParam([{ field: 'total', order: 'descend' }])).toBe('total,desc');
    expect(toSortParam({ field: 'name', order: null })).toBeUndefined();
    expect(toSortParam(undefined)).toBeUndefined();
  });
});

describe('pageRequestToQuery', () => {
  it('ra đúng tham số page/size/sort của AD-19', () => {
    expect(pageRequestToQuery({ page: 2, size: 1000, sort: 'name,asc' })).toEqual({ page: 2, size: 100, sort: 'name,asc' });
    expect(pageRequestToQuery({ page: -1, size: 0 })).toEqual({ page: 0, size: 20, sort: undefined });
  });
});
