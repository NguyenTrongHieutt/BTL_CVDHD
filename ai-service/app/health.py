"""`GET /ai/health`: trạng thái dịch vụ. Không ép nạp model, không cần đăng nhập."""

from fastapi import APIRouter, Request
from pydantic import BaseModel

from app import __version__, embedding

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str
    version: str
    modelLoaded: bool
    embeddingModel: str
    syncEnabled: bool


@router.get("/health", response_model=HealthResponse)
def health(request: Request) -> HealthResponse:
    return HealthResponse(
        status="ok",
        version=__version__,
        modelLoaded=embedding.is_loaded(),
        embeddingModel=embedding.model_name(),
        syncEnabled=request.app.state.settings.sync_enabled,
    )
