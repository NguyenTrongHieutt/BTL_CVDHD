"""Problem Details (RFC 9457, AD-19) và chuyển tiếp `X-Request-Id`."""

import re

import pytest
from fastapi.testclient import TestClient

PROBLEM_FIELDS = {"type", "title", "status", "detail", "instance", "requestId"}


def _auth(make_token) -> dict[str, str]:
    return {"Authorization": f"Bearer {make_token()}"}


def test_validation_error_is_400_with_errors(client, make_token):
    response = client.get("/ai/search", params={"limit": 0}, headers=_auth(make_token))
    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert PROBLEM_FIELDS | {"errors"} == set(body)
    assert body["type"] == "/errors/common/validation"
    assert body["instance"] == "/ai/search"
    fields = {e["field"] for e in body["errors"]}
    assert fields == {"q", "limit"}
    assert all({"field", "code", "message"} == set(e) for e in body["errors"])


def test_body_validation_error_names_body_field(client, make_token):
    response = client.post("/ai/ask", json={"question": ""}, headers=_auth(make_token))
    assert response.status_code == 400
    assert [e["field"] for e in response.json()["errors"]] == ["question"]


def test_not_implemented_stubs_return_501_problem(client, make_token):
    search = client.get("/ai/search", params={"q": "nước ngọt"}, headers=_auth(make_token))
    ask = client.post("/ai/ask", json={"question": "Doanh thu hôm nay?"}, headers=_auth(make_token))
    for response in (search, ask):
        assert response.status_code == 501
        body = response.json()
        assert body["type"] == "/errors/ai/not-implemented"
        assert PROBLEM_FIELDS == set(body)


def test_unknown_route_is_404_problem(client):
    response = client.get("/ai/khong-co")
    assert response.status_code == 404
    assert response.json()["type"] == "/errors/common/not-found"


def test_wrong_method_is_405_problem(client):
    response = client.get("/ai/ask")
    assert response.status_code == 405
    assert response.json()["type"] == "/errors/common/method-not-allowed"


def test_request_id_is_propagated_to_body_and_header(client):
    response = client.get("/ai/search", headers={"X-Request-Id": "req-abc.123"})
    assert response.status_code == 401
    assert response.json()["requestId"] == "req-abc.123"
    assert response.headers["x-request-id"] == "req-abc.123"


def test_request_id_is_generated_when_missing(client):
    first = client.get("/ai/khong-co")
    second = client.get("/ai/khong-co")
    rid = first.json()["requestId"]
    assert re.fullmatch(r"[0-9a-f]{32}", rid)
    assert first.headers["x-request-id"] == rid
    assert second.json()["requestId"] != rid


def test_request_id_is_set_on_success_responses(client):
    response = client.get("/ai/health", headers={"X-Request-Id": "abc"})
    assert response.headers["x-request-id"] == "abc"


@pytest.mark.parametrize("bad", ["x" * 200, "có dấu", "a b"])
def test_unsafe_request_id_is_replaced(client, bad):
    response = client.get("/ai/khong-co", headers={"X-Request-Id": bad.encode("utf-8")})
    assert response.json()["requestId"] != bad
    assert re.fullmatch(r"[0-9a-f]{32}", response.json()["requestId"])


def test_unhandled_exception_is_500_problem_without_leaking(app):
    @app.get("/ai/_boom")
    def boom() -> None:
        raise RuntimeError("bí mật nội bộ: password=hunter2")

    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.get("/ai/_boom", headers={"X-Request-Id": "boom-1"})
    assert response.status_code == 500
    body = response.json()
    assert body["type"] == "/errors/common/internal"
    assert body["requestId"] == "boom-1"
    assert response.headers["x-request-id"] == "boom-1"
    assert "hunter2" not in response.text
