import type { ThemeConfig } from 'antd';

/** Shared retail workspace tokens. Keep page components free of one-off color values. */
export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#0f766e',
    colorInfo: '#0f766e',
    colorSuccess: '#15803d',
    colorWarning: '#b45309',
    colorError: '#b42318',
    colorLink: '#0f766e',
    colorText: '#172b3a',
    colorTextSecondary: '#526575',
    colorBgBase: '#ffffff',
    colorBgLayout: '#f5f8fa',
    colorBorder: '#d9e3e8',
    colorBorderSecondary: '#e7edf0',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif',
    fontSize: 15,
    borderRadius: 10,
    controlHeight: 40,
  },
  components: {
    Button: { primaryShadow: 'none', borderRadius: 9 },
    Card: { borderRadiusLG: 14 },
    Table: { headerBg: '#f5f8fa', headerColor: '#526575' },
    Layout: { headerBg: '#ffffff', bodyBg: '#f5f8fa' },
  },
};
