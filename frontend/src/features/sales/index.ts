/**
 * features/sales — bán hàng, Đơn hàng, trả hàng.
 * Người giữ: Anh (AD-20). Route: `/sales/…`.
 */
import { lazy } from 'react';
import { ALL_ROLES } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';

export const salesFeature: FeatureDefinition = {
  module: 'sales',
  owner: 'Anh',
  routes: [
    {
      path: '/sales/pos',
      title: 'Bán hàng',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/PosPage').then(({ PosPage }) => ({ default: PosPage }))),
      menu: { group: 'sales', order: 10 },
    },
    {
      path: '/sales/orders',
      title: 'Đơn hàng',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/OrdersPage').then(({ OrdersPage }) => ({ default: OrdersPage }))),
      menu: { group: 'sales', order: 20 },
    },
    {
      path: '/sales/returns',
      title: 'Phiếu trả hàng',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/ReturnsPage').then(({ ReturnsPage }) => ({ default: ReturnsPage }))),
      menu: { group: 'sales', order: 30 },
    },
  ],
};
