/**
 * Chặn route theo phiên đăng nhập và vai trò (AD-9). Người giữ: Anh.
 *
 * - `RequireAuth`: gọi `GET /api/v1/platform/me`; 401 thì chuyển về `/login` (nhớ trang đang mở).
 * - `RequireRole`: vai trò không nằm trong `roles` thì hiện trang 403. Chỉ để ẩn giao diện:
 *   backend vẫn kiểm quyền mọi endpoint (AD-16).
 */
import { Button, Result, Spin } from 'antd';
import type { ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router';
import { isApiError } from '../shared/api/client';
import { CurrentUserContext, useCurrentUser, useMe } from '../shared/api/session';
import { useDocumentTitle } from '../shared/components';
import { describeError } from '../shared/messages.vi';
import type { Role } from '../shared/types/platform';

export function RequireAuth({ children }: { children: ReactNode }) {
  const me = useMe();
  const location = useLocation();

  if (me.data) {
    return <CurrentUserContext value={me.data}>{children}</CurrentUserContext>;
  }
  if (me.isError) {
    if (isApiError(me.error) && me.error.status === 401) {
      return <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
    }
    const described = describeError(me.error);
    return (
      <div className="full-page-center">
        <Result
          status="error"
          title="Không tải được thông tin đăng nhập"
          subTitle={[described.message, described.hint].filter(Boolean).join(' ')}
          extra={
            <Button type="primary" onClick={() => void me.refetch()}>
              Thử lại
            </Button>
          }
        />
      </div>
    );
  }
  return (
    <div className="full-page-center" aria-busy="true">
      <Spin size="large" />
    </div>
  );
}

export interface RequireRoleProps {
  roles: readonly Role[];
  /** Tiêu đề màn hình, đặt cho tab trình duyệt. */
  title: string;
  children: ReactNode;
}

export function RequireRole({ roles, title, children }: RequireRoleProps) {
  const me = useCurrentUser();
  useDocumentTitle(title);
  if (!roles.includes(me.role)) {
    return (
      <Result
        status="403"
        title="Không có quyền"
        subTitle="Tài khoản của bạn không được mở màn hình này."
        extra={<Link to="/">Về trang chính</Link>}
      />
    );
  }
  return children;
}
