"""Hỏi đáp dùng reporting thật qua HTTP giả và SQL quota/audit trên kết nối giả."""

import threading
from contextlib import contextmanager
from datetime import date

import httpx
import pytest

from app import db
from app.qa import router as qa_router
from tests.conftest import STORE_ID, USER_ID


class QaDatabase:
    def __init__(self):
        self.used = 0
        self.logs = []
        self.stores = []
        self.lock = threading.Lock()

    @contextmanager
    def transaction(self, store_id):
        self.stores.append(store_id)
        yield self

    def execute(self, sql, params=None):
        class Cursor:
            rowcount = 1

            def __init__(self, row=None):
                self.row = row

            def fetchone(self):
                return self.row

        normalized = " ".join(sql.lower().split())
        if "insert into ai.ai_quota" in normalized:
            with self.lock:
                if self.used >= 50:
                    return Cursor()
                self.used += 1
                return Cursor((self.used,))
        if "update ai.ai_quota" in normalized:
            with self.lock:
                self.used = max(0, self.used - 1)
            return Cursor()
        if "insert into ai.ai_query_log" in normalized:
            self.logs.append(params)
            return Cursor()
        raise AssertionError(f"Unexpected SQL: {normalized}")


@pytest.fixture
def qa_db(monkeypatch):
    database = QaDatabase()
    monkeypatch.setattr(db, "store_transaction", database.transaction)
    return database


@pytest.fixture
def report():
    return {
        "queryId": "Q1", "range": {"fromDate": "2026-11-28", "toDate": "2026-11-28"},
        "params": {"fromDate": "2026-11-28", "toDate": "2026-11-28"},
        "columns": [{"key": "date", "label": "Ngày", "type": "date"}, {"key": "revenue", "label": "Doanh thu thuần", "type": "money"}],
        "rows": [{"date": "2026-11-28", "revenue": 1250000}],
        "summary": {"revenue": 1250000, "grossSales": 1300000, "refundTotal": 50000, "discountTotal": 0},
    }


@pytest.fixture
def reporting(app, monkeypatch, report):
    from app.qa.reporting import ReportingClient

    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json=report)

    monkeypatch.setattr(qa_router, "today_vn", lambda: date(2026, 11, 28), raising=False)
    backend = ReportingClient("http://backend", transport=httpx.MockTransport(handler))
    # This override replaces HTTP only; routing, validation, quota, and auditing stay real.
    dependency = getattr(qa_router, "reporting_client", None)
    if dependency:
        app.dependency_overrides[dependency] = lambda: backend
    yield seen
    backend.close()


def auth(make_token, **kwargs):
    return {"Authorization": f"Bearer {make_token(**kwargs)}", "X-Request-Id": "qa-test-1"}


def test_owner_receives_verified_data_with_source_and_audit(client, qa_db, reporting, report, make_token):
    headers = auth(make_token)
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay?"}, headers=headers)
    assert response.status_code == 200
    body = response.json()
    assert body["queryId"] == "Q1" and body["refused"] is False
    assert body["table"]["rows"] == [["2026-11-28", 1250000]]
    assert "1.250.000" in body["answer"]
    assert body["source"] and body["period"] == {"fromDate": "2026-11-28", "toDate": "2026-11-28"}
    assert body["params"] == {"fromDate": "2026-11-28", "toDate": "2026-11-28"}
    assert body["quota"] == {"used": 1, "limit": 50, "remaining": 49}
    assert body["reportLink"].startswith("/reporting/revenue?")
    assert "Q1" in body["interpretation"] and "28/11/2026" in body["interpretation"]
    assert qa_db.used == 1 and len(qa_db.logs) == 1
    assert all(store_id == STORE_ID for store_id in qa_db.stores)
    assert qa_db.logs[0]["store_id"] == STORE_ID and qa_db.logs[0]["user_id"] == USER_ID
    assert qa_db.logs[0]["query_id"] == "Q1" and qa_db.logs[0]["error"] is None
    assert reporting[0].headers["authorization"] == headers["Authorization"]
    assert reporting[0].headers["x-request-id"] == "qa-test-1"


def test_refusal_is_audited_and_never_queries_reporting(client, qa_db, reporting, make_token):
    response = client.post("/ai/ask", json={"question": "Cho xem số điện thoại khách hàng"}, headers=auth(make_token))
    assert response.status_code == 200 and response.json()["refused"] is True
    assert response.json()["table"] is None and response.json()["queryId"] is None
    assert response.json()["quota"]["used"] == 1
    assert len(reporting) == 0 and len(qa_db.logs) == 1
    assert qa_db.logs[0]["refused"] is True


