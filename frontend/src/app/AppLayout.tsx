/**
 * Khung màn hình sau khi đăng nhập: menu bên trái lọc theo vai trò, thanh trên có tên Cửa hàng,
 * tên người dùng và nút Đăng xuất. Người giữ: Anh (CB-05).
 *
 * Dưới mốc `lg` (992px) menu ẩn đi, mở bằng nút trên thanh trên và nổi đè lên nội dung, để màn của
 * Chủ cửa hàng dùng được từ chiều rộng 360px (Consistency Conventions, dòng "Giao diện").
 */
import { CloseOutlined, LogoutOutlined, MenuOutlined, ShopOutlined } from '@ant-design/icons';
import { App, Button, Grid, Layout, Menu, Tag, Typography, theme } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { useCurrentUser, useLogout } from '../shared/api/session';
import { APP_TITLE } from '../shared/components';
import { describeError } from '../shared/messages.vi';
import type { FeatureDefinition } from '../shared/routing';
import { ROLE_LABELS } from '../shared/types/platform';
import { buildMenuItems, selectedMenuKey } from './menu';

export interface AppLayoutProps {
  features: readonly FeatureDefinition[];
}

export function AppLayout({ features }: AppLayoutProps) {
  const me = useCurrentUser();
  const logout = useLogout();
  const location = useLocation();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const { token } = theme.useToken();
  const screens = Grid.useBreakpoint();
  const [narrow, setNarrow] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const menuToggleRef = useRef<HTMLButtonElement>(null);
  const menuCloseRef = useRef<HTMLButtonElement>(null);
  const menuOpen = narrow && !collapsed;

  const items = useMemo(() => buildMenuItems(features, me.role), [features, me.role]);
  const selectedKey = selectedMenuKey(features, location.pathname);

  useEffect(() => {
    if (!menuOpen) return;
    const menu = document.querySelector<HTMLElement>('#app-navigation .ant-menu');
    const focusDialog = () => {
      if (document.activeElement !== document.body && document.activeElement !== menuToggleRef.current) return;
      menuCloseRef.current?.focus();
    };
    const focusFrame = requestAnimationFrame(focusDialog);
    const focusTimer = window.setTimeout(focusDialog, 250);
    const keepFocusInMenu = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setCollapsed(true);
        requestAnimationFrame(() => menuToggleRef.current?.focus());
      }
      if (event.key === 'Tab') {
        const close = menuCloseRef.current;
        if (!menu || !close) return;
        event.preventDefault();
        if (document.activeElement === close || !document.getElementById('app-navigation')?.contains(document.activeElement)) {
          menu.focus();
        } else {
          close.focus();
        }
      }
    };
    document.addEventListener('keydown', keepFocusInMenu);
    return () => {
      cancelAnimationFrame(focusFrame);
      clearTimeout(focusTimer);
      document.removeEventListener('keydown', keepFocusInMenu);
    };
  }, [menuOpen]);

  const onLogout = () => {
    logout.mutate(undefined, {
      onError: (error) => {
        const described = describeError(error);
        void message.error(`Đăng xuất chưa được: ${described.message}`);
      },
    });
  };

  return (
    <>
    <a className="skip-link" href="#main-content">Chuyển đến nội dung chính</a>
    <Layout style={{ minHeight: '100dvh' }}>
      <Layout.Sider
        id="app-navigation"
        role={menuOpen ? 'dialog' : undefined}
        aria-modal={menuOpen ? true : undefined}
        aria-label={menuOpen ? 'Điều hướng chính' : undefined}
        className="app-chrome app-sider"
        theme="dark"
        width={240}
        breakpoint="lg"
        collapsedWidth={0}
        collapsed={collapsed}
        trigger={null}
        onBreakpoint={(broken) => {
          setNarrow(broken);
          setCollapsed(broken);
        }}
        style={
          narrow
            ? {
                position: 'fixed',
                insetBlock: 0,
                insetInlineStart: 0,
                zIndex: 1000,
                overflowY: 'auto',
                boxShadow: token.boxShadowSecondary,
              }
            : {
                position: 'sticky',
                top: 0,
                height: '100vh',
                overflowY: 'auto',
                borderInlineEnd: `1px solid ${token.colorBorderSecondary}`,
              }
        }
      >
        <div className={menuOpen ? 'app-brand app-brand-mobile-open' : 'app-brand'}>
          <span className="app-brand-mark" aria-hidden="true"><ShopOutlined /></span>
          <span className="app-brand-name">{APP_TITLE}<span className="app-brand-caption">Không gian cửa hàng</span></span>
          {menuOpen ? (
            <Button
              ref={menuCloseRef}
              className="app-menu-close"
              type="text"
              icon={<CloseOutlined />}
              aria-label="Đóng menu"
              onClick={() => {
                setCollapsed(true);
                requestAnimationFrame(() => menuToggleRef.current?.focus());
              }}
            />
          ) : null}
        </div>
        <Menu
          mode="inline"
          theme="dark"
          aria-label="Điều hướng chính"
          items={items}
          selectedKeys={selectedKey ? [selectedKey] : []}
          style={{ borderInlineEnd: 'none' }}
          onClick={({ key }) => {
            void navigate(key);
            if (narrow) {
              setCollapsed(true);
              requestAnimationFrame(() => document.getElementById('main-content')?.focus());
            }
          }}
        />
      </Layout.Sider>
      {menuOpen ? (
        <button type="button" className="app-sider-mask app-chrome" aria-label="Đóng menu" aria-hidden="true" tabIndex={-1}
          onClick={() => {
            setCollapsed(true);
            requestAnimationFrame(() => menuToggleRef.current?.focus());
          }} />
      ) : null}
      <Layout inert={menuOpen}>
        <Layout.Header className="app-chrome app-header" style={{ background: token.colorBgContainer }}>
          {narrow ? (
            <Button
              ref={menuToggleRef}
              type="text"
              icon={<MenuOutlined />}
              aria-label={collapsed ? 'Mở menu' : 'Đóng menu'}
              aria-expanded={!collapsed}
              aria-controls="app-navigation"
              onClick={() => setCollapsed((value) => !value)}
            />
          ) : null}
          <Typography.Text strong className="app-header-store">
            {me.storeName}
          </Typography.Text>
          <div className="app-header-user">
            {screens.sm ? <Typography.Text ellipsis>{me.displayName}</Typography.Text> : null}
            <Tag color={me.role === 'OWNER' ? 'gold' : 'blue'}>{ROLE_LABELS[me.role]}</Tag>
            <Button icon={<LogoutOutlined />} loading={logout.isPending} onClick={onLogout} aria-label="Đăng xuất">
              {screens.md ? 'Đăng xuất' : null}
            </Button>
          </div>
        </Layout.Header>
        <Layout.Content className="app-content" id="main-content" tabIndex={-1}>
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
    </>
  );
}
