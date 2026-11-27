"""Hỏi đáp báo cáo cục bộ: chỉ đọc Q1–Q9, hạn mức nguyên tử và lưu vết theo tenant."""

import logging
import time
from collections.abc import Iterator
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends, Request

from app import db, quota
from app.auth import OwnerDep
from app.problems import (
    TYPE_AI_QUOTA_EXCEEDED, TYPE_AI_UNAVAILABLE, TYPE_FORBIDDEN, TYPE_UNAUTHENTICATED,
    ProblemException, get_request_id,
)
from app.qa import audit
from app.qa.answers import build_answer
from app.qa.intent import interpret
from app.qa.periods import today_vn
from app.qa.reporting import ReportingClient
from app.qa.schemas import AskRequest, AskResponse, QuotaInfo
from app.qa.tools import QueryId, to_reporting_query

logger = logging.getLogger("app.qa")
router = APIRouter(tags=["qa"])


def reporting_client(request: Request) -> Iterator[ReportingClient]:
    client = ReportingClient(request.app.state.settings.backend_url)
    try:
        yield client
    finally:
        client.close()


def _unavailable() -> ProblemException:
    return ProblemException(503, TYPE_AI_UNAVAILABLE, "Dịch vụ AI chưa sẵn sàng", "Chưa lấy được kết quả báo cáo. Vui lòng thử lại; lỗi kỹ thuật không tính lượt.")


@router.post(
    "/ask", response_model=AskResponse,
    responses={429: {"description": "Hết 50 lượt trong ngày"}, 503: {"description": "Báo cáo chưa sẵn sàng"}},
)
def ask(
    body: AskRequest, user: OwnerDep, request: Request,
    reporting: Annotated[ReportingClient, Depends(reporting_client)],
) -> AskResponse:
    started = time.monotonic()
    day = today_vn()
    request_id = get_request_id(request)
    query_id: QueryId | None = None
    params: dict[str, str | int] = {}

    def log_result(answer: AskResponse | None, error: str | None = None) -> None:
        with db.store_transaction(user.store_id) as conn:
            audit.record(
                conn, user=user, question=body.question, query_id=query_id, params=params,
                answer=answer, refused=bool(answer and answer.refused),
                duration_ms=int((time.monotonic() - started) * 1000), error=error, request_id=request_id,
            )

    try:
        with db.store_transaction(user.store_id) as conn:
            reservation = quota.reserve(conn, user.store_id, day)
    except Exception as exc:
        logger.warning("Không giữ được hạn mức; requestId=%s; error=%s", request_id, type(exc).__name__)
        raise _unavailable() from None
    if reservation is None:
        try:
            log_result(None, "QUOTA_EXCEEDED")
        except Exception as exc:
            logger.warning("Không lưu được từ chối hạn mức; requestId=%s; error=%s", request_id, type(exc).__name__)
        raise ProblemException(429, TYPE_AI_QUOTA_EXCEEDED, "Đã hết lượt hỏi trong ngày", "Mỗi cửa hàng có 50 lượt mỗi ngày, làm mới lúc 0 giờ theo giờ Việt Nam.")

    try:
        decision = interpret(body.question, day)
        quota_info = QuotaInfo(used=reservation.used, limit=reservation.limit)
        query_id = decision.query_id
        if query_id is None:
            result = AskResponse(
                answer=decision.reason, interpretation="Chưa chọn truy vấn báo cáo; hãy xem phạm vi hỗ trợ trong câu trả lời.",
                table=None, chart=None, reportLink=None, quota=quota_info, refused=True,
            )
        else:
            assert decision.params is not None
            params = to_reporting_query(query_id, decision.params, day)
            raw = reporting.query(query_id, params, token=user.token, request_id=request_id)
            result = build_answer(decision, raw, params, quota_info, day)
        # Do not return an unrecorded successful result: failures here are technical failures too.
        log_result(result)
        return result
    except Exception as exc:
        if isinstance(exc, httpx.TimeoutException):
            error = "REPORTING_TIMEOUT"
        elif isinstance(exc, httpx.HTTPStatusError):
            error = f"REPORTING_HTTP_{exc.response.status_code}"
        elif isinstance(exc, ValueError):
            error = "INVALID_REPORTING_RESPONSE"
        else:
            error = "QA_UNAVAILABLE"
        # Refund independently of the audit write: an audit outage must not cancel a refund.
        try:
            with db.store_transaction(user.store_id) as conn:
                quota.refund(conn, reservation)
        except Exception as refund_error:
            logger.error("Không hoàn được lượt; requestId=%s; error=%s", request_id, type(refund_error).__name__)
        try:
            log_result(None, error)
        except Exception as audit_error:
            logger.error("Không lưu được lỗi hỏi đáp; requestId=%s; error=%s", request_id, type(audit_error).__name__)
        logger.warning("Hỏi đáp thất bại; requestId=%s; error=%s", request_id, error)
        if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code in {401, 403}:
            status = exc.response.status_code
            raise ProblemException(
                status, TYPE_UNAUTHENTICATED if status == 401 else TYPE_FORBIDDEN,
                "Phiên đăng nhập không hợp lệ" if status == 401 else "Không có quyền xem báo cáo",
                "Vui lòng đăng nhập lại." if status == 401 else "Không thể xem số liệu báo cáo với tài khoản hiện tại.",
            ) from None
        raise _unavailable() from None
