"""DM-01: diễn tập API thật trên máy demo cục bộ; mỗi lượt tạo hai cửa hàng mới."""
import argparse
import json
import secrets
import uuid
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPCookieProcessor, Request, build_opener


class Session:
    def __init__(self, base):
        self.base = base
        self.client = build_opener(HTTPCookieProcessor(CookieJar()))

    def call(self, path, payload=None, *, status=200, key=None):
        headers = {"Content-Type": "application/json"}
        if key:
            headers["Idempotency-Key"] = str(key)
        body = json.dumps(payload).encode() if payload is not None else None
        req = Request(self.base + path, data=body, headers=headers)
        try:
            response = self.client.open(req, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if response.status != status:
                raise RuntimeError(f"{req.get_method()} {path}: "
                                   f"HTTP {response.status}, expected {status}, request {response.headers.get('X-Request-Id')}")
            return json.loads(raw) if raw else None


def ensure(condition, description):
    if not condition:
        raise RuntimeError(description)


def store(base, name, password):
    session = Session(base)
    me = session.call("/api/v1/platform/stores", {
        "storeName": name, "loginKey": name + "@example.test",
        "displayName": name, "password": password,
    }, key=uuid.uuid4(), status=201)
    ensure(me["role"] == "OWNER", "Đăng ký chưa tạo phiên chủ cửa hàng")
    return session


def add_product(session, price):
    product = session.call("/api/v1/catalog/products", {
        "name": "Tất cotton", "sku": "TAT-01", "salePrice": price,
        "openingQuantity": 10, "openingUnitCost": 5000,
    }, key=uuid.uuid4(), status=201)
    return product["variants"][0]["id"]


def check_item(session, variant, price, quantity):
    page = session.call("/api/v1/sales/sellable?q=TAT-01")
    ensure(page["total"] == 1, "Mã hàng phải duy nhất trong cửa hàng")
    item = page["items"][0]
    ensure((item["variantId"], item["salePrice"], item["quantity"]) ==
           (variant, price, quantity), "Sai cửa hàng, giá hoặc tồn kho")


def check_customer_history(client, customer_id, saved):
    summary = client.call(f"/api/v1/sales/customers/{customer_id}/purchase-summary")
    ensure(summary["orderCount"] == 1 and summary["totalSpent"] == 30000
           and summary["lastPurchaseAt"], "Tổng hợp mua không khớp giao dịch")
    page = client.call(f"/api/v1/sales/orders?customerId={customer_id}&page=0&size=20")
    ensure(page["total"] == 1 and page["items"][0]["orderId"] == saved["orderId"],
           "Lịch sử khách không chứa đúng đơn vừa bán")


def rehearse(base, ai_off, integrated):
    tag = uuid.uuid4().hex[:12]
    password = secrets.token_urlsafe(24)
    owner = store(base, "a" + tag, password)
    other = store(base, "b" + tag, password)
    variant = add_product(owner, 15000)
    other_variant = add_product(other, 19000)
    staff_login = "nv." + tag
    owner.call("/api/v1/platform/users", {
        "loginKey": staff_login, "displayName": "Nhân viên demo", "password": password,
    }, status=201)
    staff = Session(base)
    me = staff.call("/api/v1/platform/auth/login", {"loginKey": staff_login, "password": password})
    ensure(me["role"] == "STAFF", "Sai vai trò người bán")
    staff.call("/api/v1/platform/audit-log", status=403)
    staff.call("/ai/health", status=503 if ai_off else 200)
    check_item(staff, variant, 15000, 10)
    check_item(other, other_variant, 19000, 10)
    customer = staff.call("/api/v1/catalog/customers", {"name": "Khách demo", "phone": "0901234567"}, status=201)
    key = uuid.uuid4()
    order = {"lines": [{"variantId": variant, "quantity": 2}], "customerId": customer["customerId"],
             "paymentMethod": "CASH", "cashReceived": 50000}
    sales = "/api/v1/sales/orders"
    quote = staff.call(sales + "/quote", order, key=key)
    ensure((quote["total"], quote["change"]) == (30000, 20000), "Sai tổng tiền hoặc tiền thừa")
    order["quotedTotal"] = quote["total"]
    saved = staff.call(sales, order, key=key, status=201)
    replay = staff.call(sales, order, key=key)
    ensure(saved["orderId"] == replay["orderId"], "Gửi lại đã tạo đơn mới")
    check_item(staff, variant, 15000, 8)
    check_item(other, other_variant, 19000, 10)
    rows = owner.call("/api/v1/inventory/ledger?documentType=SALE&variantId=" + variant)
    ensure(rows["total"] == 1, "Sai số bút toán kho cho một giao dịch")
    entry = rows["items"][0]
    ensure((entry["documentId"], entry["delta"], entry["quantityAfter"]) ==
           (saved["orderId"], -2, 8), "Sổ kho không khớp giao dịch")
    audit = owner.call("/api/v1/platform/audit-log?action=platform.user.create")
    ensure(audit["total"] == 1, "Thiếu nhật ký tạo nhân viên")
    if integrated:
        for client in (owner, staff):
            check_customer_history(client, customer["customerId"], saved)
        ledger = "/api/v1/inventory/ledger?documentType=SALE&variantId=" + variant
        seller_rows = owner.call(ledger + "&actorId=" + me["userId"])
        owner_id = owner.call("/api/v1/platform/me")["userId"]
        owner_rows = owner.call(ledger + "&actorId=" + owner_id)
        ensure(seller_rows["total"] == 1 and seller_rows["items"][0]["documentId"] == saved["orderId"],
               "Sai giao dịch của người bán")
        ensure(owner_rows["total"] == 0 and not owner_rows["items"], "Sổ kho bỏ qua actorId")
    print(f"PASS store={tag} order={saved['orderCode']} total=30000 stock=8 "
          f"other_store_stock=10 ai={'off' if ai_off else 'on'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="Địa chỉ loopback của nginx trong môi trường demo riêng")
    parser.add_argument("--ai-off", action="store_true", help="Kỳ vọng AI đã dừng, web và bán hàng vẫn chạy")
    parser.add_argument("--integrated", action="store_true",
                        help="Kiểm thêm KH-03 và actorId bằng API thật sau khi Anh bàn giao")
    args = parser.parse_args()
    url = urlsplit(args.base_url)
    if url.scheme not in ("http", "https") or url.hostname not in ("localhost", "127.0.0.1", "::1"):
        parser.error("Chạy trên môi trường demo cục bộ vì bài thử tạo dữ liệu.")
    rehearse(args.base_url.rstrip("/"), args.ai_off, args.integrated)
