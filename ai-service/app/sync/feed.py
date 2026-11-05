"""Client cho change-feed `GET {BACKEND_URL}/internal/catalog/product-changes` (AD-12).

Hợp đồng:
    request : ?after=<cursor>&limit=500, header X-Service-Key = INTERNAL_SERVICE_KEY
    response: {"items": [{storeId, productId, searchText, status: ACTIVE|INACTIVE, changedAt}],
               "nextCursor": "<chuỗi mờ>"}
Con trỏ là chuỗi mờ do backend trả (bên trong là cặp (changed_at, product_id)); ai-service
chỉ lưu và gửi lại, không phân tích.
"""

import uuid
from collections import defaultdict
from collections.abc import Iterable
from typing import Literal

import httpx
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

FEED_PATH = "/internal/catalog/product-changes"
FEED_PAGE_LIMIT = 500
SERVICE_KEY_HEADER = "X-Service-Key"


class ProductChange(BaseModel):
    model_config = ConfigDict(extra="ignore", frozen=True)

    storeId: uuid.UUID
    productId: uuid.UUID
    searchText: str
    status: Literal["ACTIVE", "INACTIVE"]
    changedAt: AwareDatetime


class ChangePage(BaseModel):
    model_config = ConfigDict(extra="ignore")

    items: list[ProductChange] = Field(default_factory=list)
    nextCursor: str | None = None


def fetch_changes(
    client: httpx.Client, service_key: str, after: str | None, limit: int = FEED_PAGE_LIMIT
) -> ChangePage:
    params: dict[str, str | int] = {"limit": min(limit, FEED_PAGE_LIMIT)}
    if after:
        params["after"] = after
    response = client.get(FEED_PATH, params=params, headers={SERVICE_KEY_HEADER: service_key})
    response.raise_for_status()
    return ChangePage.model_validate(response.json())


def group_by_store(items: Iterable[ProductChange]) -> dict[uuid.UUID, list[ProductChange]]:
    """Gom theo Cửa hàng; mỗi Cửa hàng ghi trong một transaction riêng (AD-12).

    Cùng một Sản phẩm xuất hiện nhiều lần trong trang thì chỉ giữ thay đổi mới nhất.
    """
    latest: dict[tuple[uuid.UUID, uuid.UUID], ProductChange] = {}
    for item in items:
        key = (item.storeId, item.productId)
        if key not in latest or item.changedAt >= latest[key].changedAt:
            latest[key] = item
    grouped: dict[uuid.UUID, list[ProductChange]] = defaultdict(list)
    for (store_id, _), item in latest.items():
        grouped[store_id].append(item)
    return dict(grouped)

