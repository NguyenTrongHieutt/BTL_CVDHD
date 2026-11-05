"""9 công cụ Q1–Q9: schema chặt, khớp model Pydantic, quy ra tham số `reporting` đúng."""

from datetime import date
from typing import Any

import pytest
from pydantic import ValidationError

from app.qa import tools
from app.qa.tools import TOOLS, QueryId, parse_params, to_reporting_query

TODAY = date(2026, 11, 5)  # Thứ Năm

ALLOWED_KEYWORDS = {
    "type",
    "description",
    "properties",
    "required",
    "additionalProperties",
    "enum",
    "minimum",
    "maximum",
    "format",
    "minLength",
    "maxLength",
}
JSON_TYPES = {"object", "string", "integer", "number", "boolean", "array"}


def _walk(schema: dict[str, Any], path: str) -> None:
    assert set(schema) <= ALLOWED_KEYWORDS, f"{path}: từ khóa lạ {set(schema) - ALLOWED_KEYWORDS}"
    assert schema.get("type") in JSON_TYPES, f"{path}: thiếu type"
    if "enum" in schema:
        assert schema["enum"] and len(set(schema["enum"])) == len(schema["enum"]), path
        assert all(isinstance(v, str) for v in schema["enum"]), path
    if "minimum" in schema and "maximum" in schema:
        assert schema["minimum"] <= schema["maximum"], path
    if schema["type"] == "object":
        assert schema.get("additionalProperties") is False, f"{path}: object phải đóng"
        props = schema.get("properties")
        assert isinstance(props, dict), path
        required = schema.get("required", [])
        assert len(set(required)) == len(required) and set(required) <= set(props), path
        for name, sub in props.items():
            _walk(sub, f"{path}.{name}")


def test_exactly_nine_tools_named_q1_to_q9():
    assert [t.name for t in TOOLS] == [f"Q{i}" for i in range(1, 10)]
    assert all(t.description for t in TOOLS)


@pytest.mark.parametrize("spec", TOOLS, ids=lambda s: s.name)
def test_tool_schema_is_strict_and_well_formed(spec):
    _walk(spec.parameters, spec.name)


@pytest.mark.parametrize("spec", TOOLS, ids=lambda s: s.name)
def test_tool_schema_matches_pydantic_model(spec):
    model = spec.params_model
    by_alias = {f.alias or name: f for name, f in model.model_fields.items()}
    assert set(spec.parameters["properties"]) == set(by_alias)
    assert set(spec.parameters.get("required", [])) == {a for a, f in by_alias.items() if f.is_required()}


def test_period_kind_enum_matches_code():
    from app.qa.periods import PeriodKind

    schema = TOOLS[0].parameters["properties"]["period"]
    assert schema["properties"]["kind"]["enum"] == [k.value for k in PeriodKind]
    assert set(schema["properties"]) == {"kind", "days", "month", "year", "fromDate", "toDate"}


def test_gemini_declarations_build_without_network():
    tool = tools.gemini_tool()
    names = [d.name for d in tool.function_declarations]
    assert names == [f"Q{i}" for i in range(1, 10)]
    by_name = {d.name: d for d in tool.function_declarations}
    assert by_name["Q7"].parameters_json_schema is None
    assert by_name["Q6"].parameters_json_schema["required"] == ["metric", "period", "comparePeriod"]


VALID_CASES = [
    ("Q1", {"period": {"kind": "TODAY"}}, {"fromDate": "2026-11-05", "toDate": "2026-11-05"}),
    ("Q2", {"period": {"kind": "THIS_WEEK"}}, {"fromDate": "2026-11-02", "toDate": "2026-11-05"}),
    ("Q3", {"period": {"kind": "MONTH", "month": 9}}, {"fromDate": "2026-09-01", "toDate": "2026-09-30"}),
    (
        "Q4",
        {"direction": "BEST_SELLING", "limit": 5, "period": {"kind": "LAST_MONTH"}},
        {"fromDate": "2026-10-01", "toDate": "2026-10-31", "direction": "BEST_SELLING", "rankBy": "QUANTITY", "limit": 5},
    ),
    (
        "Q4",
        {"direction": "SLOW_SELLING"},
        {"fromDate": "2026-10-07", "toDate": "2026-11-05", "direction": "SLOW_SELLING", "rankBy": "QUANTITY", "limit": 10},
    ),
    ("Q5", {}, {}),
    ("Q5", {"categoryName": "Nước giải khát"}, {"categoryName": "Nước giải khát"}),
    (
        "Q6",
        {"metric": "REVENUE", "period": {"kind": "THIS_MONTH"}, "comparePeriod": {"kind": "LAST_MONTH"}},
        {
            "metric": "REVENUE",
            "fromDate": "2026-11-01",
            "toDate": "2026-11-05",
            "compareFromDate": "2026-10-01",
            "compareToDate": "2026-10-31",
        },
    ),
    ("Q7", {}, {}),
    ("Q8", None, {}),
    ("Q9", {}, {}),
]


