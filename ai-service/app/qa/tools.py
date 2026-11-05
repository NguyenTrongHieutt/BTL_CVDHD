"""9 công cụ Q1–Q9 cho Gemini: HỢP ĐỒNG giữa Hỏi đáp dữ liệu và `reporting` (AD-10, AD-11).

Mỗi công cụ có:
- `parameters`: JSON schema chặt (`additionalProperties: false`, enum, giới hạn số) gửi cho
  Gemini. Không có công cụ nào có quyền ghi (AD-11 mục 2).
- `params_model`: model Pydantic kiểm lại tham số LLM trả về trước khi gọi `reporting`.
  Test `tests/test_tools.py` giữ hai bản này khớp nhau.
- `to_reporting_query`: quy tham số ra query string của `GET /api/v1/reporting/q/<Qn>`.
  Kỳ thời gian do code quy ra `fromDate`/`toDate` (AD-19), không để LLM tính.

Tham số theo PRD mục 4.6.1:
    Q1 Doanh thu theo kỳ            period
    Q2 Số Đơn hàng theo kỳ          period
    Q3 Lợi nhuận gộp theo kỳ        period
    Q4 Bán chạy / bán chậm          period (tùy chọn), direction, rankBy, limit
    Q5 Tồn kho và giá trị Tồn kho   productName hoặc categoryName (tùy chọn)
    Q6 So sánh hai kỳ               metric (Q1/Q2/Q3), period, comparePeriod
    Q7 Tồn nhiều nhưng bán chậm     —
    Q8 Gợi ý nhập hàng              —
    Q9 Nên ưu tiên bán              —
"""

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from enum import StrEnum
from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from app.qa.periods import MAX_RANGE_DAYS, Period, PeriodKind


class QueryId(StrEnum):
    Q1 = "Q1"
    Q2 = "Q2"
    Q3 = "Q3"
    Q4 = "Q4"
    Q5 = "Q5"
    Q6 = "Q6"
    Q7 = "Q7"
    Q8 = "Q8"
    Q9 = "Q9"


class Direction(StrEnum):
    BEST_SELLING = "BEST_SELLING"
    SLOW_SELLING = "SLOW_SELLING"


class RankBy(StrEnum):
    QUANTITY = "QUANTITY"
    REVENUE = "REVENUE"


class Metric(StrEnum):
    REVENUE = "REVENUE"  # Q1
    ORDER_COUNT = "ORDER_COUNT"  # Q2
    GROSS_PROFIT = "GROSS_PROFIT"  # Q3


Q4_DEFAULT_LIMIT = 10
Q4_MAX_LIMIT = 50
# Q4 không nêu kỳ: dùng 30 ngày gần nhất, cùng cửa sổ với định nghĩa "bán chậm" (PRD 4.6.1).
Q4_DEFAULT_PERIOD = Period(kind=PeriodKind.LAST_N_DAYS, days=30)
NAME_MAX_LENGTH = 100


# ---------------------------------------------------------------------------
# Model Pydantic: kiểm tham số LLM trả về
# ---------------------------------------------------------------------------


class _Params(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, validate_by_name=True, validate_by_alias=True, extra="forbid", frozen=True
    )


class PeriodParams(_Params):
    period: Period


class Q4Params(_Params):
    period: Period | None = None
    direction: Direction
    rank_by: RankBy = RankBy.QUANTITY
    limit: int = Field(default=Q4_DEFAULT_LIMIT, ge=1, le=Q4_MAX_LIMIT)


class Q5Params(_Params):
    product_name: str | None = Field(default=None, min_length=1, max_length=NAME_MAX_LENGTH)
    category_name: str | None = Field(default=None, min_length=1, max_length=NAME_MAX_LENGTH)

    @model_validator(mode="after")
    def _at_most_one_filter(self) -> Self:
        if self.product_name is not None and self.category_name is not None:
            raise ValueError("Chỉ lọc theo productName hoặc categoryName, không cả hai")
        return self


class Q6Params(_Params):
    metric: Metric
    period: Period
    compare_period: Period


class NoParams(_Params):
    pass


# ---------------------------------------------------------------------------
# JSON schema gửi cho Gemini
# ---------------------------------------------------------------------------

_PERIOD_KIND_HELP = (
    "Chọn kind theo đúng cụm người dùng nói, KHÔNG tự tính ngày: "
    "TODAY = hôm nay; YESTERDAY = hôm qua; THIS_WEEK = tuần này; LAST_WEEK = tuần trước; "
    "THIS_MONTH = tháng này; LAST_MONTH = tháng trước; THIS_YEAR = năm nay; "
    "LAST_YEAR = năm ngoái, năm trước; LAST_N_DAYS = N ngày qua, N ngày gần đây (điền days); "
    "MONTH = một tháng cụ thể như 'tháng 9' (điền month; chỉ điền year khi người dùng nói năm); "
    "DATE_RANGE = chỉ khi người dùng nêu rõ ngày bắt đầu và ngày kết thúc (điền fromDate, toDate)."
)


