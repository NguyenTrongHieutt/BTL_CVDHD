"""Gọi `GET /api/v1/reporting/q/<Qn>` bằng JWT của người dùng (AD-9, AD-10).

ai-service không đọc bảng nghiệp vụ; mọi con số đến từ đây. Token được chuyển nguyên
sang header `Authorization: Bearer`, nên `reporting` tự kiểm vai trò và Cửa hàng.
"""

from typing import Any

import httpx

from app.qa.tools import QueryId

REPORTING_PATH = "/api/v1/reporting/q/{query_id}"


class ReportingClient:
    def __init__(self, base_url: str, *, timeout_seconds: float = 4.0, transport: httpx.BaseTransport | None = None):
        self._client = httpx.Client(base_url=base_url, timeout=timeout_seconds, transport=transport)

    def close(self) -> None:
        self._client.close()

    def query(
        self, query_id: QueryId, params: dict[str, str | int], *, token: str, request_id: str | None = None
    ) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        if request_id:
            headers["X-Request-Id"] = request_id
        response = self._client.get(REPORTING_PATH.format(query_id=query_id.value), params=params, headers=headers)
        response.raise_for_status()
        body = response.json()
        if "range" not in body:  # AD-10: mỗi phản hồi luôn kèm `range`
            raise ValueError(f"Phản hồi {query_id} thiếu trường range")
        return body
