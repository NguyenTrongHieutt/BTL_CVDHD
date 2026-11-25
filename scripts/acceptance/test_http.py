"""SIMULATION. This loopback gateway never reads the eval oracle or the seed files."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qsl, urlsplit

import run

DATE = "2026-12-16"
TODAY = {"fromDate": DATE, "toDate": DATE}
MONTH = {"fromDate": "2026-12-01", "toDate": "2026-12-31"}
COMPARE = dict(metric="REVENUE", compareFromDate="2026-11-01", compareToDate="2026-11-30")
RANK = dict(direction="BEST_SELLING", rankBy="QUANTITY", limit="5")
STORES = ["Tạp hóa Lan", "Thời trang Mai"]
NAMES = ["Nước ngọt Coca-Cola lon 320ml", "Áo thun nam cổ tròn basic"]
SKUS = ["TH0001", "TT0001-DEN-S"]
ACCOUNTS = {
    "0901000001": (0, "OWNER", "Nguyễn Thị Lan"), "hoa.lan": (0, "STAFF", "Lê Thị Hoa"),
    "0902000001": (1, "OWNER", "Phạm Thị Mai"), "linh.mai": (1, "STAFF", "Đỗ Thùy Linh"),
}
REVENUE = [330000, 1577000]


class Gateway(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, data=None, session=None):
        raw = json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        if session:
            self.send_header("Set-Cookie", f"access_token={session}; Path=/; HttpOnly")
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/api/v1/platform/auth/login":
            key = body.get("loginKey")
            if body.get("password") == "Demo@2026" and key in ACCOUNTS:
                return self.identity(key, login=True)
            return self.reply(401)
        if self.path == "/ai/ask":
            return self.dispatch(body)
        self.reply(404)

    def identity(self, key, login=False):
        t, role, name = ACCOUNTS[key]
        self.reply(200, dict(storeId=str(t), userId=key, role=role, displayName=name,
                             storeName=STORES[t]), key if login else None)

    def do_GET(self):
        self.dispatch(None)

    def dispatch(self, body):
        key = self.headers.get("Cookie", "").removeprefix("access_token=")
        if key not in ACCOUNTS:
            return self.reply(401)
        t, role, _ = ACCOUNTS[key]
        url = urlsplit(self.path)
        params = dict(parse_qsl(url.query))
        if url.path == "/api/v1/platform/me":
            return self.identity(key)
        if url.path == "/ai/search" and params == {"q": NAMES[t], "limit": "3"}:
            hit = 1-t if self.server.fault == "search" else t
            return self.reply(200, {"items": [{"productId": str(hit), "score": 0.89}]})
        if url.path == f"/api/v1/catalog/products/{t}":
            return self.reply(200, dict(id=str(t), name=NAMES[t], status="ACTIVE", variants=[{"sku": SKUS[t]}]))
        if role == "STAFF":
            return self.reply(403)
        if url.path == "/ai/ask" and body == {"question": "Doanh thu ngày 16/12/2026 là bao nhiêu?"}:
            self.server.used[t] += 1
            used = self.server.used[t]
            amount = REVENUE[1-t if self.server.fault == "table" else t]
            spoken = REVENUE[1-t if self.server.fault == "answer" else t]
            return self.reply(200, dict(answer=f"{spoken:,} đồng", interpretation="Q1 " + DATE,
                table={"columns": ["Doanh thu"], "rows": [[amount]]}, chart=None, reportLink="/reports",
                disclaimer="AI có thể sai", refused=False, quota=dict(used=used, limit=50, remaining=50-used)))
        if not url.path.startswith("/api/v1/reporting/q/"):
            return self.reply(404)
        q = url.path.split("/")[-1]
        required = {"Q1": TODAY, "Q2": TODAY, "Q3": TODAY, "Q4": TODAY | RANK, "Q5": {},
                    "Q6": MONTH | COMPARE, "Q7": {}, "Q8": {}, "Q9": {}}
        if q not in required or required[q] != params:
            return self.reply(400)
        t = 1-t if self.server.fault == q else t
        summary = {"Q1": {"revenue": REVENUE[t]}, "Q2": {"orderCount": [3, 2][t]},
                   "Q3": {"grossProfit": [55217, 847000][t]},
                   "Q5": dict(stockQuantity=[1037, 2278][t], stockValue=[35846254, 362500723][t]),
                   "Q6": dict(a=[15605502, 82168500][t], b=[24412050, 134975217][t],
                              diff=[-8806548, -52806717][t], changePct=[-36.1, -39.1][t])}.get(q, {})
        period = dict(TODAY)
        echo = {}
        rows = [summary]
        if q == "Q4":
            echo = dict(RANK, limit=5)
            rows = [dict(productCode=["TH0001", "TT0001"][t], quantity=8),
                    dict(productCode=["TH0002", "TT0002"][t], quantity=4)]
            if self.server.fault == "reversed":
                rows.reverse()
        elif q == "Q6":
            echo, period = COMPARE, MONTH
        elif q in ("Q7", "Q8", "Q9"):
            rows = [dict(sku=SKUS[t], stockQuantity=12)]
            if q != "Q9":
                period["fromDate"] = {"Q7": "2026-11-17", "Q8": "2026-12-03"}[q]
        if self.server.fault == "range" and q == "Q6":
            period = dict(period, toDate=DATE)
        cols = [dict(key=k, label=k, type="text" if isinstance(v, str) else "int") for k,v in rows[0].items()]
        self.reply(200, dict(queryId=q, range=period, params=echo, columns=cols, rows=rows, summary=summary))


class SimulatedHTTP(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.http = HTTPServer(("127.0.0.1", 0), Gateway)
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = f"http://127.0.0.1:{cls.http.server_port}"
        cls.oracle = run.load(run.ROOT / "eval/answers/expected.json")
        cls.seeds = {k: run.load(run.ROOT / "deploy/seed" / (k + ".json")) for k in cls.oracle["stores"]}
        # Miniature ranked lists for transport tests. Scalar expectations remain the real oracle.
        for tenant, code, sku in (("store-a", "TH0001", "TH0001"), ("store-b", "TT0001", "TT0001-DEN-S")):
            s = cls.oracle["stores"][tenant]
            s["Q4"]["today"]["topByQuantity"] = [
                {"productCode": code, "quantity": 8}, {"productCode": code[:-1] + "2", "quantity": 4}]
            for q in ("Q7", "Q8", "Q9"):
                s[q]["items"] = [{"sku": sku, "stockQuantity": 12}]

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.thread.join()
        cls.http.server_close()

    def exercise(self, fault=None):
        self.http.fault = fault
        self.http.used = [0, 0]
        return run.run(self.url, self.oracle, self.seeds, lambda line: None)

    def test_simulated_success(self):
        self.assertEqual(self.exercise(), [])

    def test_simulated_wrong_tenant_reports(self):
        for q in ("Q1", "Q2", "Q3", "Q4", "Q5", "Q6", "Q7", "Q8", "Q9"):
            with self.subTest(query=q):
                self.assertEqual(self.exercise(q), [f"store-a OWNER {q}", f"store-b OWNER {q}"])

    def test_simulated_wrong_order_and_range(self):
        for fault, q in (("reversed", "Q4"), ("range", "Q6")):
            with self.subTest(fault=fault):
                self.assertEqual(self.exercise(fault), [f"{t} OWNER {q}" for t in self.seeds])

    def test_simulated_wrong_tenant_ask(self):
        for fault in ("table", "answer"):
            with self.subTest(fault=fault):
                self.assertEqual(self.exercise(fault), [f"{t} OWNER {case}" for t in self.seeds
                                 for case in ("ask", "ask after search")])

    def test_simulated_wrong_tenant_search(self):
        self.assertEqual(self.exercise("search"), [f"{t} {r} search" for t in self.seeds
                         for r in ("OWNER", "STAFF")])


if __name__ == "__main__":
    unittest.main(verbosity=2)
