"""`app.embedding` với model giả: tiền tố E5, chuẩn hóa, nạp lười. Không tải model thật."""

import numpy as np
import pytest

from app import embedding


class FakeModel:
    """Trả vector KHÔNG chuẩn hóa, để chắc chắn code tự chuẩn hóa."""

    def __init__(self, dim: int = embedding.EMBEDDING_DIM) -> None:
        self.dim = dim
        self.calls: list[list[str]] = []

    def encode(self, sentences, **kwargs):
        self.calls.append(list(sentences))
        rng = np.random.default_rng(len(self.calls))
        return rng.uniform(1.0, 5.0, size=(len(sentences), self.dim)).astype(np.float32)


@pytest.fixture
def fake_model(monkeypatch) -> FakeModel:
    model = FakeModel()
    loads: list[str] = []

    def fake_load(name: str) -> FakeModel:
        loads.append(name)
        return model

    monkeypatch.setattr(embedding, "_load_model", fake_load)
    model.loads = loads  # type: ignore[attr-defined]
    return model


def test_model_is_loaded_lazily_and_once(fake_model):
    assert not embedding.is_loaded()
    embedding.embed_query("a")
    embedding.embed_passage(["b", "c"])
    assert embedding.is_loaded()
    assert fake_model.loads == ["intfloat/multilingual-e5-small"]


def test_embed_passage_adds_passage_prefix(fake_model):
    embedding.embed_passage(["Nước ngọt Coca-Cola lon 330ml", "Dầu gội Clear"])
    assert fake_model.calls == [["passage: Nước ngọt Coca-Cola lon 330ml", "passage: Dầu gội Clear"]]


def test_embed_query_adds_query_prefix(fake_model):
    embedding.embed_query("nước giải khát có ga")
    assert fake_model.calls == [["query: nước giải khát có ga"]]


def test_vectors_are_normalized_with_384_dims(fake_model):
    passages = embedding.embed_passage(["a", "b", "c"])
    query = embedding.embed_query("q")
    assert passages.shape == (3, 384)
    assert query.shape == (384,)
    assert passages.dtype == np.float32
    np.testing.assert_allclose(np.linalg.norm(passages, axis=1), 1.0, rtol=1e-5)
    assert np.linalg.norm(query) == pytest.approx(1.0, rel=1e-5)


def test_empty_passage_list_does_not_load_model(fake_model):
    result = embedding.embed_passage([])
    assert result.shape == (0, 384)
    assert not embedding.is_loaded()


def test_passage_rejects_bare_string(fake_model):
    with pytest.raises(TypeError):
        embedding.embed_passage("một chuỗi")  # type: ignore[arg-type]


def test_wrong_dimension_is_rejected(monkeypatch):
    monkeypatch.setattr(embedding, "_load_model", lambda name: FakeModel(dim=768))
    with pytest.raises(ValueError, match="384"):
        embedding.embed_query("x")


def test_configure_uses_model_name_from_settings(fake_model):
    embedding.configure("some/other-model")
    embedding.embed_query("x")
    assert fake_model.loads == ["some/other-model"]
    assert embedding.model_name() == "some/other-model"
    with pytest.raises(RuntimeError):
        embedding.configure("yet/another")
