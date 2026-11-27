"""`GET /ai/search?q=&limit=`: hybrid search kết hợp vector và tìm kiếm lexical (AI-01).

Ai dùng được: Chủ cửa hàng và Nhân viên (PRD 4.7). Không trừ Hạn mức AI (AD-13).

Luồng tìm kiếm:
    1. `vector = embedding.embed_query(q)` đã chuẩn hóa. Hàm này chặn CPU,
       nên endpoint để `def` (FastAPI chạy trong threadpool).
    2. Tìm vector trong `db.store_transaction(user.store_id)` (đã set_config cho RLS).
    3. Gọi API tìm kiếm catalog bằng JWT gốc để lấy thứ hạng lexical.
    4. Hợp nhất thứ hạng bằng Reciprocal Rank Fusion, không dùng ngưỡng cosine cố định.
       `score` trong phản hồi là điểm RRF, không phải cosine similarity.

    5. Trả `{"items": [{"productId": …, "score": …}]}`: CHỈ `productId` và `score`.
       Tên, giá, Tồn kho frontend lấy từ `GET /api/v1/sales/sellable?ids=`.
    6. Catalog lexical chỉ trả Sản phẩm ACTIVE; model được nạp trước khi app nhận request.
"""

from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Query, Request
from pydantic import BaseModel

from app import db, embedding
from app.auth import CurrentUserDep
from app.problems import ProblemException, TYPE_VALIDATION
from app.search.hybrid import fuse_rankings, search_catalog_products

router = APIRouter(tags=["search"])

MAX_QUERY_LENGTH = 200
DEFAULT_LIMIT = 10
MAX_LIMIT = 50
SEMANTIC_CANDIDATE_LIMIT = 50
SEARCH_SQL = """\
select product_id, 1 - (embedding <=> %(vector)s) as score
from ai.product_embedding
where store_id = %(store_id)s
  and model_version = %(model_version)s
order by embedding <=> %(vector)s
limit %(limit)s
"""
ALTERNATIVES_SQL = """\
select candidates.product_id, 1 - (candidates.embedding <=> origin.embedding) as score
from ai.product_embedding origin
cross join lateral (
    select product_id, embedding
    from ai.product_embedding
    where store_id = %(store_id)s
      and model_version = %(model_version)s
      and product_id <> %(product_id)s
    order by embedding <=> origin.embedding
    limit %(limit)s
) candidates
where origin.store_id = %(store_id)s and origin.model_version = %(model_version)s
  and origin.product_id = %(product_id)s
  and 1 - (candidates.embedding <=> origin.embedding) >= 0.35
order by score desc, candidates.product_id
"""


class SearchHit(BaseModel):
    productId: str
    score: float


class SearchResponse(BaseModel):
    items: list[SearchHit]


@router.get(
    "/search",
    response_model=SearchResponse,
)
def search(
    request: Request,
    user: CurrentUserDep,
    q: Annotated[str, Query(min_length=1, max_length=MAX_QUERY_LENGTH)],
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = DEFAULT_LIMIT,
) -> SearchResponse:
    query = q.strip()
    if not query:
        raise ProblemException(
            400,
            TYPE_VALIDATION,
            "Dữ liệu nhập không hợp lệ",
            "Kiểm tra lại các trường trong errors[]",
            errors=[{"field": "q", "code": "string_too_short", "message": "Từ khóa không được để trống."}],
        )
    vector = embedding.embed_query(query)
    with db.store_transaction(user.store_id) as conn:
        rows = conn.execute(
            SEARCH_SQL,
            {
                "store_id": user.store_id,
                "model_version": embedding.model_name(),
                "vector": vector,
                "limit": SEMANTIC_CANDIDATE_LIMIT,
            },
        ).fetchall()
    semantic_ids = [str(product_id) for product_id, _ in rows]
    lexical_ids = search_catalog_products(
        base_url=request.app.state.settings.backend_url,
        query=query,
        token=user.token,
        request_id=request.headers.get("X-Request-Id"),
    )
    fused = fuse_rankings(semantic_ids, lexical_ids, limit)
    return SearchResponse(items=[SearchHit(productId=product_id, score=score) for product_id, score in fused])


@router.get("/search/alternatives", response_model=SearchResponse)
def alternatives(
    user: CurrentUserDep,
    productId: UUID,
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = MAX_LIMIT,
) -> SearchResponse:
    """Xếp ứng viên gần sản phẩm hết hàng, tồn kho được kiểm khi lấy Biến thể ở sales."""
    with db.store_transaction(user.store_id) as conn:
        rows = conn.execute(
            ALTERNATIVES_SQL,
            {"store_id": user.store_id, "model_version": embedding.model_name(),
             "product_id": productId, "limit": limit},
        ).fetchall()
    return SearchResponse(items=[SearchHit(productId=str(product_id), score=score) for product_id, score in rows])
