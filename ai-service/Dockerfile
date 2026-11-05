# syntax=docker/dockerfile:1
# Image của ai-service (AD-1, AD-12): Python 3.14, torch CPU, model nhúng đóng gói sẵn để chạy offline.

ARG EMBEDDING_MODEL=intfloat/multilingual-e5-small

FROM ghcr.io/astral-sh/uv:0.13.7 AS uv

# ---------- builder: cài dependency và tải model ----------
FROM python:3.14-slim AS builder
COPY --from=uv /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv

WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
# torch lấy từ index CPU (download.pytorch.org/whl/cpu) đã khai báo trong pyproject + uv.lock.
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev --no-install-project

ARG EMBEDDING_MODEL
ENV HF_HOME=/models
RUN /app/.venv/bin/python -c "\
from sentence_transformers import SentenceTransformer; \
m = SentenceTransformer('${EMBEDDING_MODEL}', device='cpu'); \
dim = m.encode(['query: kiem tra']).shape[-1]; \
assert dim == 384, dim; print('model ok, dim', dim)" \
 && rm -rf /models/xet

# ---------- runtime ----------
FROM python:3.14-slim AS runtime
ARG EMBEDDING_MODEL

RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --home-dir /home/app --create-home app

COPY --from=builder /app/.venv /app/.venv
COPY --from=builder /models /models
COPY app /app/app

ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HF_HOME=/models \
    HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    TOKENIZERS_PARALLELISM=false \
    EMBEDDING_MODEL=${EMBEDDING_MODEL}

WORKDIR /app
USER app
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/ai/health', timeout=3).status == 200 else 1)"]

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
