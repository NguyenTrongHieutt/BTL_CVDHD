/**
 * Hợp đồng giữa feature và khung ứng dụng (AD-20). Người giữ: Anh.
 *
 * Mỗi `features/<module>/index.ts` export một `FeatureDefinition`. `src/app` ghép các feature thành
 * router và menu, nên feature không cần (và không được) import nhau hay import `src/app`.
 */
import type { ComponentType } from 'react';
import type { ModuleId } from './types/common';
import type { Role } from './types/platform';

/** Nhóm menu bên trái, theo PRD 7.4. Nhãn và thứ tự nhóm do `src/app/menu.tsx` quyết định. */
export type MenuGroupId = 'sales' | 'products' | 'customers' | 'inventory' | 'reports' | 'assistant' | 'settings';

export interface FeatureMenuEntry {
  group: MenuGroupId;
  /** Thứ tự trong nhóm, nhỏ đứng trước. */
  order: number;
  /** Nhãn menu, mặc định là `title` của route. */
  label?: string;
}

export interface FeatureRoute {
  /** Đường dẫn tuyệt đối có tiền tố `/<module>/` (`/assistant` cho Trợ lý AI). */
  path: string;
  /** Tiêu đề màn hình bằng tiếng Việt, dùng cho menu và tab trình duyệt. */
  title: string;
  /**
   * Vai trò được thấy màn hình và mục menu (PRD 4.7). Chỉ để ẩn giao diện:
   * quyền thật do backend kiểm (AD-9, AD-16).
   */
  roles: readonly Role[];
  Component: ComponentType;
  /** Không khai báo thì route không hiện trong menu. */
  menu?: FeatureMenuEntry;
}

/** Route không cần đăng nhập (chỉ `platform`: đăng nhập, đăng ký). */
export interface PublicRoute {
  path: string;
  Component: ComponentType;
}

export interface FeatureDefinition {
  module: ModuleId;
  /** Người giữ thư mục feature (AD-20). */
  owner: 'Anh' | 'Hiếu' | 'Lân';
  routes: readonly FeatureRoute[];
  publicRoutes?: readonly PublicRoute[];
}

export const ALL_ROLES: readonly Role[] = ['OWNER', 'STAFF'];
export const OWNER_ONLY: readonly Role[] = ['OWNER'];
