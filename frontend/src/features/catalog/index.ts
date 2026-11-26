/**
 * features/catalog — Sản phẩm, Danh mục, Khách hàng, Nhà cung cấp, Lịch sử xuất nhập kho.
 * Người giữ: Hiếu (AD-20). Route: `/catalog/…`.
 */
import { lazy } from 'react';
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import './catalog.css';

export const catalogFeature: FeatureDefinition = {
  module: 'catalog',
  owner: 'Hiếu',
  routes: [
    {
      path: '/catalog/products',
      title: 'Sản phẩm',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/ProductsPage').then(({ ProductsPage }) => ({ default: ProductsPage }))),
      menu: { group: 'products', order: 10 },
    },
    {
      path: '/catalog/categories',
      title: 'Danh mục',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/CategoriesPage').then(({ CategoriesPage }) => ({ default: CategoriesPage }))),
      menu: { group: 'products', order: 20 },
    },
    {
      path: '/catalog/customers',
      title: 'Khách hàng',
      roles: ALL_ROLES,
      Component: lazy(() => import('./pages/CustomersPage').then(({ CustomersPage }) => ({ default: CustomersPage }))),
      menu: { group: 'customers', order: 10 },
    },
    {
      path: '/catalog/suppliers',
      title: 'Nhà cung cấp',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/SuppliersPage').then(({ SuppliersPage }) => ({ default: SuppliersPage }))),
      menu: { group: 'inventory', order: 40 },
    },
    {
      path: '/catalog/stock-ledger',
      title: 'Lịch sử xuất nhập kho',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/StockLedgerPage').then(({ StockLedgerPage }) => ({ default: StockLedgerPage }))),
      menu: { group: 'inventory', order: 50 },
    },
  ],
};
