/**
 * features/platform — đăng nhập, đăng ký, Cửa hàng, Nhân viên, Nhật ký thao tác.
 * Người giữ: Hiếu (AD-20). Route: `/platform/…` và `/login`.
 */
import { lazy } from 'react';
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { LoginPage } from './pages/LoginPage';

export const platformFeature: FeatureDefinition = {
  module: 'platform',
  owner: 'Hiếu',
  publicRoutes: [
    { path: '/login', Component: LoginPage },
    { path: '/platform/register', Component: lazy(() => import('./pages/RegisterPage').then(({ RegisterPage }) => ({ default: RegisterPage }))) },
  ],
  routes: [
    {
      path: '/platform/profile',
      title: 'Thông tin cá nhân',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/ProfilePage').then(({ ProfilePage }) => ({ default: ProfilePage }))),
      menu: { group: 'settings', order: 10 },
    },
    {
      path: '/platform/store',
      title: 'Thông tin Cửa hàng',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/StoreSettingsPage').then(({ StoreSettingsPage }) => ({ default: StoreSettingsPage }))),
      menu: { group: 'settings', order: 20 },
    },
    {
      path: '/platform/staff',
      title: 'Tài khoản Nhân viên',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/StaffPage').then(({ StaffPage }) => ({ default: StaffPage }))),
      menu: { group: 'settings', order: 30 },
    },
    {
      path: '/platform/audit-log',
      title: 'Nhật ký thao tác',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/AuditLogPage').then(({ AuditLogPage }) => ({ default: AuditLogPage }))),
      menu: { group: 'settings', order: 40 },
    },
  ],
};
