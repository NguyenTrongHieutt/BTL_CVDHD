"""Hybrid search returns tenant-scoped product ids and fused rank scores."""

import uuid
from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient

from app import db, embedding
from app.main import create_app
from app.settings import Settings
from tests.conftest import STORE_ID, TEST_JWT_SECRET

PRODUCT_ID = uuid.UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


class FakeCursor:
    def fetchall(self):
        return [(PRODUCT_ID, 0.875)]


class FakeConnection:
    def __init__(self):
        self.calls: list[tuple[str, dict[str, object]]] = []

    def execute(self, sql, params):
        self.calls.append((sql, params))
        return FakeCursor()


def test_hybrid_search_returns_ranked_ids_and_fused_scores(client, make_token, monkeypatch):
    conn = FakeConnection()
    transaction: dict[str, uuid.UUID] = {}
    embedded: list[str] = []

    @contextmanager
    def store_transaction(store_id: uuid.UUID):
        transaction["store_id"] = store_id
        yield conn

    monkeypatch.setattr(db, "store_transaction", store_transaction)
    monkeypatch.setattr(embedding, "embed_query", lambda query: embedded.append(query) or [0.1] * 384)
    monkeypatch.setattr(embedding, "model_name", lambda: "test-model")
    monkeypatch.setattr("app.search.router.search_catalog_products", lambda **kwargs: [str(PRODUCT_ID)])

    response = client.get(
        "/ai/search",
        params={"q": "sữa tươi", "limit": 7},
        headers={"Authorization": f"Bearer {make_token(role='STAFF')}"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "items": [{"productId": str(PRODUCT_ID), "score": pytest.approx(1 / 61 + 1 / 61)}]
    }
    assert embedded == ["sữa tươi"]
    assert transaction == {"store_id": STORE_ID}
    sql, params = conn.calls[0]
    assert "embedding <=> %(vector)s" in sql
    assert "store_id = %(store_id)s" in sql
    assert params == {
        "store_id": STORE_ID,
        "model_version": "test-model",
        "vector": [0.1] * 384,
        "limit": 50,
    }


def test_semantic_search_validates_query_and_limit(client, make_token, monkeypatch):
    monkeypatch.setattr(embedding, "embed_query", lambda query: (_ for _ in ()).throw(AssertionError(query)))
    headers = {"Authorization": f"Bearer {make_token()}"}

    empty_query = client.get("/ai/search", params={"q": ""}, headers=headers)
    blank_query = client.get("/ai/search", params={"q": "   "}, headers=headers)
    excessive_limit = client.get("/ai/search", params={"q": "sữa", "limit": 51}, headers=headers)

    assert empty_query.status_code == 400
    assert blank_query.status_code == 400
    assert excessive_limit.status_code == 400


def test_model_is_preloaded_before_serving_when_sync_is_enabled(monkeypatch):
    preloads: list[bool] = []
    monkeypatch.setattr(embedding, "get_model", lambda: preloads.append(True))
    monkeypatch.setattr("app.main.SyncRunner.start", lambda self: None)
    settings = Settings(
        jwt_secret=TEST_JWT_SECRET,  # type: ignore[arg-type]
        sync_enabled=True,
        internal_service_key="test-key",  # type: ignore[arg-type]
        preload_embedding_model=True,
    )

    with TestClient(create_app(settings)):
        assert preloads == [True]