def _period_schema(description: str) -> dict[str, Any]:
    return {
        "type": "object",
        "description": f"{description} {_PERIOD_KIND_HELP}",
        "properties": {
            "kind": {"type": "string", "enum": [k.value for k in PeriodKind]},
            "days": {
                "type": "integer",
                "minimum": 1,
                "maximum": MAX_RANGE_DAYS,
                "description": "Số ngày, chỉ dùng với LAST_N_DAYS.",
            },
            "month": {"type": "integer", "minimum": 1, "maximum": 12, "description": "Tháng 1–12, chỉ dùng với MONTH."},
            "year": {
                "type": "integer",
                "minimum": 2000,
                "maximum": 2100,
                "description": "Năm, chỉ dùng với MONTH và chỉ khi người dùng nói năm.",
            },
            "fromDate": {
                "type": "string",
                "format": "date",
                "description": "Ngày đầu YYYY-MM-DD, chỉ dùng với DATE_RANGE.",
            },
            "toDate": {
                "type": "string",
                "format": "date",
                "description": "Ngày cuối YYYY-MM-DD (tính cả ngày này), chỉ dùng với DATE_RANGE.",
            },
        },
        "required": ["kind"],
        "additionalProperties": False,
    }


def _object(properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {"type": "object", "properties": properties, "required": required, "additionalProperties": False}


_PERIOD_ONLY = _object({"period": _period_schema("Kỳ cần xem.")}, ["period"])
_NO_PARAMS = _object({}, [])


@dataclass(frozen=True, slots=True)
class ToolSpec:
    query_id: QueryId
    description: str
    parameters: dict[str, Any]
    params_model: type[_Params]

    @property
    def name(self) -> str:
        return self.query_id.value


TOOLS: tuple[ToolSpec, ...] = (
    ToolSpec(
        QueryId.Q1,
        "Doanh thu theo kỳ (FR-30): tổng tiền Đơn hàng Hoàn thành sau giảm giá, đã trừ hàng trả lại. "
        "Dùng khi hỏi doanh thu, doanh số, bán được bao nhiêu tiền.",
        _PERIOD_ONLY,
        PeriodParams,
    ),
    ToolSpec(
        QueryId.Q2,
        "Số Đơn hàng theo kỳ (FR-30). Dùng khi hỏi có bao nhiêu đơn, bao nhiêu hóa đơn.",
        _PERIOD_ONLY,
        PeriodParams,
    ),
    ToolSpec(
        QueryId.Q3,
        "Lợi nhuận gộp theo kỳ (FR-31): doanh thu trừ Giá vốn đã bán. Dùng khi hỏi lãi, lời, lợi nhuận.",
        _PERIOD_ONLY,
        PeriodParams,
    ),
    ToolSpec(
        QueryId.Q4,
        "Xếp hạng Sản phẩm bán chạy hoặc bán ít trong một kỳ (FR-32). "
        "direction = BEST_SELLING khi hỏi bán chạy, bán nhiều nhất; SLOW_SELLING khi hỏi bán ít nhất, bán chậm nhất. "
        "Không dùng cho câu hỏi về hàng tồn đọng (đó là Q7).",
        _object(
            {
                "period": _period_schema("Kỳ xếp hạng. Bỏ trống nếu người dùng không nêu kỳ."),
                "direction": {"type": "string", "enum": [d.value for d in Direction]},
                "rankBy": {
                    "type": "string",
                    "enum": [r.value for r in RankBy],
                    "description": "REVENUE khi hỏi theo doanh thu, tiền; QUANTITY (mặc định) khi theo số lượng.",
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": Q4_MAX_LIMIT,
                    "description": "Số Sản phẩm muốn xem, chỉ điền khi người dùng nêu (ví dụ 'top 5').",
                },
            },
            ["direction"],
        ),
        Q4Params,
    ),
    ToolSpec(
        QueryId.Q5,
        "Tồn kho hiện tại và giá trị Tồn kho theo Giá vốn (FR-33). Lọc theo một Sản phẩm (productName) "
        "hoặc một Danh mục (categoryName); bỏ trống cả hai để xem toàn bộ.",
        _object(
            {
                "productName": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": NAME_MAX_LENGTH,
                    "description": "Tên Sản phẩm người dùng nhắc, chép nguyên văn.",
                },
                "categoryName": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": NAME_MAX_LENGTH,
                    "description": "Tên Danh mục người dùng nhắc, chép nguyên văn.",
                },
            },
            [],
        ),
        Q5Params,
    ),
    ToolSpec(
        QueryId.Q6,
        "So sánh một chỉ số giữa hai kỳ (FR-38). metric: REVENUE = doanh thu (Q1), "
        "ORDER_COUNT = số Đơn hàng (Q2), GROSS_PROFIT = lợi nhuận gộp (Q3). "
        "period là kỳ được hỏi, comparePeriod là kỳ đem ra so.",
        _object(
            {
                "metric": {"type": "string", "enum": [m.value for m in Metric]},
                "period": _period_schema("Kỳ được hỏi, ví dụ 'tháng này'."),
                "comparePeriod": _period_schema("Kỳ đem ra so, ví dụ 'tháng trước'."),
            },
            ["metric", "period", "comparePeriod"],
        ),
        Q6Params,
    ),
    ToolSpec(
        QueryId.Q7,
        "Hàng tồn nhiều nhưng bán chậm (FR-39): Sản phẩm có Tồn kho đủ bán hơn 30 ngày theo tốc độ bán "
        "30 ngày gần nhất, hoặc không bán được trong 30 ngày. Dùng khi hỏi hàng tồn đọng, ế, đọng vốn.",
        _NO_PARAMS,
        NoParams,
    ),
    ToolSpec(
        QueryId.Q8,
        "Gợi ý nhập hàng (FR-40): Sản phẩm sắp hết (đủ bán dưới 7 ngày theo tốc độ bán 14 ngày) "
        "hoặc dưới Ngưỡng tồn tối thiểu, kèm số lượng nên nhập.",
        _NO_PARAMS,
        NoParams,
    ),
    ToolSpec(
        QueryId.Q9,
        "Sản phẩm nên ưu tiên bán, đẩy bán, xả hàng (FR-40): hàng bán chậm có giá bán cao hơn Giá vốn, "
        "sắp theo giá trị Tồn kho giảm dần.",
        _NO_PARAMS,
        NoParams,
    ),
)

