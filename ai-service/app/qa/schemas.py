"""Dạng request/response của `POST /ai/ask` (AD-11 mục 3)."""

from typing import Any

from pydantic import BaseModel, Field

QUESTION_MAX_LENGTH = 500
DISCLAIMER = "AI có thể sai, hãy đối chiếu trong báo cáo"


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=QUESTION_MAX_LENGTH)


class QuotaInfo(BaseModel):
    used: int
    limit: int


class AnswerTable(BaseModel):
    columns: list[str]
    rows: list[list[Any]]


class AnswerChart(BaseModel):
    kind: str  # "line" | "bar"
    x: str
    y: list[str]


class AskResponse(BaseModel):
    """Do code dựng; con số do code điền từ kết quả Qn, LLM chỉ viết lời dẫn."""

    answer: str | None  # lời dẫn; None khi bị bỏ vì số không khớp hoặc lần gọi 2 lỗi
    interpretation: str  # AI đã hiểu câu hỏi thế nào: Qn, khoảng ngày, loại số liệu (FR-37)
    table: AnswerTable | None
    chart: AnswerChart | None
    reportLink: str | None
    disclaimer: str = DISCLAIMER
    quota: QuotaInfo
    refused: bool
