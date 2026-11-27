"""Lưu vết Hỏi đáp trong transaction có RLS của đúng cửa hàng."""

from typing import Any

import psycopg
from psycopg.types.json import Jsonb

from app.auth import CurrentUser
from app.qa.schemas import AskResponse
from app.qa.tools import QueryId

INSERT_SQL = """\
insert into ai.ai_query_log
    (store_id, user_id, question, query_id, params, answer, refused, duration_ms, error)
values
    (%(store_id)s, %(user_id)s, %(question)s, %(query_id)s, %(params)s, %(answer)s,
     %(refused)s, %(duration_ms)s, %(error)s)
"""


def record(
    conn: psycopg.Connection, *, user: CurrentUser, question: str, query_id: QueryId | None,
    params: dict[str, str | int], answer: AskResponse | None, refused: bool,
    duration_ms: int, error: str | None, request_id: str,
) -> None:
    payload: dict[str, Any] = answer.model_dump(mode="json", by_alias=True) if answer else {}
    payload["requestId"] = request_id
    conn.execute(INSERT_SQL, {
        "store_id": user.store_id, "user_id": user.user_id, "question": question,
        "query_id": query_id.value if query_id else None, "params": Jsonb(params), "answer": Jsonb(payload),
        "refused": refused, "duration_ms": max(0, duration_ms), "error": error,
    })
