import { describe, expect, it } from 'vitest';
import { profilePhoneError, updateProfileBody } from './profile';
import { PHONE_INVALID, createStaffBody, normalizeVietnamPhone } from './users';

describe('normalizeVietnamPhone (như AccountFields.vietnamPhone ở backend)', () => {
  it.each([
    ['0912345678', '0912345678'],
    ['+84912345678', '0912345678'],
    ['+84 912-345.678', '0912345678'],
    ['0912 345 678', '0912345678'],
    ['  0987.654.321  ', '0987654321'],
  ])('%s thành %s', (input, expected) => {
    expect(normalizeVietnamPhone(input)).toBe(expected);
  });

  it.each([null, undefined, '', '   '])('trống (%s) là chuỗi rỗng', (input) => {
    expect(normalizeVietnamPhone(input)).toBe('');
  });

  it.each(['091234567', '09123456789', '84912345678', '+8491234567', '028 1234 5678', '(091) 234 5678', '0912abc678'])(
    '%s sai dạng là null',
    (input) => {
      expect(normalizeVietnamPhone(input)).toBeNull();
    },
  );
});

describe('updateProfileBody', () => {
  it('số trống thành null (xóa số đang lưu), tên được chuẩn hóa', () => {
    expect(updateProfileBody({ displayName: '  Minh Anh ', phone: '   ' })).toEqual({ displayName: 'Minh Anh', phone: null });
    expect(updateProfileBody({ displayName: 'Minh', phone: null })).toEqual({ displayName: 'Minh', phone: null });
  });

  it('+84 thành 0, bỏ dấu cách, dấu chấm, gạch ngang', () => {
    expect(updateProfileBody({ displayName: 'Minh', phone: '+84 912-345.678' })).toEqual({
      displayName: 'Minh',
      phone: '0912345678',
    });
  });

  it('số không theo quy tắc gửi nguyên như đã gõ (số cũ đang lưu thì backend giữ, số sai thì backend báo)', () => {
    expect(updateProfileBody({ displayName: 'Minh', phone: '028 1234 5678' })).toEqual({
      displayName: 'Minh',
      phone: '028 1234 5678',
    });
    expect(updateProfileBody({ displayName: 'Minh', phone: '0912 abc' })).toEqual({ displayName: 'Minh', phone: '0912 abc' });
  });
});

describe('profilePhoneError', () => {
  it('trống và số Việt Nam là hợp lệ; số khác thì báo lỗi', () => {
    expect(profilePhoneError('')).toBeNull();
    expect(profilePhoneError('+84 912 345 678')).toBeNull();
    expect(profilePhoneError('028 1234 5678')).toBe(PHONE_INVALID);
  });

  it('số đang lưu gửi lại y nguyên (sau chuẩn hóa văn bản) thì hợp lệ dù không theo quy tắc', () => {
    expect(profilePhoneError('028 1234 5678', '028 1234 5678')).toBeNull();
    expect(profilePhoneError(' 028 1234 5678 ', '028 1234 5678')).toBeNull();
    expect(profilePhoneError('028 1234 5679', '028 1234 5678')).toBe(PHONE_INVALID);
  });
});

describe('createStaffBody (TK-02 dùng cùng quy tắc số)', () => {
  it('số được chuẩn hóa thành 0…; trống thì không gửi; sai dạng gửi nguyên', () => {
    const base = { loginKey: ' NV.Mai ', displayName: ' Mai ', password: 'matkhau1' };
    expect(createStaffBody({ ...base, phone: '+84 987.654-321' })).toEqual({
      loginKey: 'nv.mai',
      displayName: 'Mai',
      password: 'matkhau1',
      phone: '0987654321',
    });
    expect(createStaffBody({ ...base, phone: '  ' })).not.toHaveProperty('phone');
    expect(createStaffBody({ ...base, phone: '028 1234 5678' })).toMatchObject({ phone: '028 1234 5678' });
  });
});
