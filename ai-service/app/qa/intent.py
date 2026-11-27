"""Hiểu các câu hỏi báo cáo tiếng Việt bằng quy tắc, không gửi dữ liệu ra ngoài.

Chỉ chọn một trong chín công cụ đọc đã khai báo. Ngày và tham số vẫn đi qua
Pydantic; câu hỏi mơ hồ được yêu cầu viết lại thay vì tự suy đoán bộ lọc.
"""

import re
import unicodedata
from dataclasses import dataclass
from datetime import date, timedelta

from app.qa.periods import Period, PeriodKind
from app.qa.tools import Metric, QueryId, _Params, parse_params

HELP = (
    "Tôi hỗ trợ doanh thu, số đơn, lợi nhuận gộp, bán chạy/bán chậm, tồn kho, "
    "so sánh hai kỳ và gợi ý nhập hoặc ưu tiên bán hàng trong cửa hàng của bạn. "
    "Hãy hỏi một nội dung mỗi lần, ví dụ: Doanh thu tháng này?"
)
_METRIC_FILTER = r"\b(?:danh muc|san pham|mat hang|nhan vien|chi nhanh|nha cung cap|theo gio|ca (?:sang|chieu|toi))\b"


@dataclass(frozen=True, slots=True)
class IntentDecision:
    query_id: QueryId | None
    params: _Params | None
    reason: str = ""
    defaulted_period: bool = False