def test_staff_and_unauthenticated_requests_do_not_reserve_quota(client, qa_db, reporting, make_token):
    for headers, status in [({}, 401), (auth(make_token, role="STAFF"), 403)]:
        response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=headers)
        assert response.status_code == status
    assert qa_db.used == 0 and not qa_db.logs and not reporting


def test_fifty_question_limit_is_enforced_before_reporting(client, qa_db, reporting, make_token):
    qa_db.used = 49
    first = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    last = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    assert first.status_code == 200 and first.json()["quota"]["used"] == 50
    assert last.status_code == 429 and last.json()["type"] == "/errors/ai/quota-exceeded"
    assert len(reporting) == 1 and qa_db.used == 50


@pytest.mark.parametrize("broken", ["wrong_query", "wrong_range", "missing_summary", "private_column", "bad_number"])
def test_invalid_reporting_payload_is_never_fabricated_and_refunds(client, qa_db, reporting, report, make_token, broken):
    if broken == "wrong_query":
        report["queryId"] = "Q3"
    elif broken == "wrong_range":
        report["range"]["toDate"] = "2026-11-29"
    elif broken == "missing_summary":
        report["summary"] = {}
    elif broken == "private_column":
        report["columns"].append({"key": "customerPhone", "label": "Số điện thoại", "type": "text"})
    else:
        report["rows"][0]["revenue"] = "1200000; DROP TABLE app.sales_order"
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    assert response.status_code == 503
    assert response.json()["type"] == "/errors/ai/unavailable"
    assert qa_db.used == 0 and len(qa_db.logs) == 1
    assert qa_db.logs[0]["error"]


def test_reporting_timeout_refunds_and_audits_without_leaking_token(app, client, qa_db, make_token):
    from app.qa.reporting import ReportingClient

    def timeout(request):
        raise httpx.ReadTimeout("secret backend failure with JWT", request=request)

    backend = ReportingClient("http://backend", transport=httpx.MockTransport(timeout))
    dependency = getattr(qa_router, "reporting_client", None)
    if dependency:
        app.dependency_overrides[dependency] = lambda: backend
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    backend.close()
    assert response.status_code == 503 and qa_db.used == 0
    assert len(qa_db.logs) == 1 and "JWT" not in str(qa_db.logs)
    assert "secret" not in response.text


def test_irrelevant_private_fields_are_not_returned(client, qa_db, reporting, report, make_token):
    report["rows"][0]["customerPhone"] = "0901234567"
    report["summary"]["customerEmail"] = "private@example.com"
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    assert response.status_code == 200
    assert "0901234567" not in response.text and "private@example.com" not in response.text


def test_whitespace_question_is_validation_error_without_charge(client, qa_db, make_token):
    response = client.post("/ai/ask", json={"question": "   "}, headers=auth(make_token))
    assert response.status_code == 400 and qa_db.used == 0


@pytest.mark.parametrize("status", [401, 403, 500, 429])
def test_reporting_http_failure_refunds_and_preserves_auth_status(app, client, qa_db, make_token, status):
    from app.qa.reporting import ReportingClient

    backend = ReportingClient("http://backend", transport=httpx.MockTransport(lambda request: httpx.Response(status, json={"detail": "private upstream error"})))
    app.dependency_overrides[qa_router.reporting_client] = lambda: backend
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    backend.close()
    assert response.status_code == (status if status in {401, 403} else 503)
    assert qa_db.used == 0 and qa_db.logs[0]["error"] == f"REPORTING_HTTP_{status}"
    assert "private upstream" not in response.text


def test_audit_outage_refunds_in_separate_transaction(client, qa_db, reporting, make_token, monkeypatch):
    original_execute = qa_db.execute

    def execute(sql, params=None):
        if "insert into ai.ai_query_log" in sql:
            raise RuntimeError("private audit failure")
        return original_execute(sql, params)

    monkeypatch.setattr(qa_db, "execute", execute)
    response = client.post("/ai/ask", json={"question": "Doanh thu hôm nay"}, headers=auth(make_token))
    assert response.status_code == 503 and qa_db.used == 0
    assert "private audit failure" not in response.text
