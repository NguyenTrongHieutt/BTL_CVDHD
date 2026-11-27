"""Dựng lời đáp từ số liệu reporting đã kiểm tra, không cho mô hình bịa số."""

import math
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Literal
from urllib.parse import urlencode

from pydantic import BaseModel, Field

from app.qa.intent import IntentDecision
from app.qa.periods import DateRange, Period, PeriodKind
from app.qa.schemas import AnswerChart, AnswerTable, AskResponse, QuotaInfo
from app.qa.tools import QueryId

SOURCE = "Báo cáo cửa hàng"
TITLES = {
    QueryId.Q1: "Doanh thu thuần", QueryId.Q2: "Số đơn hàng", QueryId.Q3: "Lợi nhuận gộp",
    QueryId.Q4: "Xếp hạng sản phẩm", QueryId.Q5: "Tồn kho và giá trị tồn kho",
    QueryId.Q6: "So sánh hai kỳ", QueryId.Q7: "Hàng tồn nhiều nhưng bán chậm",
    QueryId.Q8: "Gợi ý nhập hàng", QueryId.Q9: "Sản phẩm nên ưu tiên bán",
}
_STOCK_KEYS = {
    "productName", "variantName", "categoryName", "sku", "stockQuantity", "avgUnitCost", "salePrice",
    "stockValue", "soldQuantity", "dailySales", "daysOfStock", "minQuantity", "suggestedQuantity", "reason",
}
_COLUMN_KEYS = {
    QueryId.Q1: {"date", "grossSales", "refundTotal", "revenue", "discountTotal"},
    QueryId.Q2: {"date", "orderCount", "cancelledCount", "fullyReturnedCount", "returnCount"},
    QueryId.Q3: {"date", "revenue", "costOfGoodsSold", "grossProfit"},
    QueryId.Q4: {"rank", "productName", "categoryName", "quantity", "revenue", "soldQuantity", "returnedQuantity"},
    QueryId.Q5: _STOCK_KEYS, QueryId.Q6: {"fromDate", "toDate", "value"},
    QueryId.Q7: _STOCK_KEYS, QueryId.Q8: _STOCK_KEYS, QueryId.Q9: _STOCK_KEYS,
}
_SUMMARY_KEYS = {
    QueryId.Q1: {"grossSales", "refundTotal", "revenue", "discountTotal"},
    QueryId.Q2: {"orderCount", "cancelledCount", "fullyReturnedCount", "returnCount"},
    QueryId.Q3: {"revenue", "costOfGoodsSold", "grossProfit"}, QueryId.Q4: {"productCount"},
    QueryId.Q5: {"stockQuantity", "stockValue", "variantCount"},
    QueryId.Q6: {"metric", "currentValue", "comparisonValue", "difference"},
    QueryId.Q7: {"matchCount", "shownCount"}, QueryId.Q8: {"matchCount", "shownCount"},
    QueryId.Q9: {"matchCount", "shownCount"},
}


class _Column(BaseModel):
    key: str = Field(min_length=1, max_length=64)
    label: str = Field(min_length=1, max_length=100)
    type: Literal["text", "date", "money", "int", "percent", "number"]


class _Report(BaseModel):
    queryId: QueryId
    range: DateRange
    params: dict[str, Any]
    columns: list[_Column] = Field(min_length=1, max_length=30)
    rows: list[dict[str, Any]]
    summary: dict[str, Any]


def _number(value: Any) -> int | float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError("Số liệu reporting không hợp lệ")
    return value


def _cell(value: Any, column: _Column) -> Any:
    if value is None:
        return None
    if column.type in {"money", "int", "percent", "number"}:
        number = _number(value)
        if column.type in {"money", "int"} and int(number) != number:
            raise ValueError("Số tiền và số lượng phải là số nguyên")
        return number
    if not isinstance(value, str) or len(value) > 500:
        raise ValueError("Ô reporting không hợp lệ")
    if column.type == "date":
        date.fromisoformat(value)
    return value


def _format(value: int | float) -> str:
    number = f"{value:,.2f}".rstrip("0").rstrip(".") if isinstance(value, float) else f"{value:,}"
    return number.replace(",", "_").replace(".", ",").replace("_", ".")


def _range_text(period: DateRange) -> str:
    return f"{period.from_date:%d/%m/%Y}–{period.to_date:%d/%m/%Y}"


