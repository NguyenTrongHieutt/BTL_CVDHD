"""`POST /ai/ask`: Hỏi đáp dữ liệu. GĐ0 mới có khung, trả 501 Problem Details đến khi làm AI-03.

TODO(AI-03…AI-08): hiện thực đường ống theo AD-11 (tối đa 2 lần gọi LLM, tổng ≤ 10 giây):

 0. `require_owner` đã chạy: Nhân viên nhận 403 TRƯỚC khi đụng Hạn mức (AD-11 mục 1).
 1. Giữ chỗ Hạn mức: `quota.reserve(conn, store_id, day)` trong `db.store_transaction`.
    Hết lượt → 429 `/errors/ai/quota-exceeded` (AD-13). Nhớ `day` để hoàn đúng ngày.
 2. Câu hỏi gợi ý nhanh (FR-41) ánh xạ thẳng sang Qn với tham số cố định trong code,
    bỏ qua bước 3 (AD-11 mục 8).
 3. Lần gọi LLM 1: Gemini (`GEMINI_MODEL`) với `prompts.router_instruction(năm)` và
    `tools.gemini_tool()` (đúng 9 công cụ Q1–Q9, không công cụ ghi). Không có function
    call → `refused=true` (ngoài phạm vi, FR-37). Dự phòng nếu function calling không đạt
    ở GĐ0: structured output `{queryId, params}` (AD-11 mục 10; xem SPIKES.md).
 4. `tools.parse_params` kiểm tham số bằng Pydantic; `tools.to_reporting_query` quy kỳ
    ra `fromDate`/`toDate` theo giờ Việt Nam (AD-19). Sai tham số → coi như lỗi lần gọi 1.
 5. `ReportingClient.query(Qn, params, token=user.token)`; phản hồi luôn có `range`.
 6. Lỗi/quá giờ ở bước 3–5 (chưa có kết quả nào) → `quota.refund` rồi trả lỗi (AD-13).
 7. Lần gọi LLM 2: viết lời dẫn tiếng Việt (kết luận trước, số sau). Chỉ gửi số liệu tổng
    hợp; KHÔNG gửi tên, số điện thoại Khách hàng (AD-11 mục 6, PRD 7.3).
 8. Code dựng `AskResponse`: bảng, biểu đồ, `interpretation` (Qn + khoảng ngày + loại số
    liệu), `reportLink`, `disclaimer`. Kiểm mọi con số trong lời dẫn (bỏ dấu phân cách và
    đơn vị, so với kết quả truy vấn hoặc giá trị code tự tính); lệch → bỏ lời dẫn, chỉ trả
    bảng. Lần gọi 2 lỗi/quá giờ → trả bảng không lời dẫn, VẪN tính lượt (AD-11 mục 9).
 9. Ghi `ai.ai_query_log`: câu hỏi, Qn và tham số đã dùng, câu trả lời, `refused`, thời
    gian chạy, lỗi nếu có (AD-11 mục 7).
"""

from fastapi import APIRouter

from app.auth import OwnerDep
from app.problems import not_implemented
from app.qa.schemas import AskRequest, AskResponse

router = APIRouter(tags=["qa"])


@router.post(
    "/ask",
    response_model=AskResponse,
    responses={501: {"description": "Chưa làm (AI-03)", "content": {"application/problem+json": {}}}},
)
def ask(body: AskRequest, user: OwnerDep) -> AskResponse:
    raise not_implemented("Hỏi đáp dữ liệu sẽ có ở AI-03")
