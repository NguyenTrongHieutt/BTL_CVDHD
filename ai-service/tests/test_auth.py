"""Hợp đồng JWT với backend (AD-9): HS256, cookie `access_token` hoặc `Authorization: Bearer`."""

import base64
import hashlib
import hmac
import json
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app.auth import CurrentUserDep, OwnerDep, decode_token
from tests.conftest import STORE_ID, TEST_JWT_SECRET, USER_ID, claims


@pytest.fixture
def probe(app) -> TestClient:
    @app.get("/ai/_probe/me")
    def me(user: CurrentUserDep) -> dict:
        return {"userId": str(user.user_id), "storeId": str(user.store_id), "role": user.role, "jti": user.jti}

    @app.get("/ai/_probe/owner")
    def owner(user: OwnerDep) -> dict:
        return {"ok": True}

    return TestClient(app)


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _hand_signed_token(payload: dict, secret: str = TEST_JWT_SECRET) -> str:
    """Ký tay như jjwt phía Java (header chỉ có alg), không đi qua PyJWT."""
    header = _b64url(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
    body = _b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{header}.{body}".encode("ascii")
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{header}.{body}.{_b64url(signature)}"


def _assert_problem(response, status: int, type_: str) -> dict:
    assert response.status_code == status
    assert response.headers["content-type"].startswith("application/problem+json")
    body = response.json()
    assert body["type"] == type_
    assert body["status"] == status
    assert body["instance"].startswith("/ai/")
    assert body["requestId"]
    return body


def test_valid_token_in_cookie_is_accepted_with_claims(probe, make_token):
    jti = str(uuid.uuid4())
    probe.cookies.set("access_token", make_token(jti=jti, role="STAFF"))
    response = probe.get("/ai/_probe/me")
    assert response.status_code == 200
    assert response.json() == {"userId": str(USER_ID), "storeId": str(STORE_ID), "role": "STAFF", "jti": jti}


def test_valid_token_in_bearer_header_is_accepted(probe, make_token):
    response = probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {make_token()}"})
    assert response.status_code == 200
    assert response.json()["role"] == "OWNER"


def test_hand_signed_token_like_java_is_accepted():
    now = int(time.time())
    token = _hand_signed_token(
        {"sub": str(USER_ID), "store_id": str(STORE_ID), "role": "OWNER", "iat": now, "exp": now + 60, "jti": "j-1"}
    )
    user = decode_token(token, TEST_JWT_SECRET)
    assert (user.user_id, user.store_id, user.role, user.jti) == (USER_ID, STORE_ID, "OWNER", "j-1")
    assert user.token == token
    assert token not in repr(user)


def test_missing_token_gives_401(probe):
    body = _assert_problem(probe.get("/ai/_probe/me"), 401, "/errors/common/unauthorized")
    assert body["detail"] == "Thiếu token đăng nhập"


def test_wrong_secret_gives_401(probe, make_token):
    token = make_token(secret="another-secret-that-is-long-enough-32b")
    _assert_problem(probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {token}"}), 401, "/errors/common/unauthorized")


def test_expired_token_gives_401(probe, make_token):
    now = int(time.time())
    token = make_token(iat=now - 9 * 3600, exp=now - 3600)
    response = probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {token}"})
    body = _assert_problem(response, 401, "/errors/common/unauthorized")
    assert "hết hạn" in body["detail"]
    assert response.headers["www-authenticate"].startswith("Bearer")


@pytest.mark.parametrize("missing", ["sub", "store_id", "role", "iat", "exp", "jti"])
def test_token_missing_required_claim_gives_401(probe, make_token, missing):
    token = make_token(**{missing: None})
    _assert_problem(probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {token}"}), 401, "/errors/common/unauthorized")


@pytest.mark.parametrize(
    "overrides",
    [{"role": "ADMIN"}, {"store_id": "not-a-uuid"}, {"sub": "42"}, {"jti": ""}],
)
def test_token_with_bad_claim_values_gives_401(probe, make_token, overrides):
    token = make_token(**overrides)
    _assert_problem(probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {token}"}), 401, "/errors/common/unauthorized")


@pytest.mark.filterwarnings("ignore:The HMAC key")
def test_other_algorithms_are_rejected(probe, make_token):
    hs512 = make_token(algorithm="HS512")
    header = _b64url(json.dumps({"alg": "none"}).encode())
    unsigned = f"{header}.{_b64url(json.dumps(claims()).encode())}."
    for token in (hs512, unsigned, "garbage"):
        _assert_problem(probe.get("/ai/_probe/me", headers={"Authorization": f"Bearer {token}"}), 401, "/errors/common/unauthorized")


def test_require_owner_rejects_staff_with_403(probe, make_token):
    staff = probe.get("/ai/_probe/owner", headers={"Authorization": f"Bearer {make_token(role='STAFF')}"})
    _assert_problem(staff, 403, "/errors/common/forbidden")
    owner = probe.get("/ai/_probe/owner", headers={"Authorization": f"Bearer {make_token(role='OWNER')}"})
    assert owner.status_code == 200


def test_ask_checks_role_before_anything_else(client, make_token):
    response = client.post(
        "/ai/ask", json={"question": "Doanh thu hôm nay?"}, headers={"Authorization": f"Bearer {make_token(role='STAFF')}"}
    )
    _assert_problem(response, 403, "/errors/common/forbidden")


def test_short_jwt_secret_is_rejected_without_echoing_it():
    from pydantic import ValidationError

    from app.settings import Settings

    with pytest.raises(ValidationError) as excinfo:
        Settings(jwt_secret="too-short-secret-value")  # type: ignore[arg-type]
    assert "too-short-secret-value" not in str(excinfo.value)
