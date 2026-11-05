/**
 * Menu bên trái, dựng từ route của các feature và lọc theo vai trò (PRD 4.7, nhóm theo PRD 7.4).
 * Người giữ: Anh. Ẩn menu chỉ là giao diện; quyền thật do backend kiểm (AD-16).
 */
import {
  BarChartOutlined,
  InboxOutlined,
  RobotOutlined,
  SettingOutlined,
  ShoppingCartOutlined,
  TagsOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import type { MenuProps } from 'antd';
import { createElement } from 'react';
import type { ComponentType } from 'react';
import type { FeatureDefinition, FeatureRoute, MenuGroupId } from '../shared/routing';
import type { Role } from '../shared/types/platform';

interface MenuGroup {
  id: MenuGroupId;
  label: string;
  icon: ComponentType;
}

export const MENU_GROUPS: readonly MenuGroup[] = [
  { id: 'sales', label: 'Bán hàng', icon: ShoppingCartOutlined },
  { id: 'products', label: 'Sản phẩm', icon: TagsOutlined },
  { id: 'customers', label: 'Khách hàng', icon: TeamOutlined },
  { id: 'inventory', label: 'Kho', icon: InboxOutlined },
  { id: 'reports', label: 'Báo cáo', icon: BarChartOutlined },
  { id: 'assistant', label: 'Trợ lý AI', icon: RobotOutlined },
  { id: 'settings', label: 'Cài đặt', icon: SettingOutlined },
];

type MenuItem = NonNullable<MenuProps['items']>[number];

export interface VisibleMenuGroup {
  id: MenuGroupId;
  label: string;
  entries: { path: string; label: string }[];
}

/** Các nhóm menu và mục mà vai trò này được thấy, đã sắp thứ tự. Nhóm rỗng bị bỏ. */
export function visibleMenu(features: readonly FeatureDefinition[], role: Role): VisibleMenuGroup[] {
  const routes = features.flatMap((feature) => feature.routes);
  return MENU_GROUPS.map((group) => ({
    id: group.id,
    label: group.label,
    entries: routes
      .filter((route): route is FeatureRoute & { menu: NonNullable<FeatureRoute['menu']> } =>
        route.menu?.group === group.id && route.roles.includes(role),
      )
      .sort((a, b) => a.menu.order - b.menu.order)
      .map((route) => ({ path: route.path, label: route.menu.label ?? route.title })),
  })).filter((group) => group.entries.length > 0);
}

/** Item cho `<Menu items>`; key của mục là đường dẫn route. */
export function buildMenuItems(features: readonly FeatureDefinition[], role: Role): MenuItem[] {
  return visibleMenu(features, role).map((group): MenuItem => {
    const icon = MENU_GROUPS.find((g) => g.id === group.id)?.icon;
    return {
      type: 'group',
      key: `group:${group.id}`,
      label: icon ? createElement('span', null, createElement(icon), ' ', group.label) : group.label,
      children: group.entries.map((entry) => ({ key: entry.path, label: entry.label })),
    };
  });
}

/** Mục menu đang chọn: route dài nhất khớp với đầu `pathname`. */
export function selectedMenuKey(features: readonly FeatureDefinition[], pathname: string): string | undefined {
  return features
    .flatMap((feature) => feature.routes)
    .filter((route) => route.menu && (pathname === route.path || pathname.startsWith(`${route.path}/`)))
    .sort((a, b) => b.path.length - a.path.length)[0]?.path;
}
