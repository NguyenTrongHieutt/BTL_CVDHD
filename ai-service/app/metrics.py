"""Xuất chỉ số dịch vụ cho Prometheus trong mạng Docker."""

from time import perf_counter

from fastapi import FastAPI, Request, Response
from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, ProcessCollector
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from app import embedding

MONITORED_ROUTES = frozenset({("GET", "/ai/search"), ("GET", "/ai/search/alternatives"), ("POST", "/ai/ask")})


def enable_metrics(app: FastAPI) -> None:
    registry = CollectorRegistry()
    ProcessCollector(registry=registry)
    requests = Counter("banhang_ai_requests_total", "Số yêu cầu AI theo endpoint và mã HTTP.",
                       ["route", "status"], registry=registry)
    duration = Histogram("banhang_ai_request_duration_seconds", "Thời gian xử lý yêu cầu AI.", ["route"],
                         buckets=(0.05, 0.1, 0.25, 0.5, 1, 1.5, 2.5, 5, 10), registry=registry)
    model_loaded = Gauge("banhang_ai_embedding_model_loaded", "Model nhúng đã nạp vào bộ nhớ.", registry=registry)
    model_loaded.set_function(embedding.is_loaded)
    sync_enabled = Gauge("banhang_ai_sync_enabled", "Đồng bộ chỉ mục được bật.", registry=registry)
    sync_enabled.set(float(app.state.settings.sync_enabled))

    @app.middleware("http")
    async def observe(request: Request, call_next):
        path = request.url.path
        if (request.method, path) not in MONITORED_ROUTES:
            return await call_next(request)
        started = perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            requests.labels(path, str(status)).inc()
            duration.labels(path).observe(perf_counter() - started)

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(registry), headers={"Content-Type": CONTENT_TYPE_LATEST})
