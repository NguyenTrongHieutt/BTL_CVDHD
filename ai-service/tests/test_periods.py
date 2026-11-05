"""Quy cụm thời gian ra khoảng ngày (AD-19): Thứ Hai đầu tuần, bao gồm hai đầu, giờ Việt Nam."""

from datetime import UTC, date, datetime

import pytest
from pydantic import ValidationError

from app.qa.periods import Period, today_vn

THU = date(2026, 11, 5)  # Thứ Năm


def _range(kind: str, today: date = THU, **fields) -> tuple[str, str]:
    r = Period.model_validate({"kind": kind, **fields}).resolve(today)
    return r.from_date.isoformat(), r.to_date.isoformat()


@pytest.mark.parametrize(
    ("kind", "fields", "expected"),
    [
        ("TODAY", {}, ("2026-11-05", "2026-11-05")),
        ("YESTERDAY", {}, ("2026-11-04", "2026-11-04")),
        ("THIS_WEEK", {}, ("2026-11-02", "2026-11-05")),
        ("LAST_WEEK", {}, ("2026-10-26", "2026-11-01")),
        ("THIS_MONTH", {}, ("2026-11-01", "2026-11-05")),
        ("LAST_MONTH", {}, ("2026-10-01", "2026-10-31")),
        ("THIS_YEAR", {}, ("2026-01-01", "2026-11-05")),
        ("LAST_YEAR", {}, ("2025-01-01", "2025-12-31")),
        ("LAST_N_DAYS", {"days": 7}, ("2026-10-30", "2026-11-05")),
        ("LAST_N_DAYS", {"days": 1}, ("2026-11-05", "2026-11-05")),
        ("MONTH", {"month": 2}, ("2026-02-01", "2026-02-28")),
        ("MONTH", {"month": 12}, ("2025-12-01", "2025-12-31")),  # tháng chưa tới → năm trước
        ("MONTH", {"month": 2, "year": 2024}, ("2024-02-01", "2024-02-29")),
        ("DATE_RANGE", {"fromDate": "2026-10-01", "toDate": "2026-10-15"}, ("2026-10-01", "2026-10-15")),
    ],
)
def test_period_resolution(kind, fields, expected):
    assert _range(kind, **fields) == expected


def test_week_boundaries_on_monday_and_sunday():
    assert _range("THIS_WEEK", today=date(2026, 11, 2)) == ("2026-11-02", "2026-11-02")
    assert _range("THIS_WEEK", today=date(2026, 11, 8)) == ("2026-11-02", "2026-11-08")
    assert _range("LAST_WEEK", today=date(2026, 11, 8)) == ("2026-10-26", "2026-11-01")


def test_last_month_in_january():
    assert _range("LAST_MONTH", today=date(2027, 1, 10)) == ("2026-12-01", "2026-12-31")


def test_range_longer_than_366_days_is_rejected():
    with pytest.raises(ValidationError):
        Period.model_validate({"kind": "DATE_RANGE", "fromDate": "2025-01-01", "toDate": "2026-01-02"})
    assert _range("LAST_N_DAYS", days=366)[0] == "2025-11-05"


def test_today_uses_vietnam_time():
    # 17:30 UTC ngày 5/11 là 00:30 ngày 6/11 ở Việt Nam.
    assert today_vn(datetime(2026, 11, 5, 17, 30, tzinfo=UTC)) == date(2026, 11, 6)
    assert today_vn(datetime(2026, 11, 5, 16, 59, tzinfo=UTC)) == date(2026, 11, 5)
    with pytest.raises(ValueError):
        today_vn(datetime(2026, 11, 5, 12, 0))