TOOLS_BY_ID: dict[QueryId, ToolSpec] = {t.query_id: t for t in TOOLS}


def parse_params(query_id: QueryId | str, args: Mapping[str, Any] | None) -> _Params:
    """Kiểm tham số LLM trả về. Sai thì ném `pydantic.ValidationError`."""
    spec = TOOLS_BY_ID[QueryId(query_id)]
    return spec.params_model.model_validate(dict(args or {}))


def to_reporting_query(query_id: QueryId | str, params: _Params, today: date) -> dict[str, str | int]:
    """Query string cho `GET /api/v1/reporting/q/<Qn>`. `today` là ngày theo giờ Việt Nam."""
    qid = QueryId(query_id)
    match params:
        case PeriodParams() if qid in (QueryId.Q1, QueryId.Q2, QueryId.Q3):
            return dict(params.period.resolve(today).as_query())
        case Q4Params() if qid is QueryId.Q4:
            period = params.period or Q4_DEFAULT_PERIOD
            return {
                **period.resolve(today).as_query(),
                "direction": params.direction.value,
                "rankBy": params.rank_by.value,
                "limit": params.limit,
            }
        case Q5Params() if qid is QueryId.Q5:
            query: dict[str, str | int] = {}
            if params.product_name:
                query["productName"] = params.product_name
            if params.category_name:
                query["categoryName"] = params.category_name
            return query
        case Q6Params() if qid is QueryId.Q6:
            return {
                "metric": params.metric.value,
                **params.period.resolve(today).as_query(),
                **params.compare_period.resolve(today).as_query(prefix="compare"),
            }
        case NoParams() if qid in (QueryId.Q7, QueryId.Q8, QueryId.Q9):
            return {}
    raise TypeError(f"Tham số {type(params).__name__} không khớp {qid}")


class RouterDecision(BaseModel):
    """Kết quả lần gọi 1 ở chế độ dự phòng structured output (AD-11 mục 10)."""

    model_config = ConfigDict(extra="forbid")

    queryId: QueryId | None
    params: dict[str, Any] = Field(default_factory=dict)


def router_response_schema() -> dict[str, Any]:
    """`response_json_schema` cho chế độ dự phòng: `{queryId: "Q1"…"Q9" | null, params}`."""
    distinct: list[dict[str, Any]] = []
    for spec in TOOLS:
        if spec.parameters not in distinct:
            distinct.append(spec.parameters)
    return {
        "type": "object",
        "properties": {
            "queryId": {"anyOf": [{"type": "string", "enum": [q.value for q in QueryId]}, {"type": "null"}]},
            "params": {"anyOf": distinct},
        },
        "required": ["queryId", "params"],
        "additionalProperties": False,
    }


def gemini_tool() -> Any:
    """`types.Tool` chứa đúng 9 FunctionDeclaration Q1–Q9 (AD-11 mục 2)."""
    from google.genai import types

    declarations = [
        types.FunctionDeclaration(
            name=spec.name,
            description=spec.description,
            # Công cụ không tham số thì bỏ hẳn `parameters` (Gemini không nhận object rỗng).
            parameters_json_schema=spec.parameters if spec.parameters["properties"] else None,
        )
        for spec in TOOLS
    ]
    return types.Tool(function_declarations=declarations)
