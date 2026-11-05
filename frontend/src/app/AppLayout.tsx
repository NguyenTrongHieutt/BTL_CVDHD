/**
 * Khung màn hình sau khi đăng nhập: menu bên trái lọc theo vai trò, thanh trên có tên Cửa hàng,
 * tên người dùng và nút Đăng xuất. Người giữ: Anh (CB-05).
 *
 * Dưới mốc `lg` (992px) menu ẩn đi, mở bằng nút trên thanh trên và nổi đè lên nội dung, để màn của
 * Chủ cửa hàng dùng được từ chiều rộng 360px (Consistency Conventions, dòng "Giao diện").
 */
import { LogoutOutlined, MenuOutlined } from '@ant-design/icons';
import { App, Button, Grid, Layout, Menu, Tag, Typography, theme } from 'antd';
import { useMemo, useState } from 'react';
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

  const items = useMemo(() => buildMenuItems(features, me.role), [features, me.role]);
  const selectedKey = selectedMenuKey(features, location.pathname);

  const onLogout = () => {
    logout.mutate(undefined, {
      onError: (error) => {
        const described = describeError(error);
        void message.error(`Đăng xuất chưa được: ${described.message}`);
      },
    });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Layout.Sider
        className="app-chrome"
        theme="light"
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
        <div className="app-brand">{APP_TITLE}</div>
        <Menu
          mode="inline"
          items={items}
          selectedKeys={selectedKey ? [selectedKey] : []}
          style={{ borderInlineEnd: 'none' }}
          onClick={({ key }) => {
            void navigate(key);
            if (narrow) setCollapsed(true);
          }}
        />
      </Layout.Sider>
      {narrow && !collapsed ? <div className="app-sider-mask app-chrome" onClick={() => setCollapsed(true)} /> : null}
      <Layout>
        <Layout.Header className="app-chrome app-header" style={{ background: token.colorBgContainer }}>
          {narrow ? (
            <Button
              type="text"
              icon={<MenuOutlined />}
              aria-label={collapsed ? 'Mở menu' : 'Đóng menu'}
              onClick={() => setCollapsed((value) => !value)}
            />
          ) : null}
          <Typography.Text strong ellipsis className="app-header-store">
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
        <Layout.Content className="app-content">
          <Outlet />
        </Layout.Content>
      </Layout>
    </Layout>
  );
}
