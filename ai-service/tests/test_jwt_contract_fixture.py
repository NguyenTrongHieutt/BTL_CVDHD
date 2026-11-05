"""Hợp đồng JWT Java ↔ Python (AD-9, AD-21).

Token trong `docs/contracts/jwt-fixture.json` do backend Java ký (JwtContractTest). Python phải đọc được
đúng claim bằng cùng secret. Đổi cách ký ở một phía mà không đổi phía kia thì test này hoặc JwtContractTest hỏng.
"""

import json
import uuid
from pathlib import Path

from app.auth import decode_token

FIXTURE = Path(__file__).resolve().parents[2] / "docs" / "contracts" / "jwt-fixture.json"


def test_python_accepts_token_signed_by_java() -> None:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    user = decode_token(fixture["token"], fixture["secret"])
    assert user.user_id == uuid.UUID(fixture["sub"])
    assert user.store_id == uuid.UUID(fixture["store_id"])
    assert user.role == fixture["role"]
