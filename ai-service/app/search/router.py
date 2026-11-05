"""`GET /ai/search?q=&limit=`: GĐ0 mới có khung, trả 501 Problem Details đến khi làm AI-01.

Ai dùng được: Chủ cửa hàng và Nhân viên (PRD 4.7). Không trừ Hạn mức AI (AD-13).

TODO(AI-01): hiện thực theo AD-12.
    1. `vector = embedding.embed_query(q)` (thêm "query: ", đã chuẩn hóa). Hàm này chặn CPU,
       nên endpoint để `def` (FastAPI chạy trong threadpool).
    2. Trong `db.store_transaction(user.store_id)` (đã `set_config('app.store_id', …, true)`):

           select product_id, 1 - (embedding <=> %(vector)s) as score
           from ai.product_embedding
           where store_id = %(store_id)s          -- RLS đã lọc; vẫn ghi rõ cho index
             and model_version = %(model_version)s
           order by embedding <=> %(vector)s
           limit %(limit)s

       `<=>` là cosine distance, nên `score = 1 - distance`. Chưa cần HNSW (Deferred).
    3. Trả `{"items": [{"productId": …, "score": …}]}`: CHỈ `productId` và `score`.
       Tên, giá, Tồn kho frontend lấy từ `GET /api/v1/sales/sellable?ids=`.
    4. Sản phẩm INACTIVE đã bị job đồng bộ xóa embedding, nên không cần lọc thêm.
    5. Frontend chờ tối đa 1,5 giây rồi chuyển sang Tìm kiếm thường (AD-12), nên giữ
       truy vấn nhanh; model phải nạp sẵn (job đồng bộ nạp khi khởi động).
"""

from typing import Annotated

from fastapi import APIRouter, Query
from pydantic import BaseModel

from app.auth import CurrentUserDep
from app.problems import not_implemented

router = APIRouter(tags=["search"])

MAX_QUERY_LENGTH = 200
DEFAULT_LIMIT = 10
MAX_LIMIT = 50


class SearchHit(BaseModel):
    productId: str
    score: float


class SearchResponse(BaseModel):
    items: list[SearchHit]


@router.get(
    "/search",
    response_model=SearchResponse,
    responses={501: {"description": "Chưa làm (AI-01)", "content": {"application/problem+json": {}}}},
)
def search(
    user: CurrentUserDep,
    q: Annotated[str, Query(min_length=1, max_length=MAX_QUERY_LENGTH)],
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = DEFAULT_LIMIT,
) -> SearchResponse:
    raise not_implemented("Tìm kiếm ngữ nghĩa sẽ có ở AI-01")
