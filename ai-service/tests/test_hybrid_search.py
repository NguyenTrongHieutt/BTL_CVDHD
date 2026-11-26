import uuid

import httpx
import pytest

from app.problems import ProblemException
from app.search import hybrid

PRODUCT_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
PRODUCT_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
PRODUCT_3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"


def test_search_catalog_products_forwards_auth_and_uses_active_catalog_search(monkeypatch):
    seen: dict[str, object] = {}

    def fake_get(url, *, params, headers, timeout):
        seen.update(url=url, params=params, headers=headers, timeout=timeout)
        return httpx.Response(
            200,
            request=httpx.Request("GET", url),
            json={
                "items": [
                    {"id": PRODUCT_2, "name": "Sữa tươi không đường", "status": "ACTIVE"},
                    {"id": PRODUCT_2, "name": "Sữa tươi không đường", "status": "ACTIVE"},
                    {"id": PRODUCT_3, "name": "Sữa tươi", "status": "ACTIVE"},
                ],
                "total": 2,
                "page": 0,
                "size": 100,
            },
        )

    monkeypatch.setattr(hybrid.httpx, "get", fake_get)

    ids = hybrid.search_catalog_products(
        base_url="http://backend",
        query="sữa tươi",
        token="user-token",
        request_id="request-1",
    )

    assert ids == [PRODUCT_3, PRODUCT_2]
    assert seen == {
        "url": "http://backend/api/v1/catalog/products",
        "params": {"q": "sữa tươi", "status": "ACTIVE", "page": 0, "size": 100, "sort": "name,asc"},
        "headers": {
            "Authorization": "Bearer user-token",
            "Accept": "application/json",
            "X-Request-Id": "request-1",
        },
        "timeout": hybrid.LEXICAL_TIMEOUT_SECONDS,
    }


def test_catalog_failure_is_reported_as_ai_unavailable(monkeypatch):
    def failed_get(*args, **kwargs):
        return httpx.Response(503, request=httpx.Request("GET", "http://backend"))

    monkeypatch.setattr(hybrid.httpx, "get", failed_get)

    with pytest.raises(ProblemException) as exc_info:
        hybrid.search_catalog_products(base_url="http://backend", query="sữa", token="user-token")

    assert exc_info.value.status == 503


def test_reciprocal_rank_fusion_combines_both_rankings_and_deduplicates():
    fused = hybrid.fuse_rankings(
        [PRODUCT_1, PRODUCT_2, PRODUCT_1],
        [PRODUCT_2, PRODUCT_3, PRODUCT_3],
        limit=3,
    )

    assert [product_id for product_id, _ in fused] == [PRODUCT_2, PRODUCT_1, PRODUCT_3]
    assert fused[0][1] == pytest.approx(1 / 61 + 1 / 62)


def test_reciprocal_rank_fusion_handles_empty_sources_and_limit():
    assert hybrid.fuse_rankings([], [], limit=10) == []
    assert hybrid.fuse_rankings([PRODUCT_1, PRODUCT_2], [], limit=1) == [
        (PRODUCT_1, pytest.approx(1 / 61))
    ]


def test_catalog_lexical_rank_prefers_exact_accent_insensitive_name_match():
    exact = hybrid._CatalogProduct(id=uuid.UUID(PRODUCT_1), name="Sữa tươi")
    prefix = hybrid._CatalogProduct(id=uuid.UUID(PRODUCT_2), name="Sữa tươi không đường")
    code = hybrid._CatalogProduct(
        id=uuid.UUID(PRODUCT_3),
        name="Nước đóng chai",
        variants=[{"sku": "SUA-01"}],
    )
    irrelevant = hybrid._CatalogProduct(id=uuid.UUID(PRODUCT_2), name="Sữa chua")

    assert hybrid._lexical_score("sua tuoi", exact) > hybrid._lexical_score("sữa tươi", prefix)
    assert hybrid._lexical_score("sua-0", code) > hybrid._lexical_score("sua-0", irrelevant)
