"""Truy cập DB (AD-5), Hạn mức (AD-13) và change-feed (AD-12) với kết nối/HTTP giả."""

import uuid
from datetime import UTC, date, datetime

import httpx
import pytest

from app import db, quota
from app.quota.repository import REFUND_SQL, RESERVE_SQL
from app.sync import feed

STORE = uuid.UUID("11111111-1111-4111-8111-111111111111")
OTHER_STORE = uuid.UUID("33333333-3333-4333-8333-333333333333")


class FakeCursor:
    def __init__(self, row=None, rowcount=1):
        self._row = row
        self.rowcount = rowcount

    def fetchone(self):
        return self._row


class FakeConnection:
    def __init__(self, row=None, rowcount=1):
        self.executed: list[tuple[str, object]] = []
        self.events: list[str] = []
        self._row, self._rowcount = row, rowcount

    def execute(self, sql, params=None):
        self.executed.append((sql, params))
        return FakeCursor(self._row, self._rowcount)

    def transaction(self):
        conn = self

        class _Tx:
            def __enter__(self):
                conn.events.append("begin")

            def __exit__(self, exc_type, *exc):
                conn.events.append("rollback" if exc_type else "commit")
                return False

        return _Tx()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.events.append("close")
        return False


def test_store_transaction_sets_store_context_first(monkeypatch):
    conn = FakeConnection()
    monkeypatch.setattr(db, "connect", lambda: conn)
    with db.store_transaction(STORE) as tx:
        tx.execute("select 1")
    assert conn.executed[0] == ("select set_config('app.store_id', %s, true)", (str(STORE),))
    assert conn.executed[1][0] == "select 1"
    assert conn.events == ["begin", "commit", "close"]


def test_store_transaction_rolls_back_on_error(monkeypatch):
    conn = FakeConnection()
    monkeypatch.setattr(db, "connect", lambda: conn)
    with pytest.raises(RuntimeError), db.store_transaction(STORE):
        raise RuntimeError("x")
    assert conn.events == ["begin", "rollback", "close"]


def test_store_transaction_requires_uuid():
    with pytest.raises(TypeError), db.store_transaction(str(STORE)):  # type: ignore[arg-type]
        pass


def test_quota_day_is_vietnam_date():
    assert quota.quota_day(datetime(2026, 11, 5, 17, 0, tzinfo=UTC)) == date(2026, 11, 6)


def test_quota_reserve_sql_is_atomic_conditional_upsert():
    normalized = " ".join(RESERVE_SQL.split())
    assert "on conflict (store_id, quota_date) do update set used = q.used + 1 where q.used < %(limit)s" in normalized
    assert normalized.endswith("returning q.used")
    assert "used > 0" in REFUND_SQL


def test_quota_reserve_returns_used_or_none():
    conn = FakeConnection(row=(3,))
    reservation = quota.reserve(conn, STORE, date(2026, 11, 5))  # type: ignore[arg-type]
    assert reservation is not None and reservation.used == 3 and reservation.remaining == 47
    assert conn.executed[0][1] == {"store_id": STORE, "quota_date": date(2026, 11, 5), "limit": 50}
    assert quota.reserve(FakeConnection(row=None), STORE, date(2026, 11, 5)) is None  # type: ignore[arg-type]


def test_quota_refund_uses_reserved_day():
    reservation = quota.QuotaReservation(store_id=STORE, quota_date=date(2026, 11, 5), used=4)
    conn = FakeConnection(rowcount=1)
    assert quota.refund(conn, reservation) is True  # type: ignore[arg-type]
    assert conn.executed[0][1] == {"store_id": STORE, "quota_date": date(2026, 11, 5)}


def test_fetch_changes_sends_service_key_and_cursor():
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["params"] = dict(request.url.params)
        seen["key"] = request.headers.get("x-service-key")
        return httpx.Response(
            200,
            json={
                "items": [
                    {
                        "storeId": str(STORE),
                        "productId": str(uuid.UUID(int=1)),
                        "searchText": "Bia Tiger lon 330ml · Đồ uống",
                        "status": "ACTIVE",
                        "changedAt": "2026-11-05T03:00:00Z",
                    }
                ],
                "nextCursor": "opaque-2",
            },
        )

    with httpx.Client(base_url="http://backend", transport=httpx.MockTransport(handler)) as client:
        page = feed.fetch_changes(client, "test-key", "opaque-1")
    assert seen == {
        "path": "/internal/catalog/product-changes",
        "params": {"after": "opaque-1", "limit": "500"},
        "key": "test-key",
    }
    assert page.nextCursor == "opaque-2"
    assert page.items[0].status == "ACTIVE"


def test_group_by_store_keeps_latest_change_per_product():
    def change(store, product, status, hour):
        return feed.ProductChange(
            storeId=store,
            productId=uuid.UUID(int=product),
            searchText=f"p{product}",
            status=status,
            changedAt=datetime(2026, 11, 5, hour, tzinfo=UTC),
        )

    grouped = feed.group_by_store(
        [change(STORE, 1, "ACTIVE", 1), change(STORE, 1, "INACTIVE", 2), change(OTHER_STORE, 2, "ACTIVE", 1)]
    )
    assert set(grouped) == {STORE, OTHER_STORE}
    assert [c.status for c in grouped[STORE]] == ["INACTIVE"]
    assert len(grouped[OTHER_STORE]) == 1


def test_reporting_client_forwards_user_token_and_request_id():
    from app.qa.reporting import ReportingClient
    from app.qa.tools import QueryId

    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(
            path=request.url.path,
            params=dict(request.url.params),
            auth=request.headers.get("authorization"),
            rid=request.headers.get("x-request-id"),
        )
        return httpx.Response(200, json={"range": {"fromDate": "2026-11-05", "toDate": "2026-11-05"}, "value": 1})

    client = ReportingClient("http://backend", transport=httpx.MockTransport(handler))
    body = client.query(QueryId.Q1, {"fromDate": "2026-11-05", "toDate": "2026-11-05"}, token="tok", request_id="r-1")
    assert body["value"] == 1
    assert seen == {
        "path": "/api/v1/reporting/q/Q1",
        "params": {"fromDate": "2026-11-05", "toDate": "2026-11-05"},
        "auth": "Bearer tok",
        "rid": "r-1",
    }
    missing_range = ReportingClient("http://backend", transport=httpx.MockTransport(lambda r: httpx.Response(200, json={})))
    with pytest.raises(ValueError, match="range"):
        missing_range.query(QueryId.Q7, {}, token="tok")