def _summary(report: _Report) -> dict[str, Any]:
    summary = {key: value for key, value in report.summary.items() if key in _SUMMARY_KEYS[report.queryId]}
    for key, value in summary.items():
        if key == "metric":
            if value not in {"REVENUE", "ORDER_COUNT", "GROSS_PROFIT"}:
                raise ValueError("Chỉ số so sánh không hợp lệ")
        else:
            _number(value)
    required = {
        QueryId.Q1: {"revenue"}, QueryId.Q2: {"orderCount"}, QueryId.Q3: {"grossProfit"},
        QueryId.Q4: {"productCount"}, QueryId.Q5: {"stockQuantity", "stockValue"},
        QueryId.Q6: {"metric", "currentValue", "comparisonValue", "difference"},
        QueryId.Q7: {"matchCount", "shownCount"}, QueryId.Q8: {"matchCount", "shownCount"},
        QueryId.Q9: {"matchCount", "shownCount"},
    }[report.queryId]
    if not required <= summary.keys():
        raise ValueError("Thiếu tổng hợp reporting")
    if report.queryId is QueryId.Q6 and summary["difference"] != summary["currentValue"] - summary["comparisonValue"]:
        raise ValueError("Chênh lệch không khớp hai kỳ")
    return summary


def _lead(query_id: QueryId, summary: dict[str, Any], params: dict[str, str | int]) -> str:
    match query_id:
        case QueryId.Q1:
            return f"Doanh thu thuần trong kỳ là {_format(summary['revenue'])} ₫."
        case QueryId.Q2:
            lead = f"Trong kỳ có {_format(summary['orderCount'])} đơn hàng chưa bị hủy."
            if "cancelledCount" in summary:
                lead += f" Có {_format(summary['cancelledCount'])} đơn đã hủy."
            if "fullyReturnedCount" in summary:
                lead += f" Có {_format(summary['fullyReturnedCount'])} đơn đã trả toàn bộ."
            return lead
        case QueryId.Q3:
            return f"Lợi nhuận gộp trong kỳ là {_format(summary['grossProfit'])} ₫."
        case QueryId.Q4:
            count = summary["productCount"]
            if count == 0:
                return "Chưa có sản phẩm phù hợp với xếp hạng trong kỳ."
            direction = "bán chậm" if params.get("direction") == "SLOW_SELLING" else "bán chạy"
            rank_by = "doanh thu thuần" if params.get("rankBy") == "REVENUE" else "số lượng bán ròng"
            return f"Có {_format(count)} sản phẩm {direction} trong bảng, xếp theo {rank_by}."
        case QueryId.Q5:
            return f"Tồn kho hiện tại là {_format(summary['stockQuantity'])} đơn vị hàng, trị giá {_format(summary['stockValue'])} ₫ theo giá vốn."
        case QueryId.Q6:
            unit = " đơn" if summary["metric"] == "ORDER_COUNT" else " ₫"
            difference = summary["difference"]
            outcome = "không đổi" if difference == 0 else "tăng" if difference > 0 else "giảm"
            lead = (
                f"Kỳ được hỏi đạt {_format(summary['currentValue'])}{unit}; "
                f"kỳ so sánh đạt {_format(summary['comparisonValue'])}{unit}. "
                f"Kết quả {outcome}" + ("." if difference == 0 else f" {_format(abs(difference))}{unit}.")
            )
            if summary["comparisonValue"] == 0:
                lead += " Kỳ so sánh bằng 0 nên không tính tỷ lệ phần trăm thay đổi."
            else:
                rate = (Decimal(difference) * 100 / Decimal(summary["comparisonValue"])).quantize(
                    Decimal("0.1"), rounding=ROUND_HALF_UP,
                )
                percentage = format(rate, "f").replace(".", ",")
                lead += f" Tỷ lệ thay đổi {percentage}% so với kỳ so sánh."
            return lead
        case QueryId.Q7:
            return f"Có {_format(summary['matchCount'])} mặt hàng tồn nhiều hoặc không bán được trong kỳ. Hãy đối chiếu tốc độ bán và số ngày tồn trong bảng."
        case QueryId.Q8:
            return f"Có {_format(summary['matchCount'])} mặt hàng cần xem xét nhập thêm. Số lượng gợi ý dựa trên tốc độ bán gần đây và ngưỡng tồn tối thiểu; bạn quyết định lượng nhập thực tế."
        case QueryId.Q9:
            return f"Có {_format(summary['matchCount'])} mặt hàng nên xem xét ưu tiên bán: bán chậm và có giá bán cao hơn giá vốn, xếp theo giá trị tồn giảm dần."


