"""Quy cụm thời gian ra khoảng ngày cụ thể (AD-19). LLM KHÔNG tự tính ngày.

LLM chỉ chọn `kind` (TODAY, LAST_MONTH, …) hoặc chép lại ngày người dùng nêu rõ
(DATE_RANGE); code ở đây quy ra `fromDate`/`toDate` kiểu `date`, bao gồm cả hai đầu,
theo lịch Việt Nam (Asia/Ho_Chi_Minh), tối đa 366 ngày.

Quy ước (C chốt cùng `reporting`):
- Tuần bắt đầu Thứ Hai, kết thúc Chủ Nhật.
- Kỳ "này" (THIS_WEEK, THIS_MONTH, THIS_YEAR) tính từ đầu kỳ đến hôm nay, vì sau hôm nay
  chưa có dữ liệu; câu trả lời ghi đúng khoảng đã dùng (FR-37).
- LAST_N_DAYS gồm cả hôm nay: "7 ngày qua" = hôm nay và 6 ngày trước.
- MONTH không có năm: tháng đó của năm nay, hoặc của năm trước nếu tháng đó chưa tới.
"""

from datetime import date, datetime, timedelta
from enum import StrEnum
from typing import Self
from zoneinfo import ZoneInfo

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

VN_TZ = ZoneInfo("Asia/Ho_Chi_Minh")
MAX_RANGE_DAYS = 366


def today_vn(now: datetime | None = None) -> date:
    """Ngày hiện tại theo giờ Việt Nam. `now` (để test) phải có múi giờ."""
    moment = now or datetime.now(tz=VN_TZ)
    if moment.tzinfo is None:
        raise ValueError("now phải có múi giờ")
    return moment.astimezone(VN_TZ).date()


class PeriodKind(StrEnum):
    TODAY = "TODAY"
    YESTERDAY = "YESTERDAY"
    THIS_WEEK = "THIS_WEEK"
    LAST_WEEK = "LAST_WEEK"
    THIS_MONTH = "THIS_MONTH"
    LAST_MONTH = "LAST_MONTH"
    THIS_YEAR = "THIS_YEAR"
    LAST_YEAR = "LAST_YEAR"
    LAST_N_DAYS = "LAST_N_DAYS"
    MONTH = "MONTH"
    DATE_RANGE = "DATE_RANGE"


# Trường nào được (và phải) có theo từng kind. Trường không liệt kê phải để trống.
_REQUIRED_FIELDS: dict[PeriodKind, frozenset[str]] = {
    PeriodKind.LAST_N_DAYS: frozenset({"days"}),
    PeriodKind.MONTH: frozenset({"month"}),
    PeriodKind.DATE_RANGE: frozenset({"from_date", "to_date"}),
}
_OPTIONAL_FIELDS: dict[PeriodKind, frozenset[str]] = {PeriodKind.MONTH: frozenset({"year"})}
_ALL_FIELDS = frozenset({"days", "month", "year", "from_date", "to_date"})


class DateRange(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, validate_by_name=True, validate_by_alias=True, frozen=True)

    from_date: date
    to_date: date

    @property
    def days(self) -> int:
        return (self.to_date - self.from_date).days + 1

    def as_query(self, prefix: str = "") -> dict[str, str]:
        """Tham số cho `GET /api/v1/reporting/q/<Qn>`: `fromDate`, `toDate` (hoặc có tiền tố)."""
        if prefix:
            return {f"{prefix}FromDate": self.from_date.isoformat(), f"{prefix}ToDate": self.to_date.isoformat()}
        return {"fromDate": self.from_date.isoformat(), "toDate": self.to_date.isoformat()}


class Period(BaseModel):
    """Tham số `period` mà LLM điền trong công cụ Q1–Q6."""

    model_config = ConfigDict(
        alias_generator=to_camel, validate_by_name=True, validate_by_alias=True, extra="forbid", frozen=True
    )

    kind: PeriodKind
    days: int | None = Field(default=None, ge=1, le=MAX_RANGE_DAYS)
    month: int | None = Field(default=None, ge=1, le=12)
    year: int | None = Field(default=None, ge=2000, le=2100)
    from_date: date | None = None
    to_date: date | None = None

    @model_validator(mode="after")
    def _fields_match_kind(self) -> Self:
        required = _REQUIRED_FIELDS.get(self.kind, frozenset())
        allowed = required | _OPTIONAL_FIELDS.get(self.kind, frozenset())
        missing = sorted(f for f in required if getattr(self, f) is None)
        extra = sorted(f for f in _ALL_FIELDS - allowed if getattr(self, f) is not None)
        if missing:
            raise ValueError(f"kind={self.kind} cần {', '.join(to_camel(f) for f in missing)}")
        if extra:
            raise ValueError(f"kind={self.kind} không dùng {', '.join(to_camel(f) for f in extra)}")
        if self.kind is PeriodKind.DATE_RANGE:
            assert self.from_date is not None and self.to_date is not None
            if self.from_date > self.to_date:
                raise ValueError("fromDate phải trước hoặc bằng toDate")
            if (self.to_date - self.from_date).days + 1 > MAX_RANGE_DAYS:
                raise ValueError(f"Khoảng ngày tối đa {MAX_RANGE_DAYS} ngày")
        return self

    def resolve(self, today: date) -> DateRange:
        return resolve_period(self, today)


def _month_end(year: int, month: int) -> date:
    first_next = date(year + (month == 12), month % 12 + 1, 1)
    return first_next - timedelta(days=1)


def resolve_period(period: Period, today: date) -> DateRange:
    match period.kind:
        case PeriodKind.TODAY:
            start, end = today, today
        case PeriodKind.YESTERDAY:
            start = end = today - timedelta(days=1)
        case PeriodKind.THIS_WEEK:
            start, end = today - timedelta(days=today.weekday()), today
        case PeriodKind.LAST_WEEK:
            this_monday = today - timedelta(days=today.weekday())
            start, end = this_monday - timedelta(days=7), this_monday - timedelta(days=1)
        case PeriodKind.THIS_MONTH:
            start, end = today.replace(day=1), today
        case PeriodKind.LAST_MONTH:
            end = today.replace(day=1) - timedelta(days=1)
            start = end.replace(day=1)
        case PeriodKind.THIS_YEAR:
            start, end = date(today.year, 1, 1), today
        case PeriodKind.LAST_YEAR:
            start, end = date(today.year - 1, 1, 1), date(today.year - 1, 12, 31)
        case PeriodKind.LAST_N_DAYS:
            assert period.days is not None
            start, end = today - timedelta(days=period.days - 1), today
        case PeriodKind.MONTH:
            assert period.month is not None
            year = period.year or (today.year if period.month <= today.month else today.year - 1)
            start, end = date(year, period.month, 1), _month_end(year, period.month)
        case PeriodKind.DATE_RANGE:
            assert period.from_date is not None and period.to_date is not None
            start, end = period.from_date, period.to_date
    result = DateRange(from_date=start, to_date=end)
    if result.days > MAX_RANGE_DAYS:
        raise ValueError(f"Khoảng ngày tối đa {MAX_RANGE_DAYS} ngày")
    return result
