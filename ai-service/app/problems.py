"""Lỗi dạng RFC 9457 Problem Details (AD-19) và `X-Request-Id`.

Mọi lỗi trả về đều có `type`, `title`, `status`, `detail`, `instance`, `requestId`;
lỗi dữ liệu nhập có thêm `errors[]`. Mã `type` phải được đăng ký trong `docs/errors.md`
(nhóm `common` dùng chung, nhóm `ai` do C giữ).
"""

import logging
import re
import uuid
from collections.abc import Sequence
from http import HTTPStatus
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.datastructures import Headers, MutableHeaders
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.log import request_id_var

logger = logging.getLogger("app.problems")

PROBLEM_JSON = "application/problem+json"
REQUEST_ID_HEADER = "X-Request-Id"
_REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")

# Mã `type` của ai-service. Chỉ thêm mã vào nhóm `ai`; nhóm `common` dùng chung với backend.
TYPE_VALIDATION = "/errors/common/validation"
TYPE_UNAUTHENTICATED = "/errors/common/unauthorized"
TYPE_FORBIDDEN = "/errors/common/forbidden"
TYPE_NOT_FOUND = "/errors/common/not-found"
TYPE_METHOD_NOT_ALLOWED = "/errors/common/method-not-allowed"
TYPE_INTERNAL = "/errors/common/internal"
TYPE_AI_NOT_IMPLEMENTED = "/errors/ai/not-implemented"
TYPE_AI_QUOTA_EXCEEDED = "/errors/ai/quota-exceeded"
TYPE_AI_UNAVAILABLE = "/errors/ai/unavailable"

_TYPE_BY_STATUS = {
    400: (TYPE_VALIDATION, "Dữ liệu nhập không hợp lệ"),
    401: (TYPE_UNAUTHENTICATED, "Chưa đăng nhập"),
    403: (TYPE_FORBIDDEN, "Không có quyền"),
    404: (TYPE_NOT_FOUND, "Không tìm thấy"),
    405: (TYPE_METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ"),
    500: (TYPE_INTERNAL, "Lỗi hệ thống"),
    501: (TYPE_AI_NOT_IMPLEMENTED, "Chức năng chưa làm xong"),
    503: (TYPE_AI_UNAVAILABLE, "Dịch vụ AI không sẵn sàng"),
}


class ProblemException(Exception):
    """Ném trong code nghiệp vụ để trả một Problem Details cụ thể."""

    def __init__(
        self,
        status: int,
        type_: str,
        title: str,
        detail: str | None = None,
        *,
        errors: Sequence[dict[str, Any]] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(detail or title)
        self.status = status
        self.type = type_
        self.title = title
        self.detail = detail
        self.errors = list(errors) if errors else None
        self.headers = headers


def not_implemented(detail: str) -> ProblemException:
    return ProblemException(501, TYPE_AI_NOT_IMPLEMENTED, "Chức năng chưa làm xong", detail)


def get_request_id(request: Request) -> str:
    rid = request.scope.get("state", {}).get("request_id")
    return rid or _new_request_id()


def _new_request_id() -> str:
    return uuid.uuid4().hex


def problem_response(
    request: Request,
    status: int,
    type_: str,
    title: str,
    detail: str | None = None,
    *,
    errors: Sequence[dict[str, Any]] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    request_id = get_request_id(request)
    body: dict[str, Any] = {
        "type": type_,
        "title": title,
        "status": status,
        "detail": detail,
        "instance": request.url.path,
        "requestId": request_id,
    }
    if errors is not None:
        body["errors"] = list(errors)
    response_headers = {REQUEST_ID_HEADER: request_id, **(headers or {})}
    return JSONResponse(body, status_code=status, headers=response_headers, media_type=PROBLEM_JSON)


def _field_path(loc: Sequence[Any]) -> str:
    parts = list(loc)
    if parts and parts[0] in {"query", "body", "path", "header", "cookie"}:
        parts = parts[1:]
    path = ""
    for part in parts:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}" if path else str(part)
    return path or str(loc[0] if loc else "")


async def _validation_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, RequestValidationError)
    errors = [
        {"field": _field_path(err.get("loc", ())), "code": err.get("type", "invalid"), "message": err.get("msg", "")}
        for err in exc.errors()
    ]
    return problem_response(
        request, 400, TYPE_VALIDATION, "Dữ liệu nhập không hợp lệ", "Kiểm tra lại các trường trong errors[]", errors=errors
    )


async def _http_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, StarletteHTTPException)
    type_, title = _TYPE_BY_STATUS.get(exc.status_code, ("about:blank", _phrase(exc.status_code)))
    detail = exc.detail if isinstance(exc.detail, str) else None
    if detail == _phrase(exc.status_code):
        detail = None
    return problem_response(request, exc.status_code, type_, title, detail, headers=getattr(exc, "headers", None))


async def _problem_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    assert isinstance(exc, ProblemException)
    return problem_response(request, exc.status, exc.type, exc.title, exc.detail, errors=exc.errors, headers=exc.headers)


async def _unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # Không đưa nội dung exception ra ngoài; chi tiết nằm trong log, tra bằng requestId.
    logger.error(
        "Lỗi không xử lý được ở %s %s",
        request.method,
        request.url.path,
        exc_info=exc,
        extra={"requestId": get_request_id(request)},
    )
    return problem_response(request, 500, TYPE_INTERNAL, "Lỗi hệ thống", "Đã ghi log, tra theo requestId")


def _phrase(status: int) -> str:
    try:
        return HTTPStatus(status).phrase
    except ValueError:
        return "Error"


class RequestIdMiddleware:
    """Đọc `X-Request-Id` (nginx sinh nếu chưa có) hoặc tự sinh, rồi gắn vào response và log."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        incoming = Headers(scope=scope).get(REQUEST_ID_HEADER)
        request_id = incoming if incoming and _REQUEST_ID_RE.fullmatch(incoming) else _new_request_id()
        scope.setdefault("state", {})["request_id"] = request_id
        token = request_id_var.set(request_id)

        async def send_with_request_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)[REQUEST_ID_HEADER] = request_id
            await send(message)

        try:
            await self.app(scope, receive, send_with_request_id)
        finally:
            request_id_var.reset(token)


def install_problem_handlers(app: FastAPI) -> None:
    app.add_exception_handler(RequestValidationError, _validation_handler)
    app.add_exception_handler(StarletteHTTPException, _http_exception_handler)
    app.add_exception_handler(ProblemException, _problem_exception_handler)
    app.add_exception_handler(Exception, _unhandled_exception_handler)
    app.add_middleware(RequestIdMiddleware)
