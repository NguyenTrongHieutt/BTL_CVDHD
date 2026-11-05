/**
 * features/catalog — Sản phẩm, Danh mục, Khách hàng, Nhà cung cấp, Lịch sử xuất nhập kho.
 * Người giữ: Hiếu (AD-20). Route: `/catalog/…`.
 */
import { ALL_ROLES, OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';
import { CategoriesPage } from './pages/CategoriesPage';
import { CustomersPage } from './pages/CustomersPage';
import { ProductsPage } from './pages/ProductsPage';
import { StockLedgerPage } from './pages/StockLedgerPage';
import { SuppliersPage } from './pages/SuppliersPage';

export const catalogFeature: FeatureDefinition = {
  module: 'catalog',
  owner: 'Hiếu',
  routes: [
    {
      path: '/catalog/products',
      title: 'Sản phẩm',
      roles: ALL_ROLES,
      Component: ProductsPage,
      menu: { group: 'products', order: 10 },
    },
    {
      path: '/catalog/categories',
      title: 'Danh mục',
      roles: OWNER_ONLY,
      Component: CategoriesPage,
      menu: { group: 'products', order: 20 },
    },
    {
      path: '/catalog/customers',
      title: 'Khách hàng',
      roles: ALL_ROLES,
      Component: CustomersPage,
      menu: { group: 'customers', order: 10 },
    },
    {
      path: '/catalog/suppliers',
      title: 'Nhà cung cấp',
      roles: OWNER_ONLY,
      Component: SuppliersPage,
      menu: { group: 'inventory', order: 40 },
    },
    {
      path: '/catalog/stock-ledger',
      title: 'Lịch sử xuất nhập kho',
      roles: OWNER_ONLY,
      Component: StockLedgerPage,
      menu: { group: 'inventory', order: 50 },
    },
  ],
};