def build_answer(
    decision: IntentDecision, raw: dict[str, Any], params: dict[str, str | int],
    quota: QuotaInfo, today: date,
) -> AskResponse:
    report = _Report.model_validate(raw)
    query_id = decision.query_id
    if query_id is None or report.queryId != query_id:
        raise ValueError("Mã truy vấn trả về không khớp")
    Period(kind=PeriodKind.DATE_RANGE, from_date=report.range.from_date, to_date=report.range.to_date)
    expected = {key: value for key, value in params.items() if key in {"fromDate", "toDate"}}
    if query_id in {QueryId.Q5, QueryId.Q7, QueryId.Q8, QueryId.Q9}:
        days = 14 if query_id is QueryId.Q8 else 1 if query_id is QueryId.Q5 else 30
        expected = {"fromDate": (today - timedelta(days=days - 1)).isoformat(), "toDate": today.isoformat()}
    if report.range.as_query() != expected:
        raise ValueError("Khoảng ngày trả về không khớp")
    if any(report.params.get(key) != value for key, value in params.items()):
        raise ValueError("Tham số reporting trả về không khớp")
    if len({column.key for column in report.columns}) != len(report.columns):
        raise ValueError("Cột reporting bị trùng")
    if any(column.key not in _COLUMN_KEYS[query_id] for column in report.columns):
        raise ValueError("Cột reporting ngoài phạm vi tổng hợp")
    summary = _summary(report)
    if query_id is QueryId.Q6 and summary["metric"] != params.get("metric"):
        raise ValueError("Chỉ số reporting trả về không khớp")
    display_limit = 366 if query_id in {QueryId.Q1, QueryId.Q2, QueryId.Q3} else 50
    # Only visible safe columns are copied; large inventory responses stay bounded.
    rows = [[_cell(row.get(column.key), column) for column in report.columns] for row in report.rows[:display_limit]]
    total_rows = int(summary["matchCount"]) if query_id in {QueryId.Q7, QueryId.Q8, QueryId.Q9} else len(report.rows)
    table = AnswerTable(
        columns=[column.label for column in report.columns], columnKeys=[column.key for column in report.columns],
        columnTypes=[column.type for column in report.columns], rows=rows,
        totalRows=total_rows, truncated=total_rows > len(rows),
    )
    interpretation = f"{query_id.value} · {TITLES[query_id]} · {_range_text(report.range)} · dữ liệu cửa hàng của bạn."
    definition = {
        QueryId.Q1: "Doanh thu sau giảm giá và đã trừ tiền trả hàng.",
        QueryId.Q2: "Đếm theo ngày tạo đơn, không tính đơn đã hủy; đơn đã trả hết vẫn tính trong số đơn.",
        QueryId.Q3: "Lợi nhuận gộp bằng doanh thu thuần trừ giá vốn hàng bán.",
    }.get(query_id)
    if definition:
        interpretation += " " + definition
    if decision.defaulted_period:
        interpretation += " Bạn chưa nêu kỳ; dùng từ đầu tháng đến hôm nay."
    if query_id is QueryId.Q6:
        compare = DateRange(from_date=date.fromisoformat(str(params["compareFromDate"])), to_date=date.fromisoformat(str(params["compareToDate"])))
        interpretation += f" So với {_range_text(compare)}."
    if query_id is QueryId.Q5:
        filter_name = params.get("productName") or params.get("categoryName")
        if filter_name:
            interpretation += f" Bộ lọc: {filter_name}."
    chart = None
    numeric = {QueryId.Q1: "revenue", QueryId.Q2: "orderCount", QueryId.Q3: "grossProfit"}.get(query_id)
    if numeric and {"date", numeric} <= {column.key for column in report.columns} and rows:
        chart = AnswerChart(kind="line" if query_id in {QueryId.Q1, QueryId.Q3} else "bar", x="date", y=[numeric])
    metric = params.get("metric")
    path = "/reporting/stock"
    if query_id in {QueryId.Q1, QueryId.Q2} or query_id is QueryId.Q6 and metric in {"REVENUE", "ORDER_COUNT"}:
        path = "/reporting/revenue"
    elif query_id is QueryId.Q3 or query_id is QueryId.Q6 and metric == "GROSS_PROFIT":
        path = "/reporting/profit"
    elif query_id is QueryId.Q4:
        path = "/reporting/products"
    return AskResponse(
        answer=_lead(query_id, summary, params), interpretation=interpretation, table=table, chart=chart,
        reportLink=path + ("?" + urlencode(params) if params else ""), quota=quota, refused=False,
        queryId=query_id, source=SOURCE, period=report.range, params=params, summary=summary,
    )
