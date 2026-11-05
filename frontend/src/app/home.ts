/** Trang chính theo vai trò. Người giữ: Anh. */
import type { Role } from '../shared/types/platform';

export const HOME_PATH_BY_ROLE: Readonly<Record<Role, string>> = {
  OWNER: '/reporting/dashboard',
  STAFF: '/sales/pos',
};

export function homePathFor(role: Role): string {
  return HOME_PATH_BY_ROLE[role];
}
