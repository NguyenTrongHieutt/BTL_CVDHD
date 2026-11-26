/**
 * features/inventory — Tồn kho, Phiếu nhập, kiểm kho.
 * Người giữ: Anh (AD-20). Route: `/inventory/…`.
 */
import { lazy } from 'react';
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';

export const inventoryFeature: FeatureDefinition = {
  module: 'inventory',
  owner: 'Anh',
  routes: [
    {
      path: '/inventory/stock',
      title: 'Tồn kho',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/StockPage').then(({ StockPage }) => ({ default: StockPage }))),
      menu: { group: 'inventory', order: 10 },
    },
    {
      path: '/inventory/purchase-receipts',
      title: 'Phiếu nhập',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/PurchaseReceiptsPage').then(({ PurchaseReceiptsPage }) => ({ default: PurchaseReceiptsPage }))),
      menu: { group: 'inventory', order: 20 },
    },
    {
      path: '/inventory/stocktakes',
      title: 'Kiểm kho',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/StocktakesPage').then(({ StocktakesPage }) => ({ default: StocktakesPage }))),
      menu: { group: 'inventory', order: 30 },
    },
  ],
};
