"""SQL giữ chỗ và hoàn lượt Hạn mức AI (AD-13).

Bảng (Flyway ở backend tạo, xem README):
    ai.ai_quota(store_id uuid, quota_date date, used integer, primary key (store_id, quota_date))

- Đếm theo (store_id, ngày theo giờ Việt Nam); làm mới lúc 0h giờ Việt Nam.
- Giữ chỗ TRƯỚC khi gọi LLM, một câu lệnh nguyên tử: bấm liên tục cũng không vượt 50.
- Chỉ hoàn lượt khi không trả được kết quả nào (AD-11 mục 9). Hoàn đúng ngày đã giữ chỗ,
  kể cả khi câu hỏi chạy qua 0h.
- Cả hai hàm phải chạy trong `db.store_transaction(store_id)` (RLS + set_config).
"""

import uuid
from dataclasses import dataclass
from datetime import date, datetime

import psycopg

from app.qa.periods import today_vn

DAILY_LIMIT = 50

RESERVE_SQL = """\
insert into ai.ai_quota as q (store_id, quota_date, used)
values (%(store_id)s, %(quota_date)s, 1)
on conflict (store_id, quota_date) do update
    set used = q.used + 1
    where q.used < %(limit)s
returning q.used
"""

REFUND_SQL = """\
update ai.ai_quota
   set used = used - 1
 where store_id = %(store_id)s
   and quota_date = %(quota_date)s
   and used > 0
"""


@dataclass(frozen=True, slots=True)
class QuotaReservation:
    store_id: uuid.UUID
    quota_date: date
    used: int
    limit: int = DAILY_LIMIT

    @property
    def remaining(self) -> int:
        return max(self.limit - self.used, 0)


def quota_day(now: datetime | None = None) -> date:
    """Ngày tính Hạn mức: ngày theo giờ Việt Nam."""
    return today_vn(now)


def reserve(
    conn: psycopg.Connection, store_id: uuid.UUID, quota_date: date, limit: int = DAILY_LIMIT
) -> QuotaReservation | None:
    """Giữ một lượt. Trả `None` khi đã hết hạn mức (→ 429 /errors/ai/quota-exceeded)."""
    row = conn.execute(RESERVE_SQL, {"store_id": store_id, "quota_date": quota_date, "limit": limit}).fetchone()
    if row is None:
        return None
    return QuotaReservation(store_id=store_id, quota_date=quota_date, used=int(row[0]), limit=limit)


def refund(conn: psycopg.Connection, reservation: QuotaReservation) -> bool:
    """Hoàn lượt đã giữ. Trả `True` nếu có dòng được cập nhật."""
    cursor = conn.execute(REFUND_SQL, {"store_id": reservation.store_id, "quota_date": reservation.quota_date})
    return cursor.rowcount == 1
