"""Backend lexical retrieval and reciprocal-rank fusion for hybrid product search."""

import logging
import unicodedata
import uuid

import httpx
from pydantic import BaseModel, ConfigDict, Field

from app.problems import ProblemException, TYPE_AI_UNAVAILABLE

logger = logging.getLogger("app.search.hybrid")

CATALOG_SEARCH_PATH = "/api/v1/catalog/products"
LEXICAL_PAGE_SIZE = 100
RRF_K = 60
LEXICAL_TIMEOUT_SECONDS = 0.8


class _CatalogVariant(BaseModel):
    model_config = ConfigDict(extra="ignore")

    sku: str | None = None
    barcode: str | None = None


class _CatalogProduct(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: uuid.UUID
    name: str
    variants: list[_CatalogVariant] = Field(default_factory=list)


class _CatalogPage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[_CatalogProduct]


def search_catalog_products(
    *,
    base_url: str,
    query: str,
    token: str,
    request_id: str | None = None,
) -> list[str]:
    """Get active lexical candidates from catalog using the caller's access token."""
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    if request_id:
        headers["X-Request-Id"] = request_id

    try:
        response = httpx.get(
            f"{base_url}{CATALOG_SEARCH_PATH}",
            params={
                "q": query,
                "status": "ACTIVE",
                "page": 0,
                "size": LEXICAL_PAGE_SIZE,
                "sort": "name,asc",
            },
            headers=headers,
            timeout=LEXICAL_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        logger.warning("Catalog lexical search returned HTTP %d", exc.response.status_code)
        raise ProblemException(
            503, TYPE_AI_UNAVAILABLE, "Dịch vụ tìm kiếm chưa sẵn sàng",
            "Không gọi được dịch vụ catalog để hoàn thành tìm kiếm kết hợp.",
        ) from exc
    except httpx.RequestError as exc:
        logger.warning("Catalog lexical search failed: %s", type(exc).__name__)
        raise ProblemException(
            503, TYPE_AI_UNAVAILABLE, "Dịch vụ tìm kiếm chưa sẵn sàng",
            "Không gọi được dịch vụ catalog để hoàn thành tìm kiếm kết hợp.",
        ) from exc

    try:
        page = _CatalogPage.model_validate(response.json())
    except ValueError as exc:
        logger.warning("Catalog lexical search returned an invalid response")
        raise ProblemException(
            503, TYPE_AI_UNAVAILABLE, "Dịch vụ tìm kiếm chưa sẵn sàng",
            "Dịch vụ catalog trả về phản hồi tìm kiếm không hợp lệ.",
        ) from exc
    ordered = sorted(page.items, key=lambda item: (-_lexical_score(query, item), _fold(item.name), str(item.id)))
    return list(dict.fromkeys(str(item.id) for item in ordered))


def _fold(value: str) -> str:
    value = value.replace("đ", "d").replace("Đ", "D")
    return " ".join(
        "".join(char for char in unicodedata.normalize("NFD", value.casefold()) if unicodedata.category(char) != "Mn")
        .split()
    )


def _lexical_score(query: str, product: _CatalogProduct) -> int:
    folded_query = _fold(query)
    folded_name = _fold(product.name)
    if folded_query == folded_name:
        return 5
    if folded_name.startswith(folded_query):
        return 4
    if folded_query in folded_name:
        return 3
    if any(
        _fold(code).startswith(folded_query)
        for variant in product.variants
        for code in (variant.sku, variant.barcode)
        if code
    ):
        return 2
    return 1


def fuse_rankings(
    semantic_ids: list[str],
    lexical_ids: list[str],
    limit: int,
    *,
    rrf_k: int = RRF_K,
) -> list[tuple[str, float]]:
    """Combine two ordered candidate lists; rank 1 contributes 1 / (k + 1)."""
    scores: dict[str, float] = {}
    for ranking in (semantic_ids, lexical_ids):
        for rank, product_id in enumerate(dict.fromkeys(ranking), start=1):
            scores[product_id] = scores.get(product_id, 0.0) + 1 / (rrf_k + rank)
    return sorted(scores.items(), key=lambda item: (-item[1], item[0]))[:limit]
