"""Nơi DUY NHẤT gọi model nhúng (AD-12).

- Model: `EMBEDDING_MODEL`, mặc định `intfloat/multilingual-e5-small` (384 chiều).
- `embed_passage` thêm tiền tố "passage: ", `embed_query` thêm "query: " (yêu cầu của E5).
- Vector luôn được chuẩn hóa L2, nên cosine distance (`<=>`) = 1 - tích vô hướng.
- Model nạp lười ở lần gọi đầu tiên, có khóa để chỉ nạp một lần. `/ai/health` chỉ hỏi
  `is_loaded()`, không ép nạp.
- Các hàm này chạy CPU và chặn luồng: gọi từ endpoint `def` (threadpool) hoặc
  `asyncio.to_thread`.
"""

import threading
from collections.abc import Sequence
from typing import Any, Protocol

import numpy as np

from app.settings import DEFAULT_EMBEDDING_MODEL

EMBEDDING_DIM = 384
PASSAGE_PREFIX = "passage: "
QUERY_PREFIX = "query: "


class _Encoder(Protocol):
    def encode(self, sentences: list[str], **kwargs: Any) -> Any: ...


_model_name: str = DEFAULT_EMBEDDING_MODEL
_model: _Encoder | None = None
_lock = threading.Lock()


def configure(model_name: str) -> None:
    """Đặt tên model trước lần nạp đầu tiên (gọi trong create_app)."""
    global _model_name
    with _lock:
        if _model is not None and model_name != _model_name:
            raise RuntimeError("Model nhúng đã nạp, không đổi tên model được nữa")
        _model_name = model_name


def model_name() -> str:
    """Giá trị ghi vào cột `ai.product_embedding.model_version`."""
    return _model_name


def is_loaded() -> bool:
    return _model is not None


def _load_model(name: str) -> _Encoder:
    # Import muộn: torch nặng, không nạp khi chỉ chạy health hay test.
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(name, device="cpu")


def get_model() -> _Encoder:
    global _model
    if _model is None:
        with _lock:
            if _model is None:
                _model = _load_model(_model_name)
    return _model


def _reset_for_tests() -> None:
    global _model, _model_name
    with _lock:
        _model = None
        _model_name = DEFAULT_EMBEDDING_MODEL


def _encode(texts: list[str]) -> np.ndarray:
    raw = get_model().encode(texts, normalize_embeddings=True, convert_to_numpy=True, show_progress_bar=False)
    vectors = np.asarray(raw, dtype=np.float32)
    if vectors.ndim != 2 or vectors.shape[1] != EMBEDDING_DIM:
        raise ValueError(f"Model trả vector {vectors.shape}, cần (n, {EMBEDDING_DIM})")
    # Chuẩn hóa lại ở đây cho chắc, không phụ thuộc tham số của model.
    norms = np.linalg.norm(vectors, axis=1, keepdims=True)
    if np.any(norms == 0):
        raise ValueError("Model trả vector 0, không chuẩn hóa được")
    return vectors / norms


def embed_passage(texts: Sequence[str]) -> np.ndarray:
    """Nhúng `searchText` của Sản phẩm. Trả mảng float32 (n, 384), mỗi dòng có độ dài 1."""
    if isinstance(texts, str):
        raise TypeError("embed_passage nhận danh sách chuỗi")
    if not texts:
        return np.empty((0, EMBEDDING_DIM), dtype=np.float32)
    return _encode([PASSAGE_PREFIX + t for t in texts])


def embed_query(text: str) -> np.ndarray:
    """Nhúng câu tìm kiếm. Trả mảng float32 (384,), độ dài 1."""
    return _encode([QUERY_PREFIX + text])[0]
