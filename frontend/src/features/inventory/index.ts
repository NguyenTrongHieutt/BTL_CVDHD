/**
 * features/inventory — Tồn kho, Phiếu nhập, kiểm kho.
 * Người giữ: Anh (AD-20). Route: `/inventory/…`.
 */
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { PurchaseReceiptsPage } from './pages/PurchaseReceiptsPage';
import { StockPage } from './pages/StockPage';
import { StocktakesPage } from './pages/StocktakesPage';

export const inventoryFeature: FeatureDefinition = {
  module: 'inventory',
  owner: 'Anh',
  routes: [
    {
      path: '/inventory/stock',
      title: 'Tồn kho',
      roles: ALL_ROLES,
      Component: StockPage,
      menu: { group: 'inventory', order: 10 },
    },
    {
      path: '/inventory/purchase-receipts',
      title: 'Phiếu nhập',
      roles: OWNER_ONLY,
      Component: PurchaseReceiptsPage,
      menu: { group: 'inventory', order: 20 },
    },
    {
      path: '/inventory/stocktakes',
      title: 'Kiểm kho',
      roles: OWNER_ONLY,
      Component: StocktakesPage,
      menu: { group: 'inventory', order: 30 },
    },
  ],
};
