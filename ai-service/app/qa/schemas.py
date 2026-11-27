"""Dạng request/response của `POST /ai/ask` (AD-11 mục 3)."""

from typing import Any

from pydantic import BaseModel, Field, computed_field, field_validator

from app.qa.periods import DateRange
from app.qa.tools import QueryId

QUESTION_MAX_LENGTH = 500
DISCLAIMER = "AI có thể sai, hãy đối chiếu trong báo cáo"


class AskRequest(BaseModel):
    question: str = Field(min_length=1, max_length=QUESTION_MAX_LENGTH)

    @field_validator("question")
    @classmethod
    def _not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Câu hỏi không được để trống")
        return value.strip()


class QuotaInfo(BaseModel):
    used: int
    limit: int

    @computed_field
    @property
    def remaining(self) -> int:
        return max(0, self.limit - self.used)


class AnswerTable(BaseModel):
    columns: list[str]
    rows: list[list[Any]]
    columnKeys: list[str] = Field(default_factory=list)
    columnTypes: list[str] = Field(default_factory=list)
    totalRows: int = 0
    truncated: bool = False


class AnswerChart(BaseModel):
    kind: str  # "line" | "bar"
    x: str
    y: list[str]


class AskResponse(BaseModel):
    """Câu trả lời được dựng từ số liệu của truy vấn báo cáo."""

    answer: str | None
    interpretation: str  # AI đã hiểu câu hỏi thế nào: Qn, khoảng ngày, loại số liệu (FR-37)
    table: AnswerTable | None
    chart: AnswerChart | None
    reportLink: str | None
    disclaimer: str = DISCLAIMER
    quota: QuotaInfo
    refused: bool
    queryId: QueryId | None = None
    source: str | None = None
    period: DateRange | None = None
    params: dict[str, str | int] = Field(default_factory=dict)
    summary: dict[str, Any] | None = None
