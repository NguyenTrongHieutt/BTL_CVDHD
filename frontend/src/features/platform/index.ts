/**
 * features/platform — đăng nhập, đăng ký, Cửa hàng, Nhân viên, Nhật ký thao tác.
 * Người giữ: Hiếu (AD-20). Route: `/platform/…` và `/login`.
 */
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { AuditLogPage } from './pages/AuditLogPage';
import { LoginPage } from './pages/LoginPage';
import { ProfilePage } from './pages/ProfilePage';
import { RegisterPage } from './pages/RegisterPage';
import { StaffPage } from './pages/StaffPage';
import { StoreSettingsPage } from './pages/StoreSettingsPage';

export const platformFeature: FeatureDefinition = {
  module: 'platform',
  owner: 'Hiếu',
  publicRoutes: [
    { path: '/login', Component: LoginPage },
    { path: '/platform/register', Component: RegisterPage },
  ],
  routes: [
    {
      path: '/platform/profile',
      title: 'Thông tin cá nhân',
      roles: ALL_ROLES,
      Component: ProfilePage,
      menu: { group: 'settings', order: 10 },
    },
    {
      path: '/platform/store',
      title: 'Thông tin Cửa hàng',
      roles: OWNER_ONLY,
      Component: StoreSettingsPage,
      menu: { group: 'settings', order: 20 },
    },
    {
      path: '/platform/staff',
      title: 'Tài khoản Nhân viên',
      roles: OWNER_ONLY,
      Component: StaffPage,
      menu: { group: 'settings', order: 30 },
    },
    {
      path: '/platform/audit-log',
      title: 'Nhật ký thao tác',
      roles: OWNER_ONLY,
      Component: AuditLogPage,
      menu: { group: 'settings', order: 40 },
    },
  ],
};
