"""Truy cập PostgreSQL bằng psycopg 3, role `ai_service`, chỉ schema `ai` (AD-3, AD-5).

Quy tắc:
- Mọi truy cập database nằm trong transaction.
- Transaction chạm bảng có `store_id` phải mở bằng `store_transaction(store_id)`: câu
  đầu tiên là `select set_config('app.store_id', %s, true)`, nên RLS lọc đúng Cửa hàng.
- `global_transaction()` chỉ dùng cho `ai.sync_cursor` (bảng toàn cục, AD-5 mục 1).
- DDL do Flyway ở backend tạo; ở đây không chạy DDL.

Bản GĐ0 mở một kết nối cho mỗi transaction (quy mô demo). Cần thì thêm psycopg_pool sau.
"""

import uuid
from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from pgvector.psycopg import register_vector

SET_STORE_SQL = "select set_config('app.store_id', %s, true)"

_database_url: str | None = None


def configure(database_url: str) -> None:
    global _database_url
    _database_url = database_url


def connect() -> psycopg.Connection:
    if _database_url is None:
        raise RuntimeError("db.configure() chưa được gọi")
    # autocommit=True để register_vector không mở transaction ngầm; transaction thật
    # mở bằng conn.transaction() (BEGIN … COMMIT/ROLLBACK).
    conn = psycopg.connect(_database_url, autocommit=True, application_name="ai-service")
    try:
        register_vector(conn)
    except Exception:
        conn.close()
        raise
    return conn


@contextmanager
def store_transaction(store_id: uuid.UUID) -> Iterator[psycopg.Connection]:
    """Mở transaction trong ngữ cảnh một Cửa hàng; `set_config` chạy trước mọi câu khác."""
    if not isinstance(store_id, uuid.UUID):
        raise TypeError("store_id phải là uuid.UUID")
    with connect() as conn, conn.transaction():
        conn.execute(SET_STORE_SQL, (str(store_id),))
        yield conn


@contextmanager
def global_transaction() -> Iterator[psycopg.Connection]:
    """Transaction không có ngữ cảnh Cửa hàng. Chỉ cho `ai.sync_cursor`."""
    with connect() as conn, conn.transaction():
        yield conn
