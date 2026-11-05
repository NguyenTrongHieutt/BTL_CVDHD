/**
 * Điểm vào của SPA. Người giữ: Anh (CB-05).
 * Nối TanStack Query, Ant Design (`ConfigProvider` locale `vi_VN`) và router.
 */
import { QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider } from 'antd';
import viVN from 'antd/locale/vi_VN';
import dayjs from 'dayjs';
import 'dayjs/locale/vi';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router/dom';
import { createAppQueryClient } from './app/queryClient';
import { createAppRouter } from './app/router';
import { installSessionHandlers } from './app/session';
import './app/global.css';

dayjs.locale('vi');

const queryClient = createAppQueryClient();
const router = createAppRouter();
installSessionHandlers(router, queryClient);

const rootElement = document.getElementById('root');
if (!rootElement) throw new Error('Không tìm thấy #root trong index.html');

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={viVN}>
        <AntdApp>
          <RouterProvider router={router} />
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>
  </StrictMode>,
);