@pytest.mark.parametrize(("qid", "args", "expected"), VALID_CASES)
def test_valid_args_map_to_reporting_query(qid, args, expected):
    params = parse_params(qid, args)
    assert to_reporting_query(qid, params, TODAY) == expected


INVALID_CASES = [
    ("Q1", {}),  # thiếu period
    ("Q1", {"period": {"kind": "TOMORROW"}}),
    ("Q1", {"period": {"kind": "TODAY"}, "extra": 1}),
    ("Q1", {"period": {"kind": "LAST_N_DAYS"}}),  # thiếu days
    ("Q1", {"period": {"kind": "TODAY", "days": 3}}),  # days không dùng với TODAY
    ("Q2", {"period": {"kind": "LAST_N_DAYS", "days": 400}}),
    ("Q3", {"period": {"kind": "DATE_RANGE", "fromDate": "2026-10-15", "toDate": "2026-10-01"}}),
    ("Q3", {"period": {"kind": "DATE_RANGE", "fromDate": "2024-01-01", "toDate": "2026-01-01"}}),
    ("Q4", {"period": {"kind": "TODAY"}}),  # thiếu direction
    ("Q4", {"direction": "BEST_SELLING", "limit": 0}),
    ("Q4", {"direction": "BEST_SELLING", "rankBy": "PROFIT"}),
    ("Q5", {"productName": "Bia", "categoryName": "Đồ uống"}),
    ("Q5", {"productName": "x" * 101}),
    ("Q6", {"metric": "Q1", "period": {"kind": "TODAY"}, "comparePeriod": {"kind": "YESTERDAY"}}),
    ("Q6", {"metric": "REVENUE", "period": {"kind": "TODAY"}}),
    ("Q7", {"limit": 5}),
]


@pytest.mark.parametrize(("qid", "args"), INVALID_CASES)
def test_invalid_args_are_rejected(qid, args):
    with pytest.raises(ValidationError):
        parse_params(qid, args)


def test_unknown_query_id_is_rejected():
    with pytest.raises(ValueError):
        parse_params("Q10", {})


def test_params_of_wrong_tool_are_rejected():
    params = parse_params(QueryId.Q7, {})
    with pytest.raises(TypeError):
        to_reporting_query(QueryId.Q1, params, TODAY)


def test_structured_fallback_schema_covers_all_param_shapes():
    schema = tools.router_response_schema()
    assert schema["required"] == ["queryId", "params"] and schema["additionalProperties"] is False
    query_ids = schema["properties"]["queryId"]["anyOf"]
    assert query_ids[0]["enum"] == [f"Q{i}" for i in range(1, 10)] and query_ids[1] == {"type": "null"}
    variants = schema["properties"]["params"]["anyOf"]
    for i, variant in enumerate(variants):
        _walk(variant, f"params[{i}]")
    assert all(spec.parameters in variants for spec in TOOLS)
    decision = tools.RouterDecision.model_validate({"queryId": None, "params": {}})
    assert decision.queryId is None


def test_router_prompt_gives_year_but_not_today():
    from app.qa.prompts import OUT_OF_SCOPE_MARKER, router_instruction

    text = router_instruction(2026)
    assert "2026" in text and OUT_OF_SCOPE_MARKER in text
    assert "2026-11" not in text
    structured = router_instruction(2026, "structured")
    assert all(f"- Q{i}:" in structured for i in range(1, 10))