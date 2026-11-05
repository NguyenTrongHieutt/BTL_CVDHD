"""Vòng đồng bộ chỉ mục (AD-12). Tắt mặc định ở GĐ0; bật bằng `SYNC_ENABLED=true`.

- Khi khởi động: xóa `sync_cursor` để đánh chỉ mục lại toàn bộ.
- Cứ `SYNC_INTERVAL_SECONDS` (mặc định 60) đọc feed đến hết, gom theo `store_id`.
- Mỗi Cửa hàng ghi trong một transaction riêng, có `set_config('app.store_id', …, true)`.
- `ACTIVE` → nhúng `searchText` (passage) và upsert; `INACTIVE` → xóa embedding.
- Chỉ lưu con trỏ mới sau khi mọi Cửa hàng trong trang đã ghi xong.

TODO(AI-01): chạy thử với DB thật khi Flyway đã tạo schema `ai`; thêm test chéo 2 Cửa hàng (AD-5 mục 9).
"""

import asyncio
import logging
import uuid
from collections.abc import Sequence

import httpx

from app import db, embedding
from app.settings import Settings
from app.sync.feed import FEED_PAGE_LIMIT, ProductChange, fetch_changes, group_by_store

logger = logging.getLogger("app.sync")

CURSOR_NAME = "catalog.product-changes"

UPSERT_EMBEDDING_SQL = """\
insert into ai.product_embedding as e (store_id, product_id, embedding, model_version, source_changed_at)
values (%(store_id)s, %(product_id)s, %(embedding)s, %(model_version)s, %(changed_at)s)
on conflict (store_id, product_id) do update
    set embedding = excluded.embedding,
        model_version = excluded.model_version,
        source_changed_at = excluded.source_changed_at
    where e.source_changed_at <= excluded.source_changed_at
"""

DELETE_EMBEDDINGS_SQL = """\
delete from ai.product_embedding
 where store_id = %(store_id)s
   and product_id = any(%(product_ids)s)
"""

READ_CURSOR_SQL = "select cursor from ai.sync_cursor where name = %s"
WRITE_CURSOR_SQL = """\
insert into ai.sync_cursor (name, cursor, updated_at) values (%s, %s, now())
on conflict (name) do update set cursor = excluded.cursor, updated_at = now()
"""
RESET_CURSOR_SQL = "delete from ai.sync_cursor where name = %s"


def apply_store_changes(store_id: uuid.UUID, changes: Sequence[ProductChange]) -> None:
    active = [c for c in changes if c.status == "ACTIVE"]
    inactive_ids = [c.productId for c in changes if c.status == "INACTIVE"]
    # Nhúng trước khi mở transaction để không giữ kết nối trong lúc chạy CPU.
    vectors = embedding.embed_passage([c.searchText for c in active])
    with db.store_transaction(store_id) as conn:
        for change, vector in zip(active, vectors, strict=True):
            conn.execute(
                UPSERT_EMBEDDING_SQL,
                {
                    "store_id": store_id,
                    "product_id": change.productId,
                    "embedding": vector,
                    "model_version": embedding.model_name(),
                    "changed_at": change.changedAt,
                },
            )
        if inactive_ids:
            conn.execute(DELETE_EMBEDDINGS_SQL, {"store_id": store_id, "product_ids": inactive_ids})


def read_cursor() -> str | None:
    with db.global_transaction() as conn:
        row = conn.execute(READ_CURSOR_SQL, (CURSOR_NAME,)).fetchone()
    return row[0] if row else None


def write_cursor(cursor: str) -> None:
    with db.global_transaction() as conn:
        conn.execute(WRITE_CURSOR_SQL, (CURSOR_NAME, cursor))


def reset_cursor() -> None:
    with db.global_transaction() as conn:
        conn.execute(RESET_CURSOR_SQL, (CURSOR_NAME,))


def sync_until_caught_up(client: httpx.Client, service_key: str) -> int:
    """Đọc feed từng trang đến khi hết. Trả số thay đổi đã xử lý."""
    processed = 0
    cursor = read_cursor()
    while True:
        page = fetch_changes(client, service_key, cursor, FEED_PAGE_LIMIT)
        for store_id, changes in group_by_store(page.items).items():
            apply_store_changes(store_id, changes)
        processed += len(page.items)
        if page.nextCursor and page.nextCursor != cursor:
            write_cursor(page.nextCursor)
            cursor = page.nextCursor
        if len(page.items) < FEED_PAGE_LIMIT:
            return processed


async def run_sync_loop(settings: Settings, stop: asyncio.Event) -> None:
    assert settings.internal_service_key is not None  # Settings đã kiểm khi SYNC_ENABLED
    service_key = settings.internal_service_key.get_secret_value()
    with httpx.Client(base_url=settings.backend_url, timeout=10.0) as client:
        try:
            await asyncio.to_thread(reset_cursor)
        except Exception:
            logger.exception("Không đặt lại được sync_cursor")
        while not stop.is_set():
            try:
                count = await asyncio.to_thread(sync_until_caught_up, client, service_key)
                if count:
                    logger.info("Đồng bộ %d thay đổi Sản phẩm", count)
            except Exception:
                logger.exception("Vòng đồng bộ lỗi, thử lại sau %ds", settings.sync_interval_seconds)
            try:
                await asyncio.wait_for(stop.wait(), timeout=settings.sync_interval_seconds)
            except TimeoutError:
                pass


class SyncRunner:
    """Bật/tắt vòng đồng bộ theo lifespan của app. Không làm gì khi `SYNC_ENABLED=false`."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._stop = asyncio.Event()
        self._task: asyncio.Task[None] | None = None

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    def start(self) -> None:
        if not self._settings.sync_enabled or self._task is not None:
            return
        self._task = asyncio.create_task(run_sync_loop(self._settings, self._stop), name="ai-sync")
        logger.info("Bật vòng đồng bộ, chu kỳ %ds", self._settings.sync_interval_seconds)

    async def stop(self) -> None:
        if self._task is None:
            return
        self._stop.set()
        try:
            await asyncio.wait_for(self._task, timeout=15)
        except TimeoutError:
            self._task.cancel()
        self._task = None
