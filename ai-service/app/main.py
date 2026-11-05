"""Điểm vào FastAPI: `uvicorn app.main:app --host 0.0.0.0 --port 8000`.

Mọi route nằm dưới `/ai` (nginx chuyển nguyên đường dẫn, AD-1):
    GET  /ai/health
    GET  /ai/search?q=&limit=
    POST /ai/ask
"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app import __version__, db, embedding
from app.health import router as health_router
from app.log import configure_logging
from app.problems import install_problem_handlers
from app.qa.router import router as qa_router
from app.search.router import router as search_router
from app.settings import Settings, get_settings
from app.sync.loop import SyncRunner

API_PREFIX = "/ai"


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    db.configure(settings.database_url.get_secret_value())
    embedding.configure(settings.embedding_model)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        runner = SyncRunner(settings)
        app.state.sync_runner = runner
        runner.start()
        try:
            yield
        finally:
            await runner.stop()

    app = FastAPI(
        title="ai-service",
        version=__version__,
        description="Tìm kiếm ngữ nghĩa và Hỏi đáp dữ liệu (AD-11, AD-12)",
        lifespan=lifespan,
        docs_url=f"{API_PREFIX}/docs",
        openapi_url=f"{API_PREFIX}/openapi.json",
        redoc_url=None,
    )
    app.state.settings = settings
    install_problem_handlers(app)
    app.include_router(health_router, prefix=API_PREFIX)
    app.include_router(search_router, prefix=API_PREFIX)
    app.include_router(qa_router, prefix=API_PREFIX)
    return app


app = create_app()
