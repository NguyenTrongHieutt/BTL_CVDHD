"""Spike GĐ0: model nhúng intfloat/multilingual-e5-small với tên Sản phẩm tiếng Việt.

Chạy:  uv run python spikes/embedding_spike.py
Lần đầu sẽ tải model (~470 MB) vào cache Hugging Face trên máy.

Kiểm:
- số chiều = 384 (khớp cột vector(384), AD-12);
- vector đã chuẩn hóa;
- top-3 cho từng câu tìm kiếm, đi qua đúng `app.embedding` (tiền tố query:/passage:).
So hai cách dựng văn bản passage:
  A. searchText như catalog dựng (tên · Danh mục);
  B. searchText + bản bỏ dấu của chính nó (để thử câu gõ không dấu, FR-35).
"""

import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[attr-defined]

import numpy as np  # noqa: E402

from app import embedding  # noqa: E402

PRODUCTS = [
    "Nước ngọt Coca-Cola lon 330ml · Đồ uống",
    "Nước tăng lực Red Bull lon 250ml · Đồ uống",
    "Nước khoáng Lavie chai 500ml · Đồ uống",
    "Bia Tiger lon 330ml · Bia rượu",
    "Dầu gội Sunsilk mềm mượt cho tóc khô xơ 320g · Chăm sóc tóc",
    "Dầu gội Clear mát lạnh bạc hà sạch gàu 630g · Chăm sóc tóc",
    "Mì Hảo Hảo tôm chua cay gói 75g · Thực phẩm khô",
    "Sữa tươi Vinamilk không đường hộp 1L · Sữa",
    "Bột giặt OMO hệ bọt thông minh 4.1kg · Giặt giũ",
    "Kem đánh răng P/S than hoạt tính 230g · Chăm sóc răng miệng",
]

# (câu tìm kiếm, tiền tố tên Sản phẩm mong đợi, ghi chú)
QUERIES = [
    ("nước giải khát có ga", "Nước ngọt Coca-Cola", "từ đồng nghĩa"),
    ("dầu gội cho tóc khô", "Dầu gội Sunsilk", "thuộc tính"),
    ("đồ ăn liền", "Mì Hảo Hảo", "khái niệm"),
    ("dau goi tri gau", "Dầu gội Clear", "gõ không dấu"),
    ("sua tuoi khong duong", "Sữa tươi Vinamilk", "gõ không dấu"),
]


def unaccent(text: str) -> str:
    text = text.replace("đ", "d").replace("Đ", "D")
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def run_variant(label: str, passages_text: list[str]) -> tuple[int, int]:
    passages = embedding.embed_passage(passages_text)
    print(f"\n=== {label} ===")
    top1 = top3 = 0
    for query, expected, note in QUERIES:
        # Vector đã chuẩn hóa: cosine similarity = tích vô hướng = 1 - cosine distance (`<=>`).
        scores = passages @ embedding.embed_query(query)
        order = list(np.argsort(-scores))
        rank = next(pos for pos, i in enumerate(order, 1) if PRODUCTS[i].startswith(expected))
        top1 += rank == 1
        top3 += rank <= 3
        print(f"Q: \"{query}\" ({note}) -> Sản phẩm mong đợi ở hạng {rank}")
        for pos, i in enumerate(order[:3], 1):
            print(f"  {pos}. {scores[i]:.4f}  {PRODUCTS[i]}")
    print(f"Tổng: top-1 {top1}/{len(QUERIES)}, top-3 {top3}/{len(QUERIES)}")
    return top1, top3


def main() -> int:
    t0 = time.perf_counter()
    passages = embedding.embed_passage(PRODUCTS)
    print(f"Model: {embedding.model_name()}")
    print(f"Nạp model + nhúng {len(PRODUCTS)} Sản phẩm: {time.perf_counter() - t0:.1f} s")
    dim_ok = passages.shape[1] == embedding.EMBEDDING_DIM
    print(f"Số chiều: {passages.shape[1]} (cần 384) -> {'ĐẠT' if dim_ok else 'KHÔNG ĐẠT'}")
    norms = np.linalg.norm(passages, axis=1)
    print(f"Độ dài vector: min {norms.min():.6f}, max {norms.max():.6f}")

    times = []
    for query, _, _ in QUERIES:
        t1 = time.perf_counter()
        embedding.embed_query(query)
        times.append((time.perf_counter() - t1) * 1000)
    print(f"Nhúng 1 câu tìm kiếm (CPU, model đã nạp): trung vị {np.median(times):.0f} ms")

    run_variant("A. searchText (tên · Danh mục)", PRODUCTS)
    run_variant("B. searchText + bản bỏ dấu", [f"{p} | {unaccent(p)}" for p in PRODUCTS])
    return 0 if dim_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
