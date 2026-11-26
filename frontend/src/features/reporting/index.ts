/**
 * features/reporting — trang tổng quan và báo cáo. Chỉ Chủ cửa hàng (PRD 4.7).
 * Người giữ: Lân (AD-20). Route: `/reporting/…`.
 */
import { lazy } from 'react';
import { OWNER_ONLY } from '../../shared/routing';
import type { FeatureDefinition } from '../../shared/routing';

export const reportingFeature: FeatureDefinition = {
  module: 'reporting',
  owner: 'Lân',
  routes: [
    {
      path: '/reporting/dashboard',
      title: 'Trang tổng quan',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/DashboardPage').then(({ DashboardPage }) => ({ default: DashboardPage }))),
      menu: { group: 'reports', order: 10, label: 'Tổng quan' },
    },
    {
      path: '/reporting/revenue',
      title: 'Báo cáo doanh thu',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/RevenueReportPage').then(({ RevenueReportPage }) => ({ default: RevenueReportPage }))),
      menu: { group: 'reports', order: 20, label: 'Doanh thu' },
    },
    {
      path: '/reporting/profit',
      title: 'Báo cáo lợi nhuận',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/ProfitReportPage').then(({ ProfitReportPage }) => ({ default: ProfitReportPage }))),
      menu: { group: 'reports', order: 30, label: 'Lợi nhuận' },
    },
    {
      path: '/reporting/products',
      title: 'Sản phẩm bán chạy, bán chậm',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/ProductRankingPage').then(({ ProductRankingPage }) => ({ default: ProductRankingPage }))),
      menu: { group: 'reports', order: 40, label: 'Bán chạy, bán chậm' },
    },
    {
      path: '/reporting/stock',
      title: 'Báo cáo Tồn kho',
      roles: OWNER_ONLY,
      Component: lazy(() => import('./pages/StockReportPage').then(({ StockReportPage }) => ({ default: StockReportPage }))),
      menu: { group: 'reports', order: 50, label: 'Tồn kho' },
    },
  ],
};
