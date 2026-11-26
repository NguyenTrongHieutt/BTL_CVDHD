/**
 * Router của ứng dụng (react-router 8, data router). Người giữ: Anh.
 *
 * - Route công khai của `platform` (`/login`, `/platform/register`) nằm ngoài vùng đăng nhập.
 * - Mọi route khác nằm trong `RequireAuth` + `AppLayout`; mỗi route bọc `RequireRole` theo `roles`.
 * - `/` chuyển tới trang chính theo vai trò.
 */
import { Spin } from 'antd';
import { lazy, Suspense } from 'react';
import { createBrowserRouter } from 'react-router';
import type { DataRouter, RouteObject } from 'react-router';
import type { FeatureDefinition } from '../shared/routing';
import { NotFoundPage, RouteErrorPage } from './ErrorPages';
import { HomeRedirect } from './HomeRedirect';
import { RequireAuth, RequireRole } from './RequireAuth';
import { appFeatures } from './features';

const loadingPage = (
  <div className="route-loading" role="status">
    <Spin />
    <span>Đang tải màn hình…</span>
  </div>
);

export function buildAppRoutes(features: readonly FeatureDefinition[]): RouteObject[] {
  const AuthenticatedLayout = lazy(() => import('./AppLayout').then(({ AppLayout }) => ({ default: AppLayout })));
  const publicRoutes: RouteObject[] = features
    .flatMap((feature) => feature.publicRoutes ?? [])
    .map(({ path, Component: Page }) => ({
      path,
      element: <Suspense fallback={loadingPage}><Page /></Suspense>,
      errorElement: <RouteErrorPage />,
    }));

  const protectedRoutes: RouteObject[] = features
    .flatMap((feature) => feature.routes)
    .map((route) => {
      const Page = route.Component;
      return {
        path: route.path,
        element: (
          <RequireRole roles={route.roles} title={route.title}>
            <Suspense fallback={loadingPage}><Page /></Suspense>
          </RequireRole>
        ),
      };
    });

  return [
    ...publicRoutes,
    {
      element: (
        <RequireAuth>
          <Suspense fallback={loadingPage}><AuthenticatedLayout features={features} /></Suspense>
        </RequireAuth>
      ),
      errorElement: <RouteErrorPage />,
      children: [{ index: true, element: <HomeRedirect /> }, ...protectedRoutes, { path: '*', element: <NotFoundPage /> }],
    },
  ];
}

export function createAppRouter(features: readonly FeatureDefinition[] = appFeatures): DataRouter {
  return createBrowserRouter(buildAppRoutes(features));
}
