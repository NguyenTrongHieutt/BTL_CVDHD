"""`GET /ai/health`: không cần đăng nhập, không ép nạp model."""

from app import embedding


def test_health_reports_ok_without_loading_model(client, monkeypatch):
    def must_not_load(name: str):
        raise AssertionError("health không được nạp model")

    monkeypatch.setattr(embedding, "_load_model", must_not_load)
    response = client.get("/ai/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "version": "0.1.0",
        "modelLoaded": False,
        "embeddingModel": "intfloat/multilingual-e5-small",
        "syncEnabled": False,
    }
    assert not embedding.is_loaded()


def test_health_shows_model_loaded_after_first_use(client, monkeypatch):
    class Dummy:
        def encode(self, sentences, **kwargs):
            import numpy as np

            return np.ones((len(sentences), embedding.EMBEDDING_DIM), dtype=np.float32)

    monkeypatch.setattr(embedding, "_load_model", lambda name: Dummy())
    embedding.embed_query("x")
    assert client.get("/ai/health").json()["modelLoaded"] is True


def test_sync_loop_is_off_by_default(client):
    assert client.app.state.sync_runner.running is False
