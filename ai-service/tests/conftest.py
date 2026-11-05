"""Cấu hình chung cho pytest. Không cần mạng, không tải model, không cần database."""

import os
import time
import uuid
from collections.abc import Callable, Iterator
from typing import Any

import jwt
import pytest

# Đặt env trước khi import app.main (module này tạo app ngay khi import).
TEST_JWT_SECRET = "pytest-only-secret-not-used-anywhere-else-0123456789"
os.environ["JWT_SECRET"] = TEST_JWT_SECRET
os.environ["SYNC_ENABLED"] = "false"
os.environ.pop("GEMINI_API_KEY", None)

from fastapi.testclient import TestClient  # noqa: E402

from app import embedding  # noqa: E402
from app.main import create_app  # noqa: E402
from app.settings import Settings  # noqa: E402

STORE_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")
USER_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")


@pytest.fixture(autouse=True)
def _fresh_embedding_state() -> Iterator[None]:
    embedding._reset_for_tests()
    yield
    embedding._reset_for_tests()


@pytest.fixture
def settings() -> Settings:
    return Settings(jwt_secret=TEST_JWT_SECRET)  # type: ignore[arg-type]


@pytest.fixture
def app(settings: Settings):
    return create_app(settings)


@pytest.fixture
def client(app) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


def claims(**overrides: Any) -> dict[str, Any]:
    now = int(time.time())
    base: dict[str, Any] = {
        "sub": str(USER_ID),
        "store_id": str(STORE_ID),
        "role": "OWNER",
        "iat": now,
        "exp": now + 8 * 3600,
        "jti": str(uuid.uuid4()),
    }
    base.update(overrides)
    return {k: v for k, v in base.items() if v is not None}


@pytest.fixture
def make_token() -> Callable[..., str]:
    def _make(secret: str = TEST_JWT_SECRET, algorithm: str = "HS256", **overrides: Any) -> str:
        return jwt.encode(claims(**overrides), secret, algorithm=algorithm)

    return _make
