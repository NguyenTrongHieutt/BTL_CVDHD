"""Bảng giữ nguyên số từ reporting, kỳ và định nghĩa rõ ràng, không có PII."""

from datetime import date

import pytest

from app.qa.answers import build_answer
from app.qa.intent import interpret
from app.qa.schemas import QuotaInfo
from app.qa.tools import to_reporting_query

TODAY = date(2026, 11, 28)
QUOTA = QuotaInfo(used=1, limit=50)


def answer(question, *, period, columns, rows, summary):
    decision = interpret(question, TODAY)
    params = to_reporting_query(decision.query_id, decision.params, TODAY)
    return build_answer(decision, {
        "queryId": decision.query_id, "range": period, "params": params,
        "columns": columns, "rows": rows, "summary": summary,
    }, params, QUOTA, TODAY)


@pytest.mark.parametrize(
    ("question", "key", "label", "kind", "value", "definition"),
    [
        ("Doanh thu hôm nay", "revenue", "Doanh thu thuần", "money", 120000, "giảm giá"),
        ("Số đơn hôm nay", "orderCount", "Số đơn", "int", 5, "đã hủy"),
        ("Lợi nhuận hôm nay", "grossProfit", "Lợi nhuận gộp", "money", -1000, "giá vốn"),
    ],
)
def test_metric_answer_uses_exact_summary_and_explains_definition(question, key, label, kind, value, definition):
    result = answer(question,
        period={"fromDate": "2026-11-28", "toDate": "2026-11-28"},
        columns=[{"key": "date", "label": "Ngày", "type": "date"}, {"key": key, "label": label, "type": kind}],
        rows=[{"date": "2026-11-28", key: value}], summary={key: value},
    )
    assert result.table.rows == [["2026-11-28", value]]
    assert definition in result.interpretation
    assert result.chart.x == "date" and result.chart.y == [key]


@pytest.mark.parametrize("question", ["Hàng tồn nhiều nhưng bán chậm", "Gợi ý nhập hàng", "Nên ưu tiên bán sản phẩm nào"])
def test_recommendations_accept_fractional_rates_and_show_match_count(question):
    replenishment = question == "Gợi ý nhập hàng"
    result = answer(question,
        period={"fromDate": "2026-11-15" if replenishment else "2026-10-30", "toDate": "2026-11-28"},
        columns=[{"key": "sku", "label": "SKU", "type": "text"}, {"key": "dailySales", "label": "Bán/ngày", "type": "number"}, {"key": "daysOfStock", "label": "Đủ bán (ngày)", "type": "number"}],
        rows=[{"sku": "BIA-001", "dailySales": 0.25, "daysOfStock": None}],
        summary={"matchCount": 87, "shownCount": 1},
    )
    assert result.table.rows == [["BIA-001", 0.25, None]]
    assert result.table.totalRows == 87 and result.table.truncated is True
    assert "87" in result.answer


def test_large_inventory_returns_first_fifty_without_losing_aggregate_totals():
    result = answer("Tồn kho hiện tại",
        period={"fromDate": "2026-11-28", "toDate": "2026-11-28"},
        columns=[{"key": "sku", "label": "SKU", "type": "text"}, {"key": "stockQuantity", "label": "Tồn", "type": "int"}],
        rows=[{"sku": f"SKU-{index}", "stockQuantity": 1} for index in range(10001)],
        summary={"stockQuantity": 10001, "stockValue": 10001000, "variantCount": 10001},
    )
    assert len(result.table.rows) == 50 and result.table.totalRows == 10001 and result.table.truncated is True
    assert result.table.rows[49] == ["SKU-49", 1]
    assert "10.001" in result.answer


@pytest.mark.parametrize(("previous", "current", "outcome"), [(0, 500000, "bằng 0"), (100, 125, "tăng"), (125, 100, "giảm"), (100, 100, "không đổi")])
def test_comparison_describes_direction_without_dividing_by_zero(previous, current, outcome):
    result = answer("So sánh doanh thu tháng này với tháng trước",
        period={"fromDate": "2026-11-01", "toDate": "2026-11-28"},
        columns=[{"key": "fromDate", "label": "Từ ngày", "type": "date"}, {"key": "toDate", "label": "Đến ngày", "type": "date"}, {"key": "value", "label": "Doanh thu", "type": "money"}],
        rows=[{"fromDate": "2026-11-01", "toDate": "2026-11-28", "value": current}, {"fromDate": "2026-10-01", "toDate": "2026-10-31", "value": previous}],
        summary={"metric": "REVENUE", "currentValue": current, "comparisonValue": previous, "difference": current - previous},
    )
    assert outcome in result.answer
    if previous:
        expected_rate = {125: "25,0%", 100: "-20,0%" if previous != current else "0,0%"}
        assert expected_rate[current] in result.answer
    assert "01/10/2026–31/10/2026" in result.interpretation
    assert result.table.rows[0][2] == current and result.table.rows[1][2] == previous
