/**
 * features/sales — bán hàng, Đơn hàng, trả hàng.
 * Người giữ: Anh (AD-20). Route: `/sales/…`.
 */
import { ALL_ROLES } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { OrdersPage } from './pages/OrdersPage';
import { PosPage } from './pages/PosPage';
import { ReturnsPage } from './pages/ReturnsPage';

export const salesFeature: FeatureDefinition = {
  module: 'sales',
  owner: 'Anh',
  routes: [
    {
      path: '/sales/pos',
      title: 'Bán hàng',
      roles: ALL_ROLES,
      Component: PosPage,
      menu: { group: 'sales', order: 10 },
    },
    {
      path: '/sales/orders',
      title: 'Đơn hàng',
      roles: ALL_ROLES,
      Component: OrdersPage,
      menu: { group: 'sales', order: 20 },
    },
    {
      path: '/sales/returns',
      title: 'Phiếu trả hàng',
      roles: ALL_ROLES,
      Component: ReturnsPage,
      menu: { group: 'sales', order: 30 },
    },
  ],
};
