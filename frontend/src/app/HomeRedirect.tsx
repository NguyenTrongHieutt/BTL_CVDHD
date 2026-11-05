/** `/` → trang chính theo vai trò: Chủ cửa hàng → Trang tổng quan, Nhân viên → Bán hàng. Người giữ: Anh. */
import { Navigate } from 'react-router';
import { useCurrentUser } from '../shared/api/session';
import { homePathFor } from './home';

export function HomeRedirect() {
  const me = useCurrentUser();
  return <Navigate to={homePathFor(me.role)} replace />;
}
