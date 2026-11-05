"""Xác thực JWT dùng chung với backend (AD-9).

- HS256, ký bằng `JWT_SECRET` (chuỗi UTF-8, dùng nguyên văn).
- Token lấy từ cookie `access_token`; nếu không có thì từ `Authorization: Bearer`.
- Claim bắt buộc: `sub` (uuid), `store_id` (uuid), `role` (OWNER | STAFF), `iat`, `exp`, `jti`.
- Token gốc được giữ lại để chuyển nguyên sang `reporting` qua header `Authorization: Bearer`.
"""

import uuid
from dataclasses import dataclass, field
from typing import Annotated, Literal

import jwt
from fastapi import Depends, Request

from app.problems import TYPE_FORBIDDEN, TYPE_UNAUTHENTICATED, ProblemException

ACCESS_TOKEN_COOKIE = "access_token"
JWT_ALGORITHM = "HS256"
REQUIRED_CLAIMS = ("sub", "store_id", "role", "iat", "exp", "jti")
ROLES = ("OWNER", "STAFF")
# Backend và ai-service chạy cùng máy; chỉ chừa vài giây cho lệch đồng hồ.
LEEWAY_SECONDS = 5

Role = Literal["OWNER", "STAFF"]


@dataclass(frozen=True, slots=True)
class CurrentUser:
    user_id: uuid.UUID
    store_id: uuid.UUID
    role: Role
    jti: str
    token: str = field(repr=False)

    @property
    def is_owner(self) -> bool:
        return self.role == "OWNER"


class InvalidToken(Exception):
    pass


def decode_token(token: str, secret: str) -> CurrentUser:
    try:
        claims = jwt.decode(
            token,
            key=secret,
            algorithms=[JWT_ALGORITHM],
            options={"require": list(REQUIRED_CLAIMS)},
            leeway=LEEWAY_SECONDS,
        )
    except jwt.ExpiredSignatureError as exc:
        raise InvalidToken("Phiên đăng nhập đã hết hạn") from exc
    except jwt.PyJWTError as exc:
        raise InvalidToken("Token không hợp lệ") from exc

    try:
        user_id = uuid.UUID(str(claims["sub"]))
        store_id = uuid.UUID(str(claims["store_id"]))
    except ValueError as exc:
        raise InvalidToken("Claim sub hoặc store_id không phải uuid") from exc
    role = claims["role"]
    if role not in ROLES:
        raise InvalidToken("Claim role không hợp lệ")
    jti = claims["jti"]
    if not isinstance(jti, str) or not jti:
        raise InvalidToken("Claim jti không hợp lệ")
    return CurrentUser(user_id=user_id, store_id=store_id, role=role, jti=jti, token=token)


def _extract_token(request: Request) -> str | None:
    cookie = request.cookies.get(ACCESS_TOKEN_COOKIE)
    if cookie:
        return cookie
    authorization = request.headers.get("authorization", "")
    scheme, _, credentials = authorization.partition(" ")
    if scheme.lower() == "bearer" and credentials.strip():
        return credentials.strip()
    return None


def _unauthenticated(detail: str) -> ProblemException:
    return ProblemException(
        401, TYPE_UNAUTHENTICATED, "Chưa đăng nhập", detail, headers={"WWW-Authenticate": 'Bearer realm="ai"'}
    )


def current_user(request: Request) -> CurrentUser:
    token = _extract_token(request)
    if token is None:
        raise _unauthenticated("Thiếu token đăng nhập")
    secret = request.app.state.settings.jwt_secret.get_secret_value()
    try:
        return decode_token(token, secret)
    except InvalidToken as exc:
        raise _unauthenticated(str(exc)) from None


def require_owner(user: Annotated[CurrentUser, Depends(current_user)]) -> CurrentUser:
    if not user.is_owner:
        raise ProblemException(403, TYPE_FORBIDDEN, "Không có quyền", "Chỉ Chủ cửa hàng dùng được chức năng này")
    return user


CurrentUserDep = Annotated[CurrentUser, Depends(current_user)]
OwnerDep = Annotated[CurrentUser, Depends(require_owner)]