def _fold(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", text.lower().replace("đ", "d")) if not unicodedata.combining(c))


def _blocked(text: str) -> bool:
    patterns = (
        r"\b(?:khach hang|so dien thoai|email|dia chi|mat khau|password|token|jwt|api key|system prompt)\b",
        r"\b(?:bo qua (?:cac |moi )?(?:huong dan|quy tac)|ignore|instructions|sql|select|drop|delete|truncate|insert|update)\b",
        r"\b(?:store[_ -]?id|tenant|cua hang khac|cua hang cua (?!toi\b)|tat ca (?:cac )?cua hang|toan bo cua hang|toan (?:bo )?he thong)\b",
        r"\b(?:xoa|cap nhat|thay doi|huy)\b.*\b(?:don|kho|san pham|gia|hoa don)\b",
        r"^(?:(?:hay|vui long|giup toi|cho toi)\s+)*(?:sua|tao|them|ghi|nhap)\b",
        r"\b\d{8,}\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}",
    )
    if any(re.search(pattern, text) for pattern in patterns):
        return True
    # A store name/id cannot select another tenant. Do not silently ignore such a selector.
    return bool(re.search(r"\bcua hang\s+(?!(?:cua toi|hien tai|nay|hom|tuan|thang|nam|ban|co|duoc|doanh thu|loi nhuan|la bao nhieu)\b)[a-z0-9]", text))


def _date_range(start: date, end: date) -> Period:
    return Period(kind=PeriodKind.DATE_RANGE, from_date=start, to_date=end)


def _month_end(year: int, month: int) -> date:
    return date(year + (month == 12), month % 12 + 1, 1) - timedelta(days=1)


def _periods(text: str, today: date) -> tuple[list[Period], bool]:
    matches: list[tuple[int, int, Period]] = []

    def add(start: int, end: int, period: Period) -> None:
        if not any(start < prior_end and end > prior_start for prior_start, prior_end, _ in matches):
            period.resolve(today)
            matches.append((start, end, period))

    date_pattern = r"(?<!\d)(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[/-]\d{1,2}(?:[/-]\d{4})?)(?!\d)"
    explicit = list(re.finditer(date_pattern, text))
    if len(explicit) % 2:
        raise ValueError("Cần nêu cả ngày bắt đầu và ngày kết thúc.")
    for index in range(0, len(explicit), 2):
        first, last = explicit[index:index + 2]

        def parsed(value: str) -> date:
            parts = [int(part) for part in re.split("[/-]", value)]
            if len(parts) == 3 and parts[0] >= 2000:
                return date(*parts)
            day, month, *year = parts
            return date(year[0] if year else today.year, month, day)

        add(first.start(), last.end(), _date_range(parsed(first.group()), parsed(last.group())))

    relatives = (
        (r"\bhom nay\b", PeriodKind.TODAY), (r"\bhom qua\b", PeriodKind.YESTERDAY),
        (r"\b(?:tuan nay|trong tuan(?!\s+(?:truoc|roi|vua|nay|\d)))\b", PeriodKind.THIS_WEEK),
        (r"\btuan (?:truoc|roi|vua roi)\b", PeriodKind.LAST_WEEK),
        (r"\bthang nay\b", PeriodKind.THIS_MONTH), (r"\bthang (?:truoc|roi|vua roi)\b", PeriodKind.LAST_MONTH),
        (r"\bnam nay\b", PeriodKind.THIS_YEAR), (r"\bnam (?:ngoai|truoc|vua roi)\b", PeriodKind.LAST_YEAR),
    )
    for pattern, kind in relatives:
        for match in re.finditer(pattern, text):
            add(*match.span(), Period(kind=kind))

    for match in re.finditer(r"\b(\d+)\s+ngay\s+(?:qua|gan day|gan nhat|vua qua)\b", text):
        add(*match.span(), Period(kind=PeriodKind.LAST_N_DAYS, days=int(match[1])))
    for match in re.finditer(r"\bquy\s+(nay|truoc|[1-4])(?:\s+nam\s+(20\d{2}))?\b", text):
        current_quarter = (today.month - 1) // 3 + 1
        year = int(match[2]) if match[2] else today.year
        quarter = current_quarter if match[1] == "nay" else current_quarter - 1 if match[1] == "truoc" else int(match[1])
        if quarter == 0:
            year, quarter = year - 1, 4
        month = (quarter - 1) * 3 + 1
        end = today if match[1] == "nay" else _month_end(year, month + 2)
        add(*match.span(), _date_range(date(year, month, 1), end))
    for match in re.finditer(r"\bthang\s+(\d{1,2})(?:\s*(?:/|-|nam\s+)\s*(20\d{2}))?\b", text):
        add(*match.span(), Period(kind=PeriodKind.MONTH, month=int(match[1]), year=int(match[2]) if match[2] else None))
    for match in re.finditer(r"\bnam\s+(20\d{2})\b", text):
        year = int(match[1])
        add(*match.span(), _date_range(date(year, 1, 1), date(year, 12, 31)))

    remainder = list(text)
    for start, end, _ in matches:
        remainder[start:end] = " " * (end - start)
    leftover = re.sub(r"\btheo ngay\b", "", "".join(remainder))
    unsupported_time = bool(re.search(r"\b(?:hom|ngay|tuan|thang|nam|quy|gio|phut|buoi|tu khi|cung ky|ca (?:sang|chieu|toi))\b", leftover))
    return [period for _, _, period in sorted(matches, key=lambda match: match[0])], unsupported_time


def _metric(text: str) -> Metric | None:
    found: list[Metric] = []
    if re.search(r"\b(?:doanh thu|doanh so|bao nhieu tien|tong tien ban)\b", text):
        found.append(Metric.REVENUE)
    if re.search(r"\b(?:don hang|hoa don|so don|bao nhieu don|don (?:da |bi )?huy|don (?:da )?tra het)\b", text):
        found.append(Metric.ORDER_COUNT)
    if re.search(r"\b(?:loi nhuan|lai|loi)\b", text):
        found.append(Metric.GROSS_PROFIT)
    return found[0] if len(found) == 1 else None


def _choose(query_id: QueryId, args: dict, today: date, *, defaulted: bool = False) -> IntentDecision:
    params = parse_params(query_id, args)
    # Exercise the same converter used at the reporting boundary before reserving work there.
    from app.qa.tools import to_reporting_query

    to_reporting_query(query_id, params, today)
    return IntentDecision(query_id, params, defaulted_period=defaulted)


def interpret(question: str, today: date) -> IntentDecision:
    original = unicodedata.normalize("NFC", question).strip()
    text = _fold(original)
    if _blocked(text):
        return IntentDecision(None, None, "Tôi chỉ đọc số liệu báo cáo của cửa hàng bạn; không xử lý thông tin cá nhân, yêu cầu sửa dữ liệu hay truy cập cửa hàng khác.")
    if re.search(r"\b(?:loi nhuan|lai|loi) (?:rong|sau thue|sau chi phi)\b", text):
        return IntentDecision(None, None, "Báo cáo hiện cung cấp lợi nhuận gộp, tính bằng doanh thu thuần trừ giá vốn hàng bán.")
    try:
        periods, unsupported_time = _periods(text, today)
        if unsupported_time:
            return IntentDecision(None, None, "Chưa xác định được kỳ báo cáo. Hãy nêu hôm nay, tuần/tháng/năm này hoặc một khoảng ngày cụ thể (tối đa 366 ngày).")
        comparison = bool(re.search(r"\b(?:so sanh|so voi|chenh lech|tang giam)\b|\bvoi\b.*\b(?:thang|tuan|nam|hom)\b", text))
        if comparison:
            metric = _metric(text)
            if metric is None or len(periods) != 2 or re.search(_METRIC_FILTER, text):
                return IntentDecision(None, None, "Để so sánh, hãy nêu một chỉ số và đúng hai kỳ, ví dụ: So sánh doanh thu tháng này với tháng trước.")
            return _choose(QueryId.Q6, {"metric": metric, "period": periods[0], "comparePeriod": periods[1]}, today)

        query_id: QueryId | None = None
        if re.search(r"\b(?:uu tien ban|day ban|xa hang)\b", text):
            query_id = QueryId.Q9
        elif re.search(r"\b(?:goi y nhap|can nhap|nen nhap|nhap them|sap het|can bo sung|duoi (?:nguong|muc) ton)\b", text):
            query_id = QueryId.Q8
        elif re.search(r"\b(?:ton nhieu|ton dong|ton (?:kho )?.*ban cham|dong von|e)\b", text):
            query_id = QueryId.Q7
        if query_id:
            if _metric(text) is not None:
                return IntentDecision(None, None, HELP)
            if periods or "danh muc" in text or re.search(r"\bsan pham\s+(?:ten|co ten)\b", text):
                return IntentDecision(None, None, "Gợi ý này dùng toàn bộ tồn kho hiện tại và tốc độ bán gần đây của cửa hàng. Chưa hỗ trợ lọc kỳ hoặc danh mục cho câu hỏi này.")
            return _choose(query_id, {}, today)

        ranking = re.search(r"\b(?:ban chay|ban nhieu nhat|ban it|ban cham|top\s*\d+)\b", text)
        if ranking:
            if _metric(text) is Metric.GROSS_PROFIT:
                return IntentDecision(None, None, "Xếp hạng chỉ hỗ trợ số lượng bán ròng hoặc doanh thu thuần; chưa hỗ trợ xếp hạng theo lợi nhuận.")
            if len(periods) > 1 or re.search(r"\b(?:danh muc|thuoc nhom|nhom hang)\b", text):
                return IntentDecision(None, None, "Hãy hỏi xếp hạng sản phẩm của toàn cửa hàng trong một kỳ; chưa hỗ trợ bộ lọc danh mục qua trợ lý.")
            limit_match = re.search(r"\btop\s*(\d+)\b|\b(\d+)\s+(?:san pham|mat hang)\b", text)
            limit = int(next(value for value in limit_match.groups() if value)) if limit_match else 10
            return _choose(QueryId.Q4, {
                "direction": "SLOW_SELLING" if re.search(r"\bban (?:it|cham)\b", text) else "BEST_SELLING",
                "rankBy": "REVENUE" if re.search(r"\b(?:doanh thu|theo tien)\b", text) else "QUANTITY",
                "limit": limit,
                **({"period": periods[0]} if periods else {}),
            }, today)

        if re.search(r"\b(?:ton kho|hang ton|gia tri ton|con bao nhieu)\b", text):
            if _metric(text) is not None:
                return IntentDecision(None, None, HELP)
            if periods:
                return IntentDecision(None, None, "Báo cáo tồn kho phản ánh thời điểm hiện tại. Hãy hỏi tồn kho hiện tại hoặc lọc theo tên sản phẩm/danh mục.")
            args: dict[str, str] = {}
            category = re.search(r"\b(?:danh muc|nhom hang)\s+", text)
            product = re.search(r"\b(?:san pham|mat hang)\s+", text)
            target = category or product
            if target:
                value = original[target.end():].strip(" \t\r\n?.,:;\"'“”")
                # Natural trailing question words are not part of the catalog name.
                value = re.sub(r"\s+(?:còn bao nhiêu|con bao nhieu|hiện tại|hien tai|là bao nhiêu|la bao nhieu)$", "", value, flags=re.IGNORECASE).strip()
                if _fold(value) not in {"", "nao", "gi", "hien tai"}:
                    args["categoryName" if category else "productName"] = value
            return _choose(QueryId.Q5, args, today)

        metric = _metric(text)
        if metric and len(periods) <= 1 and not re.search(_METRIC_FILTER, text):
            query_id = {Metric.REVENUE: QueryId.Q1, Metric.ORDER_COUNT: QueryId.Q2, Metric.GROSS_PROFIT: QueryId.Q3}[metric]
            period = periods[0] if periods else Period(kind=PeriodKind.THIS_MONTH)
            return _choose(query_id, {"period": period}, today, defaulted=not periods)
    except (ValueError, OverflowError):
        return IntentDecision(None, None, "Tham số chưa hợp lệ. Hãy kiểm tra ngày bắt đầu/kết thúc (tối đa 366 ngày), tháng và số sản phẩm muốn xem (từ 1 đến 50).")
    return IntentDecision(None, None, HELP)
