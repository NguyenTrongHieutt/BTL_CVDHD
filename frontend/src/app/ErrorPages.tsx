/** Trang 404 và trang lỗi khi render route hỏng. Người giữ: Anh. */
import { Button, Result } from 'antd';
import { Link, isRouteErrorResponse, useRouteError } from 'react-router';
import { useDocumentTitle } from '../shared/components';

export function NotFoundPage() {
  useDocumentTitle('Không tìm thấy trang');
  return (
    <Result
      status="404"
      title="Không tìm thấy trang"
      subTitle="Đường dẫn không đúng hoặc màn hình đã được chuyển chỗ."
      extra={<Link to="/">Về trang chính</Link>}
    />
  );
}

export function RouteErrorPage() {
  const error = useRouteError();
  if (import.meta.env.DEV) console.error(error);
  const detail = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : undefined;
  return (
    <div className="full-page-center">
      <Result
        status="error"
        title="Màn hình gặp lỗi"
        subTitle={detail ?? 'Tải lại trang để thử lại. Nếu vẫn lỗi, báo cho nhóm hỗ trợ.'}
        extra={
          <Button type="primary" onClick={() => window.location.reload()}>
            Tải lại trang
          </Button>
        }
      />
    </div>
  );
}
