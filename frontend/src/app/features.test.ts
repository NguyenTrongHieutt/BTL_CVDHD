import { describe, expect, it } from 'vitest';
import { appFeatures } from './features';
import { visibleMenu } from './menu';
import { homePathFor } from './home';

const allRoutes = appFeatures.flatMap((feature) => feature.routes.map((route) => ({ module: feature.module, route })));

describe('quy ước route (AD-20)', () => {
  it('route có tiền tố /<module>/; Trợ lý AI là /assistant, không dùng /ai/', () => {
    const hasModulePrefix = (module: string, path: string) =>
      module === 'assistant' ? path === '/assistant' || path.startsWith('/assistant/') : path.startsWith(`/${module}/`);
    const offenders = allRoutes
      .filter(({ module, route }) => !hasModulePrefix(module, route.path) || /^\/(ai|api)(\/|$)/.test(route.path))
      .map(({ route }) => route.path);
    expect(offenders).toEqual([]);
  });

  it('route công khai chỉ là /login và /platform/…', () => {
    const publicPaths = appFeatures.flatMap((feature) => (feature.publicRoutes ?? []).map((r) => r.path));
    expect(publicPaths).toContain('/login');
    for (const path of publicPaths) expect(path === '/login' || path.startsWith('/platform/')).toBe(true);
  });

  it('không có route trùng đường dẫn', () => {
    const paths = allRoutes.map(({ route }) => route.path);
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('trang chính của mỗi vai trò là route vai trò đó được mở', () => {
    for (const role of ['OWNER', 'STAFF'] as const) {
      const home = allRoutes.find(({ route }) => route.path === homePathFor(role));
      expect(home?.route.roles).toContain(role);
    }
  });
});

describe('menu theo vai trò (PRD 4.7)', () => {
  const pathsFor = (role: 'OWNER' | 'STAFF') =>
    visibleMenu(appFeatures, role).flatMap((group) => group.entries.map((entry) => entry.path));

  it('Nhân viên chỉ thấy bán hàng, Đơn hàng, trả hàng, Sản phẩm, Khách hàng, Tồn kho, thông tin cá nhân', () => {
    expect(pathsFor('STAFF').sort()).toEqual(
      [
        '/sales/pos',
        '/sales/orders',
        '/sales/returns',
        '/catalog/products',
        '/catalog/customers',
        '/inventory/stock',
        '/platform/profile',
      ].sort(),
    );
  });

  it('Chủ cửa hàng thấy mọi mục menu', () => {
    const menuRoutes = allRoutes.filter(({ route }) => route.menu).map(({ route }) => route.path);
    expect(pathsFor('OWNER').sort()).toEqual(menuRoutes.sort());
  });

  it('nhóm menu theo thứ tự PRD 7.4', () => {
    expect(visibleMenu(appFeatures, 'OWNER').map((group) => group.label)).toEqual([
      'Bán hàng',
      'Sản phẩm',
      'Khách hàng',
      'Kho',
      'Báo cáo',
      'Trợ lý AI',
      'Cài đặt',
    ]);
  });
});
