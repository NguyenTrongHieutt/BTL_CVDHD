"""KT-B checks against one HTTP gateway serving /api and /ai."""
import argparse
import json
import math
import re
from datetime import date
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import HTTPCookieProcessor, HTTPRedirectHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[2]
REPORT = "/api/v1/reporting/q/"
PLATFORM = "/api/v1/platform/"


class Failure(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Failure(message)


def match(actual, expected, path):
    """Compare all expected fields and every list entry in order."""
    if isinstance(expected, dict):
        require(isinstance(actual, dict), f"{path}: expected object")
        for key, value in expected.items():
            require(key in actual, f"{path}.{key}: missing")
            match(actual[key], value, f"{path}.{key}")
    elif isinstance(expected, list):
        require(isinstance(actual, list) and len(actual) == len(expected), f"{path}: wrong row count")
        for i, value in enumerate(expected):
            match(actual[i], value, f"{path}[{i}]")
    else:
        require(actual == expected and isinstance(actual, bool) == isinstance(expected, bool),
                f"{path}: expected {expected!r}, got {actual!r}")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class Client:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.jar = CookieJar()
        self.http = build_opener(NoRedirect(), HTTPCookieProcessor(self.jar))

    def call(self, path, query=None, body=None, status=200):
        url = self.url + path + ("?" + urlencode(query) if query else "")
        request = Request(url, data=None if body is None else json.dumps(body).encode(),
                          headers={"Content-Type": "application/json", "Accept": "application/json"})
        try:
            response = self.http.open(request, timeout=15)
        except HTTPError as error:
            response = error
        with response:
            require(response.status == status, f"{path}: expected HTTP {status}, got {response.status}")
            if status == 200:
                return json.load(response)


def report_cases(store, today):
    for q, field in (("Q1", "revenue"), ("Q2", "orderCount"), ("Q3", "grossProfit")):
        value = store[q]["today"][field]
        yield q, today, today, {field: value}, None
    params = dict(today, direction="BEST_SELLING", rankBy="QUANTITY", limit=5)
    yield "Q4", params, today, {}, store["Q4"]["today"]["topByQuantity"][:5]
    yield "Q5", {}, today, {k: store["Q5"]["total"][k] for k in ("stockQuantity", "stockValue")}, None
    comparison = store["Q6"]["this_month_vs_last_month"]
    previous = comparison["periodB"]
    params = dict(comparison["periodA"], metric="REVENUE", compareFromDate=previous["fromDate"],
                  compareToDate=previous["toDate"])
    yield "Q6", params, comparison["periodA"], comparison["Q1"], None
    for q in ("Q7", "Q8", "Q9"):
        data = store[q]
        window = {k: data[k] for k in today} if "fromDate" in data else today
        yield q, {}, window, {}, data["items"]


def report(client, case):
    q, params, period, summary, rows = case
    data = client.call(REPORT + q, params)
    echo = {k: v for k, v in params.items() if k not in period}
    match(data, {"queryId": q, "range": period, "params": echo, "summary": summary}, q)
    columns = data["columns"]
    require(isinstance(columns, list) and columns, f"{q}: missing columns")
    keys = set()
    for col in columns:
        require(col["key"] and col["key"] not in keys and col["label"] and
                col["type"] in ("text", "date", "money", "int", "percent"), f"{q}: invalid column")
        keys.add(col["key"])
    require(isinstance(data["rows"], list) and all(isinstance(row, dict) and keys <= row.keys()
            for row in data["rows"]), f"{q}: invalid rows")
    if rows is not None:
        match(data["rows"], rows, q + ".rows")
    elif q in ("Q1", "Q2", "Q3"):
        for row in data["rows"]:
            for field in summary.keys() & row.keys():
                match(row[field], summary[field], f"{q}.rows.{field}")


def ask(client, question, revenue, anchor):
    data = client.call("/ai/ask", body={"question": question})
    match(data, {"refused": False}, "ask")
    require(data["reportLink"] and data["disclaimer"] and data["interpretation"] and
            "chart" in data, "ask: missing report metadata")
    formatted = date.fromisoformat(anchor).strftime("%d/%m/%Y")
    require(any(day in data["interpretation"] for day in (anchor, formatted)), "ask: wrong period")
    table = data["table"]
    require(table["columns"] and table["rows"], "ask: missing revenue table")
    # One-day, single-metric question. Dates and Q1 identifiers are context, not money.
    def numbers(value):
        text = str(value).replace(anchor, "").replace(formatted, "").replace("Q1", "")
        return [int(re.sub(r"[., ]", "", n)) for n in
                re.findall(r"-?\d+(?:[., ]\d{3})*", text)]
    amounts = []
    for row in table["rows"]:
        require(isinstance(row, list) and len(row) == len(table["columns"]), "ask: table width")
        for value in row:
            amounts.extend(numbers(value))
    require(revenue in amounts and all(n == revenue for n in amounts), "ask: wrong table revenue")
    require(data["answer"] is None or isinstance(data["answer"], str), "ask: invalid narrative")
    require(all(n == revenue for n in numbers(data["answer"])), "ask: wrong narrative revenue")
    quota = data["quota"]
    require(all(type(quota[k]) is int for k in ("used", "limit", "remaining")) and
            0 < quota["used"] <= quota["limit"] and quota["remaining"] == quota["limit"] - quota["used"],
            "ask: inconsistent quota")
    return quota["used"]


def search(client, product, catalog):
    items = client.call("/ai/search", {"q": product["name"], "limit": 3})["items"]
    require(isinstance(items, list) and 1 <= len(items) <= 3, "search: invalid top three")
    seen, names = set(), []
    for item in items:
        pid = item["productId"]
        require(isinstance(pid, str) and pid and pid not in seen and
                type(item["score"]) in (int, float) and math.isfinite(item["score"]), "search: invalid hit")
        seen.add(pid)
        detail = client.call("/api/v1/catalog/products/" + quote(pid, safe=""))
        match(detail, {"id": pid, "status": "ACTIVE"}, "search.product")
        require(detail["name"] in catalog and any(v["sku"] in catalog[detail["name"]] for v in detail["variants"]), "search: product outside demo tenant")
        names.append(detail["name"])
    require(product["name"] in names, "search: expected demo product outside top three")


def run(url, oracle, seeds, emit=print):
    failures = []
    def check(label, fn):
        try:
            result = fn()
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            failures.append(label)
            emit(f"FAIL {label}: {error}")
            return None
        emit("PASS " + label)
        return result

    today = oracle["ranges"]["today"]
    anchor = today["toDate"]
    day = date.fromisoformat(anchor).strftime("%d/%m/%Y")
    question = f"Doanh thu ngày {day} là bao nhiêu?"
    guest = Client(url)
    check("anonymous reporting", lambda: guest.call(REPORT + "Q1", today, status=401))
    check("anonymous search", lambda: guest.call("/ai/search", {"q": "demo"}, status=401))
    check("anonymous ask", lambda: guest.call("/ai/ask", body={"question": question}, status=401))
    owners = {}
    for tenant, seed in seeds.items():
        store = oracle["stores"][tenant]
        product = next(p for p in seed["products"] if p["status"] == "ACTIVE")
        catalog = {p["name"]: {v["sku"] for v in p["variants"]} for p in seed["products"]}
        for role in ("OWNER", "STAFF"):
            client = Client(url)
            user = next(u for u in seed["users"] if u["role"] == role)
            label = f"{tenant} {role} "
            def login():
                me = client.call(PLATFORM + "auth/login", body={"loginKey": user["login"],
                                 "password": seed["defaultPassword"]})
                match(me, {"role": role, "storeName": store["storeName"], "displayName": user["displayName"]}, "login")
                require(me["userId"] and me["storeId"] and any(c.name == "access_token" for c in client.jar),
                        "login: missing session identity")
                match(client.call(PLATFORM + "me"), me, "me")
                return me["storeId"]
            store_id = check(label + "login/me", login)
            if store_id is None:
                continue
            if role == "STAFF":
                if tenant in owners:
                    check(label + "storeId", lambda: match(store_id, owners[tenant], "storeId"))
                check(label + "report denied", lambda: client.call(REPORT + "Q1", today, status=403))
                check(label + "ask denied", lambda: client.call("/ai/ask", body={"question": question}, status=403))
                check(label + "search", lambda: search(client, product, catalog))
                continue
            owners[tenant] = store_id
            for case in report_cases(store, today):
                check(label + case[0], lambda: report(client, case))
            def ask_once():
                return ask(client, question, store["Q1"]["today"]["revenue"], anchor)
            before = check(label + "ask", ask_once)
            check(label + "search", lambda: search(client, product, catalog))
            after = check(label + "ask after search", ask_once)
            if before is not None and after is not None:
                check(label + "search quota", lambda: match(after, before + 1, "quota.used"))
    check("distinct tenant sessions", lambda: require(len(set(owners.values())) == 2, "expected two store IDs"))
    return failures


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--live", required=True, metavar="GATEWAY")
    args = parser.parse_args()
    print("LIVE KT-B | demo seed, two owner asks per tenant")
    oracle = load(ROOT / "eval/answers/expected.json")
    seeds = {t: load(ROOT / "deploy/seed" / (t + ".json")) for t in ("store-a", "store-b")}
    failed = run(args.live, oracle, seeds)
    print(f"LIVE failures: {len(failed)}")
    raise SystemExit(bool(failed))
