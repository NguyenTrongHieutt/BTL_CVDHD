from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings
from tests.conftest import TEST_JWT_SECRET


def test_metrics_are_optional_and_never_record_query_or_request_content():
    settings = Settings(jwt_secret=TEST_JWT_SECRET, preload_embedding_model=False)
    with TestClient(create_app(settings)) as client:
        assert client.get("/metrics").status_code == 404

    with TestClient(create_app(settings.model_copy(update={"metrics_enabled": True}))) as client:
        assert client.get("/ai/search", params={"q": "khong-duoc-luu-trong-metrics"}).status_code == 401
        assert client.get("/ai/search/arbitrary-value").status_code == 404
        response = client.get("/metrics")
        assert response.status_code == 200
        assert 'banhang_ai_requests_total{route="/ai/search",status="401"} 1.0' in response.text
        assert 'banhang_ai_request_duration_seconds_bucket' in response.text
        assert 'banhang_ai_embedding_model_loaded' in response.text
        assert 'khong-duoc-luu-trong-metrics' not in response.text
        assert 'arbitrary-value' not in response.text
