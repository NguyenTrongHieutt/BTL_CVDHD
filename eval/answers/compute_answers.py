#!/usr/bin/env python3
"""Dap an doc lap cho Truy van mau Q1-Q9 tren du lieu mau (AD-10, AD-18, SM-2, SM-3).

Script nay KHONG dung ma cua he thong (backend, ai-service) va KHONG import eval/seed_gen.
No chi doc deploy/seed/store-*.json, tu phat lai moi su kien theo thu tu thoi gian va tinh lai:
  - Ton kho, Gia von binh quan (AD-15), Lo hang theo FEFO, Diem tich luy;
  - tien cua tung Don hang va Phieu tra hang theo AD-8;
  - Q1-Q9 theo dinh nghia PRD muc 4.6.1 cho cac bo tham so chuan tinh tu ngay neo.

Cach chay (tu thu muc goc repo product/):
    py -3.14 eval/answers/compute_answers.py               # ghi eval/answers/expected.json
    py -3.14 eval/answers/compute_answers.py --check       # khong ghi; loi neu du lieu sai quy tac
                                                           # hoac expected.json da cu
    py -3.14 eval/answers/compute_answers.py --selftest    # kiem tra cac quy tac tien bang so tinh tay
    py -3.14 eval/answers/compute_answers.py --anchor 2027-01-10 --out C:/tmp/expected.json

Ngay neo: --anchor > bien moi truong SEED_ANCHOR_DATE > mac dinh 2026-12-16 (xem deploy/seed/README.md).
Ma thoat: 0 = dat; 1 = du lieu vi pham quy tac / bat bien; 2 = expected.json khong khop; 3 = selftest that bai.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
from collections import defaultdict
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from fractions import Fraction
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEED_DIR = REPO_ROOT / "deploy" / "seed"
SCHEMA_PATH = SEED_DIR / "schema" / "store-seed.schema.json"
EXPECTED_PATH = Path(__file__).resolve().parent / "expected.json"

DEFAULT_ANCHOR = "2026-12-16"
TIMEZONE = "Asia/Ho_Chi_Minh"
OUTPUT_FORMAT_VERSION = 1

# Hang so PRD 4.6.1 (phai trung voi lop hang so duy nhat trong `reporting`, AD-10).
SLOW_WINDOW_DAYS = 30        # Toc do ban 30 ngay gan nhat
SLOW_DAYS_OF_STOCK = 30      # ban cham khi du ban hon 30 ngay
RESTOCK_WINDOW_DAYS = 14     # Toc do ban 14 ngay gan nhat
RESTOCK_DAYS_OF_STOCK = 7    # goi y nhap khi du ban duoi 7 ngay
RESTOCK_COVER_DAYS = 7       # so luong goi y = toc do 14 ngay x 7 - ton
TOP_LIMIT = 10               # Q4 tra 10 dong; top 5 = 5 dong dau
DASHBOARD_TOP = 5            # FR-29: 5 San pham ban chay nhat trong 7 ngay
DASHBOARD_WINDOW_DAYS = 7

EVENT_ORDER = {"RECEIPT": 1, "ORDER": 2, "CANCELLATION": 3, "RETURN": 4, "DEACTIVATION": 5, "STOCKTAKE": 6}


# =========================================================================== money rules (AD-8, AD-15)

def half_up_div(num: int, den: int) -> int:
    """Chia nguyen lam tron half-up cho so khong am."""
    if num < 0 or den <= 0:
        raise ValueError(f"half_up_div({num}, {den})")
    q, r = divmod(num, den)
    return q + (1 if 2 * r >= den else 0)


def percent_amount(base: int, pct: int) -> int:
    """Giam theo %: HALF_UP(base * pct / 100)."""
    return half_up_div(base * pct, 100)


def allocate(total: int, amounts: list[int]) -> list[int]:
    """Chia giam gia toan don theo ty le tien cua tung dong: cac dong truoc lay floor, dong cuoi nhan phan du."""
    if total == 0:
        return [0] * len(amounts)
    s = sum(amounts)
    if s <= 0:
        raise ValueError("khong chia duoc giam gia khi tong tien = 0")
    out = []
    for a in amounts[:-1]:
        out.append(total * a // s)
    out.append(total - sum(out))
    return out


def avg_cost_after_receipt(q_old: int, c_old: int, q_in: int, c_in: int) -> int:
    """AD-15: avg_moi = HALF_UP((Q*C + q*c)/(Q+q)); Q = 0 thi avg_moi = c."""
    if q_old == 0:
        return c_in
    return half_up_div(q_old * c_old + q_in * c_in, q_old + q_in)


def refund_amount(net: int, qty: int, already_qty: int, already_refund: int, r: int) -> int:
    """AD-8: hoan theo ty le so luong tren net_line_amount (floor); lan tra cuoi cung cua dong nhan phan con lai."""
    if already_qty + r == qty:
        return net - already_refund
    return net * r // qty


def points_kept(total: int, refunded: int, earn_unit: int) -> int:
    """So diem con giu cua mot Don hang sau khi da hoan `refunded` dong."""
    return (total - refunded) // earn_unit


def compute_order_money(lines, order_discount, points_value):
    """lines: [(unit_price, qty, discount_dict|None)]. Tra ve dict cac thanh phan tien, hoac raise ValueError."""
    gross = [p * q for p, q, _ in lines]
    line_disc = []
    for (p, q, d), g in zip(lines, gross):
        if d is None:
            line_disc.append(0)
        elif d["type"] == "PERCENT":
            if not 1 <= d["value"] <= 100:
                raise ValueError("giam % ngoai khoang 1..100")
            line_disc.append(percent_amount(g, d["value"]))
        else:
            if d["value"] > g:
                raise ValueError("giam tien vuot tien dong")
            line_disc.append(d["value"])
    after = [g - x for g, x in zip(gross, line_disc)]
    subtotal = sum(after)
    od = 0
    if order_discount is not None:
        if order_discount["type"] == "PERCENT":
            if not 1 <= order_discount["value"] <= 100:
                raise ValueError("giam % toan don ngoai khoang 1..100")
            od = percent_amount(subtotal, order_discount["value"])
        else:
            od = order_discount["value"]
    if od + points_value > subtotal:
        raise ValueError("giam gia toan don + diem vuot tong tien")
    alloc = allocate(od + points_value, after)
    net = [a - x for a, x in zip(after, alloc)]
    if any(n < 0 for n in net):
        raise ValueError("net_line_amount am")
    return {
        "gross": gross, "lineDiscount": line_disc, "afterLine": after, "subtotal": subtotal,
        "orderDiscount": od, "pointsDiscount": points_value, "allocation": alloc, "net": net,
        "total": subtotal - od - points_value,
    }


def round1(x: Fraction) -> float:
    d = Decimal(x.numerator) / Decimal(x.denominator)
    return float(d.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def round2(x: Fraction) -> float:
    d = Decimal(x.numerator) / Decimal(x.denominator)
    return float(d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def ceil_fraction(x: Fraction) -> int:
    return -((-x.numerator) // x.denominator)


# =========================================================================== mini JSON Schema validator
# Chi ho tro tap tu khoa ma store-seed.schema.json dung (draft 2020-12). Gap tu khoa la thi bao loi
# de khong am tham bo qua rang buoc.

_ANNOTATIONS = {"$schema", "$id", "title", "description", "$comment", "default", "examples"}
_SUPPORTED = {"$ref", "$defs", "type", "enum", "const", "pattern", "minLength", "maxLength", "minimum",
              "maximum", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
              "oneOf", "anyOf", "allOf"}


class SchemaUnsupported(Exception):
    pass


def _type_ok(x, t: str) -> bool:
    if t == "object":
        return isinstance(x, dict)
    if t == "array":
        return isinstance(x, list)
    if t == "string":
        return isinstance(x, str)
    if t == "integer":
        return isinstance(x, int) and not isinstance(x, bool)
    if t == "number":
        return isinstance(x, (int, float)) and not isinstance(x, bool)
    if t == "boolean":
        return isinstance(x, bool)
    if t == "null":
        return x is None
    raise SchemaUnsupported(f"type {t}")


def schema_validate(inst, schema, root, path="$", errors=None, limit=50):
    if errors is None:
        errors = []
    if len(errors) >= limit:
        return errors
    if schema is True:
        return errors
    if schema is False:
        errors.append(f"{path}: không được phép")
        return errors
    for k in schema:
        if k not in _SUPPORTED and k not in _ANNOTATIONS:
            raise SchemaUnsupported(f"tu khoa schema chua ho tro: {k}")
    if "$ref" in schema:
        ref = schema["$ref"]
        if not ref.startswith("#/$defs/"):
            raise SchemaUnsupported(f"$ref {ref}")
        schema_validate(inst, root["$defs"][ref[len("#/$defs/"):]], root, path, errors, limit)
    if "type" in schema:
        types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_type_ok(inst, t) for t in types):
            errors.append(f"{path}: sai kiểu, cần {schema['type']}")
            return errors
    if "enum" in schema and inst not in schema["enum"]:
        errors.append(f"{path}: {inst!r} không thuộc {schema['enum']}")
    if "const" in schema and inst != schema["const"]:
        errors.append(f"{path}: cần đúng {schema['const']!r}")
    if isinstance(inst, str):
        if "pattern" in schema and not re.search(schema["pattern"], inst):
            errors.append(f"{path}: {inst!r} không khớp mẫu {schema['pattern']}")
        if "minLength" in schema and len(inst) < schema["minLength"]:
            errors.append(f"{path}: chuỗi quá ngắn")
        if "maxLength" in schema and len(inst) > schema["maxLength"]:
            errors.append(f"{path}: chuỗi quá dài")
    if isinstance(inst, (int, float)) and not isinstance(inst, bool):
        if "minimum" in schema and inst < schema["minimum"]:
            errors.append(f"{path}: {inst} < {schema['minimum']}")
        if "maximum" in schema and inst > schema["maximum"]:
            errors.append(f"{path}: {inst} > {schema['maximum']}")
    if isinstance(inst, dict):
        for r in schema.get("required", []):
            if r not in inst:
                errors.append(f"{path}: thiếu trường {r}")
        props = schema.get("properties", {})
        for k, v in inst.items():
            if k in props:
                schema_validate(v, props[k], root, f"{path}.{k}", errors, limit)
            elif "additionalProperties" in schema:
                ap = schema["additionalProperties"]
                if ap is False:
                    errors.append(f"{path}: trường lạ {k}")
                elif isinstance(ap, dict):
                    schema_validate(v, ap, root, f"{path}.{k}", errors, limit)
    if isinstance(inst, list):
        if "minItems" in schema and len(inst) < schema["minItems"]:
            errors.append(f"{path}: cần ít nhất {schema['minItems']} phần tử")
        if "maxItems" in schema and len(inst) > schema["maxItems"]:
            errors.append(f"{path}: quá {schema['maxItems']} phần tử")
        if "items" in schema:
            for i, it in enumerate(inst):
                schema_validate(it, schema["items"], root, f"{path}[{i}]", errors, limit)
    if "oneOf" in schema:
        ok = sum(1 for s in schema["oneOf"] if not schema_validate(inst, s, root, path, [], limit))
        if ok != 1:
            errors.append(f"{path}: khớp {ok} nhánh oneOf (cần đúng 1)")
    if "anyOf" in schema:
        if not any(not schema_validate(inst, s, root, path, [], limit) for s in schema["anyOf"]):
            errors.append(f"{path}: không khớp nhánh anyOf nào")
    for s in schema.get("allOf", []):
        schema_validate(inst, s, root, path, errors, limit)
    return errors


# =========================================================================== replay

def to_minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


class StoreReplay:
    """Phat lai toan bo su kien cua mot Cua hang; ghi vi pham vao self.violations."""

    def __init__(self, seed: dict, label: str):
        self.seed = seed
        self.label = label
        self.violations: list[str] = []
        st = seed["store"]
        self.earn_unit = st["loyalty"]["earnAmountPerPoint"]
        self.point_value = st["loyalty"]["pointValue"]
        self.setup_day = st["setupDayOffset"]
        self.setup_min = to_minutes(st["setupTime"])
        self.users = {}
        self.categories = {}
        self.suppliers = {}
        self.customers = {}
        self.products = {}
        self.var = {}
        self.ledger = []            # (sku, delta, docType, docKey, lineNo)
        self.loyalty = {}
        self.loyalty_ledger = []    # (phone, delta, kind, docKey)
        self.orders = {}
        self.returns = []
        self.order_seq = 0
        self.deactivated_products = set()
        self.deactivated_variants = set()

    def bad(self, msg: str):
        self.violations.append(f"{self.label}: {msg}")

    # ---- master data -------------------------------------------------------------
    def load_master(self):
        s = self.seed
        owners = 0
        for u in s["users"]:
            if u["login"] in self.users:
                self.bad(f"trùng tên đăng nhập {u['login']}")
            self.users[u["login"]] = u["role"]
            owners += u["role"] == "OWNER"
        if owners != 1:
            self.bad(f"cần đúng 1 Chủ cửa hàng, có {owners}")
        for c in s["categories"]:
            if c["code"] in self.categories:
                self.bad(f"trùng mã Danh mục {c['code']}")
            self.categories[c["code"]] = c["name"]
        for sp in s["suppliers"]:
            if sp["code"] in self.suppliers:
                self.bad(f"trùng mã Nhà cung cấp {sp['code']}")
            self.suppliers[sp["code"]] = sp
        for c in s["customers"]:
            if c["phone"] in self.customers:
                self.bad(f"trùng số điện thoại Khách hàng {c['phone']}")
            self.customers[c["phone"]] = c
            self.loyalty[c["phone"]] = 0
        barcodes = set()
        for p in s["products"]:
            if p["code"] in self.products:
                self.bad(f"trùng mã Sản phẩm {p['code']}")
            if p["categoryCode"] not in self.categories:
                self.bad(f"{p['code']}: Danh mục {p['categoryCode']} không tồn tại")
            self.products[p["code"]] = p
            vs = p["variants"]
            defaults = [v for v in vs if v["isDefault"]]
            if defaults and (len(vs) != 1 or defaults[0]["attributes"]):
                self.bad(f"{p['code']}: Biến thể mặc định phải là Biến thể duy nhất và không có thuộc tính")
            if not defaults:
                seen_attr = set()
                for v in vs:
                    key = tuple(sorted(v["attributes"].items()))
                    if not key or key in seen_attr:
                        self.bad(f"{v['sku']}: Biến thể không mặc định cần bộ thuộc tính riêng")
                    seen_attr.add(key)
            for v in vs:
                if v["sku"] in self.var:
                    self.bad(f"trùng SKU {v['sku']}")
                if v["barcode"] in barcodes:
                    self.bad(f"trùng mã vạch {v['barcode']} trong Cửa hàng")
                barcodes.add(v["barcode"])
                if v["trackLots"] != ("openingLot" in v) and v["openingQuantity"] > 0:
                    self.bad(f"{v['sku']}: trackLots và openingLot không khớp")
                if "openingLot" in v and not v["trackLots"]:
                    self.bad(f"{v['sku']}: có openingLot nhưng trackLots = false")
                self.var[v["sku"]] = {
                    "sku": v["sku"], "product": p["code"], "category": p["categoryCode"], "price": v["salePrice"],
                    "attributes": v["attributes"], "track": v["trackLots"], "min": v["minQuantity"], "active": True,
                    "stock": 0, "avg": v["openingUnitCost"], "lots": [], "lot_seq": 0,
                }

    # ---- ledger helpers ----------------------------------------------------------
    def post(self, sku, delta, doc_type, key, line_no):
        self.ledger.append((sku, delta, doc_type, key, line_no))

    def loyal(self, phone, delta, kind, key):
        self.loyalty[phone] += delta
        self.loyalty_ledger.append((phone, delta, kind, key))
        if self.loyalty[phone] < 0:
            self.bad(f"{key}: số dư Điểm tích lũy của {phone} âm ({self.loyalty[phone]})")

    def lot_add(self, v, code, expiry, qty):
        v["lot_seq"] += 1
        lot = {"code": code, "expiry": expiry, "remaining": qty, "seq": v["lot_seq"]}
        v["lots"].append(lot)
        return lot

    def lot_take_fefo(self, v, qty, key):
        alloc = []
        left = qty
        for lot in sorted(v["lots"], key=lambda l: (l["expiry"], l["seq"])):
            if left == 0:
                break
            take = min(lot["remaining"], left)
            if take:
                lot["remaining"] -= take
                left -= take
                alloc.append((lot, take))
        if left:
            self.bad(f"{key}: Lô hàng của {v['sku']} không đủ số lượng")
        return alloc

    @staticmethod
    def lot_restore(alloc, qty):
        """Tra lai `qty` vao cac lo da xuat cho dong nay, lo xuat sau duoc tra truoc."""
        left = qty
        for i in range(len(alloc) - 1, -1, -1):
            if left == 0:
                break
            lot, took = alloc[i]
            back = min(took, left)
            lot["remaining"] += back
            alloc[i] = (lot, took - back)
            left -= back
        return left

    # ---- events --------------------------------------------------------------------
    def build_events(self):
        s = self.seed
        ev = []
        for r in s["purchaseReceipts"]:
            ev.append((r["dayOffset"], to_minutes(r["time"]), EVENT_ORDER["RECEIPT"], r["ref"], "RECEIPT", r))
        for o in s["orders"]:
            ev.append((o["dayOffset"], to_minutes(o["time"]), EVENT_ORDER["ORDER"], o["ref"], "ORDER", o))
        for c in s["cancellations"]:
            ev.append((c["dayOffset"], to_minutes(c["time"]), EVENT_ORDER["CANCELLATION"], c["orderRef"], "CANCELLATION", c))
        for r in s["returns"]:
            ev.append((r["dayOffset"], to_minutes(r["time"]), EVENT_ORDER["RETURN"], r["ref"], "RETURN", r))
        for d in s["deactivations"]:
            ev.append((d["dayOffset"], to_minutes(d["time"]), EVENT_ORDER["DEACTIVATION"], d.get("sku") or d["productCode"], "DEACTIVATION", d))
        for t in s["stocktakes"]:
            ev.append((t["dayOffset"], to_minutes(t["time"]), EVENT_ORDER["STOCKTAKE"], t["ref"], "STOCKTAKE", t))
        ev.sort(key=lambda e: e[:4])
        seen = {}
        refs = defaultdict(set)
        for e in ev:
            if (e[0], e[1]) in seen:
                self.bad(f"hai sự kiện trùng thời điểm ngày {e[0]} {e[5]['time']}: {seen[(e[0], e[1])]} và {e[3]}")
            seen[(e[0], e[1])] = e[3]
            if (e[0], e[1]) <= (self.setup_day, self.setup_min):
                self.bad(f"{e[3]}: xảy ra trước hoặc cùng lúc khởi tạo Cửa hàng")
            if e[4] in ("RECEIPT", "ORDER", "RETURN", "STOCKTAKE"):
                if e[3] in refs[e[4]]:
                    self.bad(f"trùng ref {e[3]}")
                refs[e[4]].add(e[3])
        return ev

    def run(self):
        self.load_master()
        # Ton dau ky (OPENING) tai thoi diem khoi tao
        for p in self.seed["products"]:
            for v in p["variants"]:
                st = self.var[v["sku"]]
                q = v["openingQuantity"]
                st["avg"] = v["openingUnitCost"]
                if q > 0:
                    st["stock"] += q
                    self.post(v["sku"], q, "OPENING", v["sku"], 1)
                    if st["track"]:
                        lot = v.get("openingLot")
                        if lot:
                            self.lot_add(st, lot["lotCode"], lot["expiryDayOffset"], q)
        for day, minute, _, key, kind, obj in self.build_events():
            try:
                getattr(self, "on_" + kind.lower())(day, minute, key, obj)
            except (ValueError, KeyError, ZeroDivisionError) as ex:
                self.bad(f"{kind} {key}: không phát lại được ({type(ex).__name__}: {ex})")
        self.final_checks()
        return self

    def check_user(self, login, key, owner_only=False):
        if login not in self.users:
            self.bad(f"{key}: người dùng {login} không tồn tại")
            return None
        if owner_only and self.users[login] != "OWNER":
            self.bad(f"{key}: {login} không phải Chủ cửa hàng")
        return self.users[login]

    def on_receipt(self, day, minute, key, r):
        self.check_user(r["createdBy"], key, owner_only=True)
        if r["supplierCode"] not in self.suppliers:
            self.bad(f"{key}: Nhà cung cấp {r['supplierCode']} không tồn tại")
        for i, ln in enumerate(r["lines"], start=1):
            v = self.var.get(ln["sku"])
            if v is None:
                self.bad(f"{key}: SKU {ln['sku']} không tồn tại")
                continue
            if not v["active"]:
                self.bad(f"{key}: nhập hàng cho Biến thể đã ngừng kinh doanh {ln['sku']}")
            has_lot = "lotCode" in ln and "expiryDayOffset" in ln
            if v["track"] != has_lot or (("lotCode" in ln) != ("expiryDayOffset" in ln)):
                self.bad(f"{key}: dòng {i} {ln['sku']} thông tin Lô hàng không khớp trackLots")
            q, c = ln["quantity"], ln["unitCost"]
            # Ton am chi xay ra khi du lieu da sai (da bao vi pham); khi do coi nhu Q = 0 de phat lai tiep.
            v["avg"] = avg_cost_after_receipt(max(0, v["stock"]), v["avg"], q, c)
            v["stock"] += q
            self.post(ln["sku"], q, "RECEIPT", key, i)
            if v["track"] and has_lot:
                self.lot_add(v, ln["lotCode"], ln["expiryDayOffset"], q)

    def on_order(self, day, minute, key, o):
        self.check_user(o["sellerLogin"], key)
        self.order_seq += 1
        expect = f"DH-{self.order_seq:06d}"
        if o["expectedOrderCode"] != expect:
            self.bad(f"{key}: expectedOrderCode {o['expectedOrderCode']} nhưng thứ tự tạo cho {expect}")
        cust = o.get("customerPhone")
        if cust is not None and cust not in self.customers:
            self.bad(f"{key}: Khách hàng {cust} không tồn tại")
            cust = None
        points = o.get("pointsRedeemed", 0)
        if points and cust is None:
            self.bad(f"{key}: dùng điểm nhưng không gắn Khách hàng")
            points = 0
        if cust and points > self.loyalty[cust]:
            self.bad(f"{key}: dùng {points} điểm nhưng số dư chỉ {self.loyalty[cust]}")
        seen = set()
        price_lines = []
        for i, ln in enumerate(o["lines"], start=1):
            v = self.var.get(ln["sku"])
            if v is None:
                self.bad(f"{key}: SKU {ln['sku']} không tồn tại")
                return
            if ln["sku"] in seen:
                self.bad(f"{key}: SKU {ln['sku']} lặp trong một Đơn hàng")
            seen.add(ln["sku"])
            if not v["active"]:
                self.bad(f"{key}: bán Biến thể đã ngừng kinh doanh {ln['sku']}")
            if ln["quantity"] > v["stock"]:
                self.bad(f"{key}: bán {ln['quantity']} {ln['sku']} vượt Tồn kho {v['stock']}")
            price_lines.append((v["price"], ln["quantity"], ln.get("discount")))
        try:
            money = compute_order_money(price_lines, o.get("orderDiscount"), points * self.point_value)
        except ValueError as ex:
            self.bad(f"{key}: {ex}")
            return
        if money["total"] != o["quotedTotal"]:
            self.bad(f"{key}: quotedTotal {o['quotedTotal']} khác tổng tính lại {money['total']}")
        pay = o["payment"]
        if pay["method"] == "CASH" and pay["cashReceived"] < money["total"]:
            self.bad(f"{key}: tiền khách đưa {pay['cashReceived']} < tổng {money['total']}")
        lines = []
        for i, (ln, net) in enumerate(zip(o["lines"], money["net"]), start=1):
            v = self.var[ln["sku"]]
            q = ln["quantity"]
            unit_cost = v["avg"]
            v["stock"] -= q
            if v["stock"] < 0:
                self.bad(f"{key}: Tồn kho {ln['sku']} âm ({v['stock']})")
            alloc = self.lot_take_fefo(v, q, key) if v["track"] else []
            self.post(ln["sku"], -q, "SALE", key, i)
            lines.append({"sku": ln["sku"], "product": v["product"], "category": v["category"], "qty": q,
                          "price": v["price"], "net": net, "unitCost": unit_cost, "returned": 0, "refunded": 0,
                          "alloc": alloc})
        earned = 0
        if cust:
            if points:
                self.loyal(cust, -points, "REDEEM", key)
            earned = money["total"] // self.earn_unit
            if earned:
                self.loyal(cust, earned, "EARN", key)
        self.orders[key] = {
            "ref": key, "code": expect, "day": day, "minute": minute, "seller": o["sellerLogin"], "customer": cust,
            "status": "COMPLETED", "lines": lines, "total": money["total"], "subtotalList": sum(money["gross"]),
            "discount": sum(money["gross"]) - money["total"], "earned": earned, "redeemed": points,
            "refunded": 0, "returnCount": 0, "payment": pay["method"],
        }

    def on_cancellation(self, day, minute, key, c):
        o = self.orders.get(c["orderRef"])
        role = self.check_user(c["byLogin"], key)
        if o is None:
            self.bad(f"hủy {key}: Đơn hàng không tồn tại hoặc chưa được tạo trước thời điểm hủy")
            return
        if o["status"] != "COMPLETED":
            self.bad(f"hủy {key}: Đơn hàng đang ở trạng thái {o['status']}")
            return
        if o["returnCount"] > 0:
            self.bad(f"hủy {key}: Đơn hàng đã có Phiếu trả hàng (AD-17)")
            return
        if role == "STAFF" and (c["byLogin"] != o["seller"] or day != o["day"]):
            self.bad(f"hủy {key}: Nhân viên chỉ hủy Đơn hàng của mình trong ngày (FR-15)")
        for i, ln in enumerate(o["lines"], start=1):
            v = self.var[ln["sku"]]
            v["stock"] += ln["qty"]
            if v["track"]:
                self.lot_restore(ln["alloc"], ln["qty"])
            self.post(ln["sku"], ln["qty"], "CANCEL", key, i)
        if o["customer"]:
            net = o["redeemed"] - o["earned"]
            if self.loyalty[o["customer"]] + net < 0:
                self.bad(f"hủy {key}: trừ điểm làm số dư âm")
            if o["earned"]:
                self.loyal(o["customer"], -o["earned"], "REVERSE_EARN", key)
            if o["redeemed"]:
                self.loyal(o["customer"], o["redeemed"], "RESTORE_REDEEM", key)
        o["status"] = "CANCELLED"
        o["cancelDay"] = day

    def on_return(self, day, minute, key, r):
        o = self.orders.get(r["orderRef"])
        self.check_user(r["byLogin"], key)
        if o is None:
            self.bad(f"{key}: Đơn hàng {r['orderRef']} không tồn tại hoặc chưa được tạo")
            return
        if o["status"] != "COMPLETED":
            self.bad(f"{key}: trả hàng cho Đơn hàng trạng thái {o['status']}")
            return
        picks = []
        seen = set()
        for ln in r["lines"]:
            idx = ln["lineNo"] - 1
            if not 0 <= idx < len(o["lines"]):
                self.bad(f"{key}: lineNo {ln['lineNo']} không có trong Đơn hàng")
                return
            ol = o["lines"][idx]
            if ol["sku"] != ln["sku"]:
                self.bad(f"{key}: lineNo {ln['lineNo']} là {ol['sku']}, không phải {ln['sku']}")
            if idx in seen:
                self.bad(f"{key}: lineNo {ln['lineNo']} lặp trong một Phiếu trả hàng")
            seen.add(idx)
            if ln["quantity"] > ol["qty"] - ol["returned"]:
                self.bad(f"{key}: trả {ln['quantity']} vượt số đã mua trừ đã trả ({ol['qty'] - ol['returned']})")
                return
            picks.append((idx, ln["quantity"]))
        refunds = []
        for idx, q in picks:
            ol = o["lines"][idx]
            refunds.append(refund_amount(ol["net"], ol["qty"], ol["returned"], ol["refunded"], q))
        total_refund = sum(refunds)
        if total_refund != r["expectedRefundTotal"]:
            self.bad(f"{key}: expectedRefundTotal {r['expectedRefundTotal']} khác tiền hoàn tính lại {total_refund}")
        ret_lines = []
        for (idx, q), rf in zip(picks, refunds):
            ol = o["lines"][idx]
            ol["returned"] += q
            ol["refunded"] += rf
            v = self.var[ol["sku"]]
            v["stock"] += q
            if v["track"]:
                self.lot_restore(ol["alloc"], q)
            self.post(ol["sku"], q, "RETURN", key, idx + 1)
            ret_lines.append({"sku": ol["sku"], "product": ol["product"], "category": ol["category"], "qty": q,
                              "refund": rf, "unitCost": ol["unitCost"]})
        if o["customer"]:
            before = points_kept(o["total"], o["refunded"], self.earn_unit)
            after = points_kept(o["total"], o["refunded"] + total_refund, self.earn_unit)
            if before - after:
                self.loyal(o["customer"], -(before - after), "REVERSE_EARN", key)
        o["refunded"] += total_refund
        o["returnCount"] += 1
        if all(ol["returned"] == ol["qty"] for ol in o["lines"]):
            o["status"] = "RETURNED"
        self.returns.append({"ref": key, "order": o["ref"], "day": day, "lines": ret_lines, "refund": total_refund,
                             "method": r["refundMethod"]})

    def on_deactivation(self, day, minute, key, d):
        self.check_user(d["byLogin"], f"ngừng {key}", owner_only=True)
        p = self.products.get(d["productCode"])
        if p is None:
            self.bad(f"ngừng {key}: Sản phẩm không tồn tại")
            return
        if d["target"] == "PRODUCT":
            if d["productCode"] in self.deactivated_products:
                self.bad(f"ngừng {key}: Sản phẩm đã ngừng trước đó")
            self.deactivated_products.add(d["productCode"])
            for v in p["variants"]:
                self.var[v["sku"]]["active"] = False
        else:
            sku = d.get("sku")
            if sku is None or sku not in {v["sku"] for v in p["variants"]}:
                self.bad(f"ngừng {key}: thiếu sku hoặc sku không thuộc Sản phẩm")
                return
            if not self.var[sku]["active"]:
                self.bad(f"ngừng {key}: Biến thể đã ngừng trước đó")
            self.deactivated_variants.add(sku)
            self.var[sku]["active"] = False

    def on_stocktake(self, day, minute, key, t):
        self.check_user(t["createdBy"], key, owner_only=True)
        seen = set()
        for i, ln in enumerate(t["lines"], start=1):
            v = self.var.get(ln["sku"])
            if v is None:
                self.bad(f"{key}: SKU {ln['sku']} không tồn tại")
                continue
            if ln["sku"] in seen:
                self.bad(f"{key}: SKU {ln['sku']} lặp trong Phiếu kiểm kho")
            seen.add(ln["sku"])
            if ln["expectedSystemQuantity"] != v["stock"]:
                self.bad(f"{key}: {ln['sku']} expectedSystemQuantity {ln['expectedSystemQuantity']} khác Tồn kho {v['stock']}")
            delta = ln["countedQuantity"] - v["stock"]
            if v["track"] and delta < 0:
                self.lot_take_fefo(v, -delta, key)
            elif v["track"] and delta > 0:
                if not v["lots"]:
                    self.bad(f"{key}: tăng Tồn kho {ln['sku']} theo Lô hàng nhưng chưa có lô nào")
                else:
                    max(v["lots"], key=lambda l: (l["expiry"], l["seq"]))["remaining"] += delta
            v["stock"] = ln["countedQuantity"]      # dat theo so dem thuc te; Gia von khong doi (AD-15)
            self.post(ln["sku"], delta, "STOCKTAKE", key, i)

    # ---- invariants ------------------------------------------------------------------
    def final_checks(self):
        sums = defaultdict(int)
        for sku, delta, *_ in self.ledger:
            sums[sku] += delta
        for sku, v in self.var.items():
            if v["stock"] < 0:
                self.bad(f"Tồn kho cuối {sku} âm")
            if v["stock"] != sums[sku]:
                self.bad(f"Tồn kho {sku} = {v['stock']} khác tổng lịch sử xuất nhập {sums[sku]}")
            if v["track"] and v["stock"] != sum(l["remaining"] for l in v["lots"]):
                self.bad(f"Tồn kho {sku} khác tổng còn lại của các Lô hàng")
            if any(l["remaining"] < 0 for l in v["lots"]):
                self.bad(f"Lô hàng của {sku} có số còn lại âm")
        lsum = defaultdict(int)
        for phone, delta, *_ in self.loyalty_ledger:
            lsum[phone] += delta
        for phone, bal in self.loyalty.items():
            if bal != lsum[phone]:
                self.bad(f"số dư điểm {phone} = {bal} khác tổng lịch sử điểm {lsum[phone]}")
            if bal < 0:
                self.bad(f"số dư điểm {phone} âm")
        for p in self.seed["products"]:
            want = "INACTIVE" if p["code"] in self.deactivated_products else "ACTIVE"
            if p["status"] != want:
                self.bad(f"{p['code']}: status {p['status']} nhưng theo deactivations là {want}")
            for v in p["variants"]:
                want_v = "ACTIVE" if self.var[v["sku"]]["active"] else "INACTIVE"
                if v["status"] != want_v:
                    self.bad(f"{v['sku']}: status {v['status']} nhưng theo deactivations là {want_v}")


# =========================================================================== answers

class Answers:
    def __init__(self, rp: StoreReplay, anchor: date):
        self.rp = rp
        self.anchor = anchor
        self.seed = rp.seed
        self.pname = {p["code"]: p["name"] for p in self.seed["products"]}
        # su kien theo ngay
        self.daily = {}
        for d in range(rp.setup_day, 1):
            self.daily[d] = {"grossSales": 0, "discountTotal": 0, "refundTotal": 0, "cogsSales": 0,
                             "cogsReturns": 0, "orderCount": 0, "cancelledCount": 0, "returnCount": 0}
        self.sold = defaultdict(lambda: defaultdict(int))        # day -> sku -> qty
        self.sold_net = defaultdict(lambda: defaultdict(int))    # day -> product -> net
        self.sold_pq = defaultdict(lambda: defaultdict(int))     # day -> product -> qty
        self.ret = defaultdict(lambda: defaultdict(int))
        self.ret_amt = defaultdict(lambda: defaultdict(int))
        self.ret_pq = defaultdict(lambda: defaultdict(int))
        self.final_status_by_day = defaultdict(lambda: defaultdict(int))
        for o in rp.orders.values():
            dd = self.daily[o["day"]]
            self.final_status_by_day[o["day"]][o["status"]] += 1
            if o["status"] == "CANCELLED":
                dd["cancelledCount"] += 1
                continue
            dd["grossSales"] += o["total"]
            dd["discountTotal"] += o["discount"]
            dd["orderCount"] += 1
            for ln in o["lines"]:
                dd["cogsSales"] += ln["qty"] * ln["unitCost"]
                self.sold[o["day"]][ln["sku"]] += ln["qty"]
                self.sold_net[o["day"]][ln["product"]] += ln["net"]
                self.sold_pq[o["day"]][ln["product"]] += ln["qty"]
        for r in rp.returns:
            dd = self.daily[r["day"]]
            dd["refundTotal"] += r["refund"]
            dd["returnCount"] += 1
            for ln in r["lines"]:
                dd["cogsReturns"] += ln["qty"] * ln["unitCost"]
                self.ret[r["day"]][ln["sku"]] += ln["qty"]
                self.ret_amt[r["day"]][ln["product"]] += ln["refund"]
                self.ret_pq[r["day"]][ln["product"]] += ln["qty"]

    # ---- ranges ------------------------------------------------------------------
    def off(self, d: date) -> int:
        return (d - self.anchor).days

    def iso(self, off: int) -> str:
        return (self.anchor + timedelta(days=off)).isoformat()

    def days_in(self, lo: int, hi: int):
        return [d for d in range(max(lo, self.rp.setup_day), min(hi, 0) + 1)]

    def sum_daily(self, lo, hi, field):
        return sum(self.daily[d][field] for d in self.days_in(lo, hi))

    def q1(self, lo, hi):
        gross = self.sum_daily(lo, hi, "grossSales")
        refunds = self.sum_daily(lo, hi, "refundTotal")
        return {"fromDate": self.iso(lo), "toDate": self.iso(hi), "revenue": gross - refunds, "grossSales": gross,
                "refundTotal": refunds, "discountTotal": self.sum_daily(lo, hi, "discountTotal")}

    def q2(self, lo, hi):
        fully = sum(self.final_status_by_day[d]["RETURNED"] for d in self.days_in(lo, hi))
        return {"fromDate": self.iso(lo), "toDate": self.iso(hi), "orderCount": self.sum_daily(lo, hi, "orderCount"),
                "cancelledCount": self.sum_daily(lo, hi, "cancelledCount"), "fullyReturnedCount": fully,
                "returnCount": self.sum_daily(lo, hi, "returnCount")}

    def q3(self, lo, hi):
        revenue = self.sum_daily(lo, hi, "grossSales") - self.sum_daily(lo, hi, "refundTotal")
        cogs = self.sum_daily(lo, hi, "cogsSales") - self.sum_daily(lo, hi, "cogsReturns")
        return {"fromDate": self.iso(lo), "toDate": self.iso(hi), "grossProfit": revenue - cogs, "revenue": revenue,
                "cogs": cogs}

    def product_stats(self, lo, hi):
        stats = {}
        for code in self.pname:
            stats[code] = {"sold": 0, "ret": 0, "net": 0, "refund": 0}
        for d in self.days_in(lo, hi):
            for code, q in self.sold_pq[d].items():
                stats[code]["sold"] += q
            for code, a in self.sold_net[d].items():
                stats[code]["net"] += a
            for code, q in self.ret_pq[d].items():
                stats[code]["ret"] += q
            for code, a in self.ret_amt[d].items():
                stats[code]["refund"] += a
        return stats

    def product_active(self, code):
        p = self.rp.products[code]
        return p["code"] not in self.rp.deactivated_products and any(self.rp.var[v["sku"]]["active"] for v in p["variants"])

    def q4(self, lo, hi, limit=TOP_LIMIT):
        stats = self.product_stats(lo, hi)
        rows = []
        for code, s in stats.items():
            p = self.rp.products[code]
            rows.append({"productCode": code, "productName": p["name"], "categoryCode": p["categoryCode"],
                         "quantity": s["sold"] - s["ret"], "revenue": s["net"] - s["refund"],
                         "soldQuantity": s["sold"], "returnedQuantity": s["ret"], "active": self.product_active(code)})

        def ranked(items):
            out = []
            for i, r in enumerate(items[:limit], start=1):
                r = dict(r)
                r.pop("active")
                out.append({"rank": i, **r})
            return out

        top_q = sorted([r for r in rows if r["quantity"] > 0], key=lambda r: (-r["quantity"], -r["revenue"], r["productCode"]))
        top_r = sorted([r for r in rows if r["revenue"] > 0], key=lambda r: (-r["revenue"], -r["quantity"], r["productCode"]))
        bottom = sorted([r for r in rows if r["active"]], key=lambda r: (r["quantity"], r["revenue"], r["productCode"]))
        return {"fromDate": self.iso(lo), "toDate": self.iso(hi), "limit": limit, "topByQuantity": ranked(top_q),
                "topByRevenue": ranked(top_r), "bottomByQuantity": ranked(bottom)}

    def variant_units(self, window_days):
        lo = -(window_days - 1)
        units = defaultdict(int)
        for d in self.days_in(lo, 0):
            for sku, q in self.sold[d].items():
                units[sku] += q
            for sku, q in self.ret[d].items():
                units[sku] -= q
        return {sku: max(0, u) for sku, u in units.items()}, lo

    def variant_row(self, sku):
        v = self.rp.var[sku]
        return {"sku": sku, "productCode": v["product"], "productName": self.pname[v["product"]],
                "attributes": v["attributes"], "categoryCode": v["category"]}

    def q5(self):
        by_cat = defaultdict(lambda: [0, 0])
        products = []
        total_q = total_v = n_var = 0
        inactive_q = 0
        for p in self.seed["products"]:
            pq = pv = 0
            vrows = []
            for vd in p["variants"]:
                v = self.rp.var[vd["sku"]]
                if not v["active"]:
                    inactive_q += v["stock"]
                    continue
                val = v["stock"] * v["avg"]
                vrows.append({"sku": vd["sku"], "attributes": v["attributes"], "stockQuantity": v["stock"],
                              "avgUnitCost": v["avg"], "stockValue": val})
                pq += v["stock"]
                pv += val
                n_var += 1
            if not vrows:
                continue
            products.append({"productCode": p["code"], "productName": p["name"], "categoryCode": p["categoryCode"],
                             "stockQuantity": pq, "stockValue": pv, "variants": vrows})
            by_cat[p["categoryCode"]][0] += pq
            by_cat[p["categoryCode"]][1] += pv
            total_q += pq
            total_v += pv
        cats = [{"categoryCode": c, "categoryName": self.rp.categories[c], "stockQuantity": by_cat[c][0],
                 "stockValue": by_cat[c][1]} for c in self.rp.categories if c in by_cat]
        return {"asOf": self.anchor.isoformat(), "scope": "Biến thể đang kinh doanh",
                "total": {"stockQuantity": total_q, "stockValue": total_v, "variantCount": n_var,
                          "inactiveStockQuantity": inactive_q},
                "byCategory": cats, "byProduct": products}

    def slow_items(self):
        units, lo = self.variant_units(SLOW_WINDOW_DAYS)
        items = []
        for sku, v in self.rp.var.items():
            if not v["active"] or v["stock"] <= 0:
                continue
            u = units.get(sku, 0)
            days = None if u == 0 else Fraction(v["stock"] * SLOW_WINDOW_DAYS, u)
            if u == 0 or days > SLOW_DAYS_OF_STOCK:
                items.append((sku, v, u, days))
        items.sort(key=lambda t: (0 if t[3] is None else 1, -(t[3] or 0), -t[1]["stock"] * t[1]["avg"], t[0]))
        return items, lo

    def q7(self):
        items, lo = self.slow_items()
        out = []
        for i, (sku, v, u, days) in enumerate(items, start=1):
            out.append({"rank": i, **self.variant_row(sku), "stockQuantity": v["stock"], "unitsSold30": u,
                        "salesVelocity": round2(Fraction(u, SLOW_WINDOW_DAYS)),
                        "daysOfStock": None if days is None else round1(days), "avgUnitCost": v["avg"],
                        "salePrice": v["price"], "stockValue": v["stock"] * v["avg"]})
        return {"asOf": self.anchor.isoformat(), "windowDays": SLOW_WINDOW_DAYS, "fromDate": self.iso(lo),
                "toDate": self.iso(0), "thresholdDays": SLOW_DAYS_OF_STOCK, "count": len(out), "items": out}

    def q8(self):
        units, lo = self.variant_units(RESTOCK_WINDOW_DAYS)
        rows = []
        for sku, v in self.rp.var.items():
            if not v["active"]:
                continue
            u = units.get(sku, 0)
            days = None if u == 0 else Fraction(v["stock"] * RESTOCK_WINDOW_DAYS, u)
            reasons = []
            if days is not None and days < RESTOCK_DAYS_OF_STOCK:
                reasons.append("DAYS_OF_STOCK_LT_7")
            if v["stock"] < v["min"]:
                reasons.append("BELOW_MIN_QUANTITY")
            if not reasons:
                continue
            formula = ceil_fraction(Fraction(u * RESTOCK_COVER_DAYS, RESTOCK_WINDOW_DAYS) - v["stock"])
            suggested = max(formula, v["min"] - v["stock"])
            rows.append((sku, v, u, days, reasons, formula, suggested))
        rows.sort(key=lambda t: (1 if t[3] is None else 0, t[3] or 0, -(t[1]["min"] - t[1]["stock"]), t[0]))
        out = []
        for i, (sku, v, u, days, reasons, formula, suggested) in enumerate(rows, start=1):
            out.append({"rank": i, **self.variant_row(sku), "stockQuantity": v["stock"], "minQuantity": v["min"],
                        "unitsSold14": u, "salesVelocity": round2(Fraction(u, RESTOCK_WINDOW_DAYS)),
                        "daysOfStock": None if days is None else round1(days), "formulaQuantity": formula,
                        "suggestedQuantity": suggested, "reasons": reasons})
        return {"asOf": self.anchor.isoformat(), "windowDays": RESTOCK_WINDOW_DAYS, "fromDate": self.iso(lo),
                "toDate": self.iso(0), "thresholdDays": RESTOCK_DAYS_OF_STOCK, "coverDays": RESTOCK_COVER_DAYS,
                "count": len(out), "items": out}

    def q9(self):
        items, _ = self.slow_items()
        picked = [(sku, v, u, days) for sku, v, u, days in items if v["price"] > v["avg"]]
        picked.sort(key=lambda t: (-t[1]["stock"] * t[1]["avg"], t[0]))
        out = []
        for i, (sku, v, u, days) in enumerate(picked, start=1):
            out.append({"rank": i, **self.variant_row(sku), "salePrice": v["price"], "avgUnitCost": v["avg"],
                        "unitMargin": v["price"] - v["avg"], "stockQuantity": v["stock"],
                        "stockValue": v["stock"] * v["avg"], "unitsSold30": u,
                        "daysOfStock": None if days is None else round1(days)})
        return {"asOf": self.anchor.isoformat(), "basis": "Q7 (bán chậm) có giá bán > Giá vốn, giá trị Tồn kho giảm dần",
                "count": len(out), "items": out}

    def low_stock(self):
        rows = []
        for sku, v in sorted(self.rp.var.items()):
            if v["active"] and v["stock"] < v["min"]:
                rows.append({**self.variant_row(sku), "stockQuantity": v["stock"], "minQuantity": v["min"]})
        return rows

    def lots(self):
        n = self.seed["store"]["expiryWarningDays"]
        near, expired = [], []
        for sku, v in sorted(self.rp.var.items()):
            if not v["track"] or not v["active"]:
                continue
            for lot in sorted(v["lots"], key=lambda l: (l["expiry"], l["seq"])):
                if lot["remaining"] <= 0:
                    continue
                row = {"sku": sku, "productName": self.pname[v["product"]], "lotCode": lot["code"],
                       "expiryDate": self.iso(lot["expiry"]), "daysLeft": lot["expiry"], "remaining": lot["remaining"]}
                if lot["expiry"] < 0:
                    expired.append(row)
                elif lot["expiry"] <= n:
                    near.append(row)
        key = lambda r: (r["daysLeft"], r["sku"], r["lotCode"])
        return {"warningDays": n, "nearExpiry": sorted(near, key=key), "expired": sorted(expired, key=key)}


def standard_ranges(anchor: date):
    a = anchor
    monday = a - timedelta(days=a.weekday())
    first = a.replace(day=1)
    nxt = (first + timedelta(days=32)).replace(day=1)
    last_of_month = nxt - timedelta(days=1)
    prev_last = first - timedelta(days=1)
    prev_first = prev_last.replace(day=1)
    same_day = min(a.day, prev_last.day)
    r = {
        "today": (a, a),
        "yesterday": (a - timedelta(days=1), a - timedelta(days=1)),
        "this_week": (monday, monday + timedelta(days=6)),
        "last_week": (monday - timedelta(days=7), monday - timedelta(days=1)),
        "this_month": (first, last_of_month),
        "last_month": (prev_first, prev_last),
        "month_to_date": (first, a),
        "last_month_to_date": (prev_first, prev_first + timedelta(days=same_day - 1)),
        "last_7_days": (a - timedelta(days=6), a),
        "previous_7_days": (a - timedelta(days=13), a - timedelta(days=7)),
        "last_30_days": (a - timedelta(days=29), a),
        "previous_30_days": (a - timedelta(days=59), a - timedelta(days=30)),
    }
    return r


COMPARISONS = [
    ("this_month_vs_last_month", "this_month", "last_month"),
    ("month_to_date_vs_last_month_to_date", "month_to_date", "last_month_to_date"),
    ("this_week_vs_last_week", "this_week", "last_week"),
    ("last_7_days_vs_previous_7_days", "last_7_days", "previous_7_days"),
    ("last_30_days_vs_previous_30_days", "last_30_days", "previous_30_days"),
    ("today_vs_yesterday", "today", "yesterday"),
]


def compare(a: int, b: int) -> dict:
    return {"a": a, "b": b, "diff": a - b, "changePct": None if b == 0 else round1(Fraction((a - b) * 100, b))}


def store_answers(rp: StoreReplay, anchor: date) -> dict:
    an = Answers(rp, anchor)
    ranges = standard_ranges(anchor)
    offs = {k: (an.off(lo), an.off(hi)) for k, (lo, hi) in ranges.items()}
    offs["all_data"] = (rp.setup_day, 0)
    q1 = {k: an.q1(*v) for k, v in offs.items()}
    q2 = {k: an.q2(*v) for k, v in offs.items()}
    q3 = {k: an.q3(*v) for k, v in offs.items()}
    q4 = {k: an.q4(*v) for k, v in offs.items()}
    q6 = {}
    for name, ka, kb in COMPARISONS:
        q6[name] = {
            "periodA": {"fromDate": q1[ka]["fromDate"], "toDate": q1[ka]["toDate"]},
            "periodB": {"fromDate": q1[kb]["fromDate"], "toDate": q1[kb]["toDate"]},
            "Q1": compare(q1[ka]["revenue"], q1[kb]["revenue"]),
            "Q2": compare(q2[ka]["orderCount"], q2[kb]["orderCount"]),
            "Q3": compare(q3[ka]["grossProfit"], q3[kb]["grossProfit"]),
        }
    daily = []
    for d in range(rp.setup_day, 1):
        x = an.daily[d]
        rev = x["grossSales"] - x["refundTotal"]
        cogs = x["cogsSales"] - x["cogsReturns"]
        daily.append({"date": an.iso(d), "dayOffset": d, "revenue": rev, "grossSales": x["grossSales"],
                      "discountTotal": x["discountTotal"], "refundTotal": x["refundTotal"], "cogs": cogs,
                      "grossProfit": rev - cogs, "orderCount": x["orderCount"], "cancelledCount": x["cancelledCount"],
                      "returnCount": x["returnCount"]})
    weekly = []
    start = anchor + timedelta(days=rp.setup_day)
    wk = start - timedelta(days=start.weekday())
    while wk <= anchor:
        lo, hi = an.off(wk), an.off(wk + timedelta(days=6))
        weekly.append({"weekStart": an.iso(lo), "weekEnd": an.iso(hi), "revenue": an.q1(lo, hi)["revenue"],
                       "orderCount": an.q2(lo, hi)["orderCount"], "grossProfit": an.q3(lo, hi)["grossProfit"]})
        wk += timedelta(days=7)
    monthly = []
    m = start.replace(day=1)
    while m <= anchor:
        nxt = (m + timedelta(days=32)).replace(day=1)
        lo, hi = an.off(m), an.off(nxt - timedelta(days=1))
        monthly.append({"month": m.strftime("%Y-%m"), "fromDate": an.iso(lo), "toDate": an.iso(hi),
                        "revenue": an.q1(lo, hi)["revenue"], "orderCount": an.q2(lo, hi)["orderCount"],
                        "grossProfit": an.q3(lo, hi)["grossProfit"]})
        m = nxt
    s = rp.seed
    statuses = defaultdict(int)
    for o in rp.orders.values():
        statuses[o["status"]] += 1
    top5 = an.q4(-(DASHBOARD_WINDOW_DAYS - 1), 0, DASHBOARD_TOP)["topByQuantity"]
    return {
        "storeName": s["store"]["name"],
        "storeKind": s["store"]["kind"],
        "summary": {
            "products": len(s["products"]),
            "variants": sum(len(p["variants"]) for p in s["products"]),
            "activeVariants": sum(1 for v in rp.var.values() if v["active"]),
            "lotTrackedVariants": sum(1 for v in rp.var.values() if v["track"]),
            "customers": len(s["customers"]),
            "suppliers": len(s["suppliers"]),
            "orders": len(s["orders"]),
            "ordersCompleted": statuses["COMPLETED"],
            "ordersCancelled": statuses["CANCELLED"],
            "ordersFullyReturned": statuses["RETURNED"],
            "purchaseReceipts": len(s["purchaseReceipts"]),
            "purchaseReceiptLines": sum(len(r["lines"]) for r in s["purchaseReceipts"]),
            "stocktakes": len(s["stocktakes"]),
            "returns": len(s["returns"]),
            "cancellations": len(s["cancellations"]),
            "deactivations": len(s["deactivations"]),
            "pointsRedeemedOrders": sum(1 for o in s["orders"] if o.get("pointsRedeemed")),
        },
        "invariants": {
            "stockNeverNegative": True,
            "stockEqualsLedger": True,
            "loyaltyBalanceEqualsLedger": True,
            "loyaltyNeverNegative": True,
            "lotsEqualStock": True,
            "ledgerEntries": len(rp.ledger),
            "loyaltyLedgerEntries": len(rp.loyalty_ledger),
            "maxOrderCode": f"DH-{rp.order_seq:06d}",
            "loyaltyBalanceTotal": sum(rp.loyalty.values()),
        },
        "Q1": q1, "Q2": q2, "Q3": q3, "Q4": q4, "Q5": an.q5(), "Q6": q6, "Q7": an.q7(), "Q8": an.q8(), "Q9": an.q9(),
        "dashboard": {"today": {"revenue": q1["today"]["revenue"], "orderCount": q2["today"]["orderCount"]},
                      "top5Last7Days": top5, "lowStock": an.low_stock()},
        "lots": an.lots(),
        "daily": daily,
        "weekly": weekly,
        "monthly": monthly,
    }


def fingerprint(seed: dict) -> str:
    canon = json.dumps(seed, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canon).hexdigest()


def build_expected(seeds: dict[str, dict], anchor: date, replays: dict[str, StoreReplay]) -> dict:
    ranges = standard_ranges(anchor)
    return {
        "formatVersion": OUTPUT_FORMAT_VERSION,
        "generatedBy": "eval/answers/compute_answers.py",
        "anchorDate": anchor.isoformat(),
        "timezone": TIMEZONE,
        "definitions": {
            "revenue": "Σ quotedTotal của Đơn hàng không bị hủy, theo ngày tạo đơn − Σ tiền hoàn, theo ngày lập Phiếu trả hàng",
            "orderCount": "số Đơn hàng không bị hủy, theo ngày tạo (gồm cả đơn sau đó bị trả toàn bộ)",
            "grossProfit": "doanh thu − (Σ số lượng bán × unit_cost lúc bán − Σ số lượng trả × unit_cost của dòng gốc)",
            "salesVelocity": "(số lượng bán − số lượng trả trong N ngày gần nhất, tối thiểu 0) ÷ N; N ngày tính cả ngày neo",
            "slow": "Biến thể đang kinh doanh, Tồn kho > 0 và (không bán được trong 30 ngày hoặc Tồn kho ÷ Tốc độ bán 30 ngày > 30)",
            "restock": "Biến thể đang kinh doanh có (Tồn kho ÷ Tốc độ bán 14 ngày < 7) hoặc Tồn kho < Ngưỡng tồn tối thiểu",
            "restockQuantity": "formulaQuantity = ⌈Tốc độ bán 14 ngày × 7 − Tồn kho⌉ (PRD); suggestedQuantity = max(formulaQuantity, Ngưỡng tồn tối thiểu − Tồn kho)",
            "priority": "Biến thể thuộc Q7 có giá bán > Giá vốn, sắp theo giá trị Tồn kho giảm dần",
            "rounding": "tiền là số nguyên đồng; daysOfStock, changePct làm tròn 1 chữ số, salesVelocity 2 chữ số, half-up",
            "constants": {"slowWindowDays": SLOW_WINDOW_DAYS, "slowDaysOfStock": SLOW_DAYS_OF_STOCK,
                          "restockWindowDays": RESTOCK_WINDOW_DAYS, "restockDaysOfStock": RESTOCK_DAYS_OF_STOCK,
                          "restockCoverDays": RESTOCK_COVER_DAYS, "q4Limit": TOP_LIMIT},
        },
        "ranges": {**{k: {"fromDate": lo.isoformat(), "toDate": hi.isoformat()} for k, (lo, hi) in ranges.items()},
                   "all_data": {"fromDate": (anchor + timedelta(days=min(r.setup_day for r in replays.values()))).isoformat(),
                                "toDate": anchor.isoformat()}},
        "seedFiles": {k: {"path": f"deploy/seed/{k}.json", "sha256Canonical": fingerprint(v)} for k, v in seeds.items()},
        "stores": {k: store_answers(replays[k], anchor) for k in seeds},
    }


# =========================================================================== output helpers

def _flat(v) -> bool:
    if isinstance(v, dict):
        return all(not isinstance(x, (dict, list)) for x in v.values())
    if isinstance(v, list):
        return all(not isinstance(x, (dict, list)) for x in v)
    return True


def dumps_pretty(obj, indent=0) -> str:
    """JSON: dong du lieu (chi chua gia tri don hoac object/mang phang) nam tren mot dong; con lai thut le 2 cach."""
    pad = "  " * indent
    if isinstance(obj, dict):
        if not obj:
            return "{}"
        one = json.dumps(obj, ensure_ascii=False, separators=(", ", ": "))
        if all(_flat(v) for v in obj.values()) and ("rank" in obj or "sku" in obj or len(one) <= 320):
            return one
        parts = [f"{pad}  {json.dumps(k, ensure_ascii=False)}: {dumps_pretty(v, indent + 1)}" for k, v in obj.items()]
        return "{\n" + ",\n".join(parts) + "\n" + pad + "}"
    if isinstance(obj, list):
        if not obj:
            return "[]"
        if all(not isinstance(v, (dict, list)) for v in obj):
            return json.dumps(obj, ensure_ascii=False, separators=(", ", ": "))
        parts = [pad + "  " + dumps_pretty(v, indent + 1) for v in obj]
        return "[\n" + ",\n".join(parts) + "\n" + pad + "]"
    return json.dumps(obj, ensure_ascii=False)


def diff_paths(a, b, path="$", out=None, limit=20):
    if out is None:
        out = []
    if len(out) >= limit:
        return out
    if type(a) is not type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        out.append(f"{path}: {str(a)[:80]} != {str(b)[:80]}")
    elif isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                out.append(f"{path}.{k}: chỉ có ở một bên")
            else:
                diff_paths(a[k], b[k], f"{path}.{k}", out, limit)
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append(f"{path}: độ dài {len(a)} != {len(b)}")
        for i, (x, y) in enumerate(zip(a, b)):
            diff_paths(x, y, f"{path}[{i}]", out, limit)
    elif a != b:
        out.append(f"{path}: {a!r} != {b!r}")
    return out


def resolve_anchor(cli: str | None) -> date:
    value = cli or os.environ.get("SEED_ANCHOR_DATE") or DEFAULT_ANCHOR
    return date.fromisoformat(value.strip())


def load_seeds(seed_dir: Path) -> dict[str, dict]:
    seeds = {}
    for p in sorted(seed_dir.glob("store-*.json")):
        with open(p, encoding="utf-8") as f:
            seeds[p.stem] = json.load(f)
    return seeds


def validate_and_replay(seeds: dict[str, dict], schema: dict):
    problems = []
    replays = {}
    logins = {}
    for name, seed in seeds.items():
        errs = schema_validate(seed, schema, schema)
        problems += [f"{name}: schema: {e}" for e in errs]
        if errs:
            continue
        rp = StoreReplay(seed, name).run()
        problems += rp.violations
        replays[name] = rp
        for u in seed["users"]:
            if u["login"] in logins:
                problems.append(f"tên đăng nhập {u['login']} trùng giữa {logins[u['login']]} và {name} (login_key unique toàn hệ thống)")
            logins[u["login"]] = name
    return problems, replays


# =========================================================================== self-test

def _mini_seed() -> dict:
    """Cua hang thu 1 San pham, cac so duoi day tinh tay (xem selftest)."""
    return {
        "formatVersion": 1,
        "generator": {"script": "selftest", "randomSeed": 0, "canonicalAnchorDate": "2026-12-16"},
        "defaultPassword": "Demo@2026",
        "store": {"code": "store-t", "name": "Cửa hàng thử", "kind": "GROCERY", "address": "1 Đường Thử", "phone": "0900000000",
                  "invoiceFooter": "", "bankAccount": {"bankBin": "970436", "accountNumber": "000000", "accountName": "TEST"},
                  "loyalty": {"earnAmountPerPoint": 10000, "pointValue": 100}, "expiryWarningDays": 15,
                  "setupDayOffset": -3, "setupTime": "08:00"},
        "users": [{"login": "0900000000", "displayName": "Chủ", "role": "OWNER"},
                  {"login": "nv.test", "displayName": "Nhân viên", "role": "STAFF"}],
        "categories": [{"code": "C1", "name": "Danh mục 1"}],
        "suppliers": [{"code": "S1", "name": "NCC 1", "phone": "0900000001", "address": "x"}],
        "customers": [{"phone": "0911111111", "name": "Khách 1"}],
        "products": [{"code": "P1", "name": "Hàng X", "categoryCode": "C1", "unit": "cái", "status": "ACTIVE",
                      "variants": [{"sku": "P1", "barcode": "8930000000001", "isDefault": True, "attributes": {},
                                    "salePrice": 15000, "openingQuantity": 10, "openingUnitCost": 10000,
                                    "minQuantity": 20, "trackLots": False, "status": "ACTIVE"}]}],
        "purchaseReceipts": [{"ref": "T-REC-001", "supplierCode": "S1", "dayOffset": -1, "time": "07:00",
                              "createdBy": "0900000000", "lines": [{"sku": "P1", "quantity": 10, "unitCost": 11000}]}],
        "stocktakes": [],
        "orders": [
            {"ref": "T-ORD-0001", "expectedOrderCode": "DH-000001", "dayOffset": -2, "time": "09:00",
             "sellerLogin": "nv.test", "customerPhone": "0911111111", "lines": [{"sku": "P1", "quantity": 4}],
             "payment": {"method": "CASH", "cashReceived": 100000}, "quotedTotal": 60000},
            {"ref": "T-ORD-0002", "expectedOrderCode": "DH-000002", "dayOffset": 0, "time": "09:00",
             "sellerLogin": "nv.test", "lines": [{"sku": "P1", "quantity": 2, "discount": {"type": "PERCENT", "value": 10}}],
             "payment": {"method": "BANK_TRANSFER"}, "quotedTotal": 27000},
        ],
        "cancellations": [],
        "returns": [{"ref": "T-RET-001", "orderRef": "T-ORD-0001", "dayOffset": 0, "time": "10:00", "byLogin": "nv.test",
                     "refundMethod": "CASH", "lines": [{"lineNo": 1, "sku": "P1", "quantity": 1}], "expectedRefundTotal": 15000}],
        "deactivations": [],
    }


def selftest(schema: dict) -> int:
    fails = []

    def eq(name, got, want):
        if got != want:
            fails.append(f"{name}: được {got!r}, cần {want!r}")

    # Lam tron half-up khi giam %
    eq("percent 7% của 37035", percent_amount(37035, 7), 2592)          # 2592.45 -> 2592
    eq("percent 15% của 1230", percent_amount(1230, 15), 185)           # 184.5 -> 185 (không phải 184)
    eq("percent 25% của 12345", percent_amount(12345, 25), 3086)        # 3086.25 -> 3086
    # Chia giam gia toan don: floor cho dong truoc, du cho dong cuoi
    m = compute_order_money([(12345, 3, {"type": "PERCENT", "value": 7}), (25000, 2, None), (15557, 1, None)],
                            {"type": "AMOUNT", "value": 10000}, 0)
    eq("afterLine", m["afterLine"], [34443, 50000, 15557])               # 37035-2592; 50000; 15557
    eq("allocation 10000", m["allocation"], [3444, 5000, 1556])          # 3444.3 -> 3444; 5000; 10000-8444
    eq("net", m["net"], [30999, 45000, 14001])
    eq("total", m["total"], 90000)
    m2 = compute_order_money([(12345, 3, {"type": "PERCENT", "value": 7}), (25000, 2, None), (15557, 1, None)],
                             {"type": "PERCENT", "value": 5}, 20 * 200)
    eq("order % + điểm: allocation", m2["allocation"], [3099, 4500, 1401])  # D = 5000 + 4000; 3099.87 -> 3099
    eq("order % + điểm: total", m2["total"], 91000)
    # Gia von binh quan AD-15
    eq("avg 10@12000 + 5@13000", avg_cost_after_receipt(10, 12000, 5, 13000), 12333)   # 12333.33
    eq("avg 1@10000 + 1@10001", avg_cost_after_receipt(1, 10000, 1, 10001), 10001)     # 10000.5 -> 10001
    eq("avg 3@10001 + 1@10000", avg_cost_after_receipt(3, 10001, 1, 10000), 10001)     # 10000.75
    eq("avg Q=0", avg_cost_after_receipt(0, 999, 7, 5000), 5000)
    # Tien hoan AD-8
    eq("hoàn 1/3 lần 1", refund_amount(100000, 3, 0, 0, 1), 33333)
    eq("hoàn 1/3 lần 2", refund_amount(100000, 3, 1, 33333, 1), 33333)
    eq("hoàn lần cuối nhận phần dư", refund_amount(100000, 3, 2, 66666, 1), 33334)
    eq("hoàn 3/4 của 90001", refund_amount(90001, 4, 0, 0, 3), 67500)                 # 67500.75 -> 67500
    eq("hoàn lần cuối 1/4", refund_amount(90001, 4, 3, 67500, 1), 22501)
    # Diem tich luy: tra 2 lan het don 95000 tru dung 9 diem da cong
    eq("điểm sau lần trả 1", points_kept(95000, 0, 10000) - points_kept(95000, 47500, 10000), 5)
    eq("điểm sau lần trả 2", points_kept(95000, 47500, 10000) - points_kept(95000, 95000, 10000), 4)

    # Phat lai Cua hang thu
    seed = _mini_seed()
    errs = schema_validate(seed, schema, schema)
    eq("schema của Cửa hàng thử", errs, [])
    rp = StoreReplay(seed, "store-t").run()
    eq("vi phạm Cửa hàng thử", rp.violations, [])
    v = rp.var["P1"]
    eq("Tồn kho cuối", v["stock"], 15)                 # 10 - 4 + 10 - 2 + 1
    eq("Giá vốn sau nhập", v["avg"], 10625)            # (6*10000 + 10*11000)/16; trả hàng không đổi
    eq("điểm còn lại", rp.loyalty["0911111111"], 4)    # +6 rồi -2 khi hoàn 15000 (60000 -> 45000)
    a = store_answers(rp, date(2026, 12, 16))
    eq("Q1 hôm nay", a["Q1"]["today"]["revenue"], 12000)          # 27000 - 15000
    eq("Q3 hôm nay", a["Q3"]["today"]["grossProfit"], 750)        # (27000-21250) - (15000-10000)
    eq("Q1 7 ngày", a["Q1"]["last_7_days"]["revenue"], 72000)
    eq("Q3 7 ngày", a["Q3"]["last_7_days"]["grossProfit"], 20750)
    eq("Q2 7 ngày", a["Q2"]["last_7_days"]["orderCount"], 2)
    eq("Q5 giá trị", a["Q5"]["total"]["stockValue"], 159375)      # 15 * 10625
    eq("Q7 ngày đủ bán", a["Q7"]["items"][0]["daysOfStock"], 90.0)  # 15 / (5/30)
    q8 = a["Q8"]["items"][0]
    eq("Q8 lý do", q8["reasons"], ["BELOW_MIN_QUANTITY"])
    eq("Q8 công thức PRD", q8["formulaQuantity"], -12)            # ceil(5*7/14 - 15)
    eq("Q8 gợi ý", q8["suggestedQuantity"], 5)                    # max(-12, 20 - 15)
    eq("Q9", [x["sku"] for x in a["Q9"]["items"]], ["P1"])

    # Du lieu sai phai bi bat
    bad = _mini_seed()
    bad["orders"][1]["lines"][0]["quantity"] = 50
    bad["orders"][1]["quotedTotal"] = 675000
    rp_bad = StoreReplay(bad, "bad").run()
    if not any("vượt Tồn kho" in x for x in rp_bad.violations):
        fails.append("không phát hiện bán vượt Tồn kho")
    bad2 = _mini_seed()
    bad2["cancellations"].append({"orderRef": "T-ORD-0001", "dayOffset": 0, "time": "11:00", "byLogin": "0900000000",
                                  "reason": "thử"})
    rp_bad2 = StoreReplay(bad2, "bad2").run()
    if not any("Phiếu trả hàng" in x for x in rp_bad2.violations):
        fails.append("không phát hiện hủy đơn đã có Phiếu trả hàng")
    bad3 = _mini_seed()
    bad3["returns"][0]["lines"][0]["quantity"] = 5
    rp_bad3 = StoreReplay(bad3, "bad3").run()
    if not any("vượt số đã mua" in x for x in rp_bad3.violations):
        fails.append("không phát hiện trả vượt số đã mua")
    bad4 = _mini_seed()
    bad4["orders"][0]["quotedTotal"] = 59999
    if not any("quotedTotal" in x for x in StoreReplay(bad4, "bad4").run().violations):
        fails.append("không phát hiện quotedTotal sai")

    if fails:
        print("SELFTEST THẤT BẠI:")
        for f in fails:
            print("  -", f)
        return 3
    print("selftest: đạt (quy tắc tiền AD-8, Giá vốn AD-15, điểm, phát lại Cửa hàng thử, phát hiện dữ liệu sai)")
    return 0


# =========================================================================== main

def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="Tính đáp án độc lập Q1–Q9 từ deploy/seed/*.json")
    ap.add_argument("--anchor", help="ngày neo YYYY-MM-DD (mặc định: SEED_ANCHOR_DATE hoặc 2026-12-16)")
    ap.add_argument("--seed-dir", type=Path, default=SEED_DIR)
    ap.add_argument("--out", type=Path, default=EXPECTED_PATH)
    ap.add_argument("--check", action="store_true", help="không ghi; so với file --out")
    ap.add_argument("--selftest", action="store_true", help="chạy kiểm tra quy tắc tiền bằng số tính tay")
    args = ap.parse_args(argv)

    with open(SCHEMA_PATH, encoding="utf-8") as f:
        schema = json.load(f)
    if args.selftest:
        return selftest(schema)

    anchor = resolve_anchor(args.anchor)
    seeds = load_seeds(args.seed_dir)
    if not seeds:
        print(f"không tìm thấy store-*.json trong {args.seed_dir}", file=sys.stderr)
        return 1
    problems, replays = validate_and_replay(seeds, schema)
    if problems:
        print(f"DỮ LIỆU MẪU VI PHẠM {len(problems)} quy tắc/bất biến:", file=sys.stderr)
        for p in problems[:100]:
            print("  -", p, file=sys.stderr)
        return 1
    expected = build_expected(seeds, anchor, replays)
    for name, st in expected["stores"].items():
        sm = st["summary"]
        print(f"{name} ({st['storeName']}): {sm['products']} Sản phẩm, {sm['variants']} Biến thể, {sm['orders']} Đơn hàng, "
              f"{sm['returns']} Phiếu trả, {sm['cancellations']} hủy; bất biến đạt")
    text = dumps_pretty(expected) + "\n"
    if args.check:
        if not args.out.exists():
            print(f"thiếu {args.out}; chạy lại không có --check để tạo", file=sys.stderr)
            return 2
        with open(args.out, encoding="utf-8") as f:
            current = json.load(f)
        if current.get("anchorDate") != expected["anchorDate"]:
            print(f"{args.out} tính cho ngày neo {current.get('anchorDate')}, đang kiểm với {expected['anchorDate']}",
                  file=sys.stderr)
            return 2
        diffs = diff_paths(current, json.loads(text))
        if diffs:
            print(f"{args.out} KHÔNG khớp dữ liệu mẫu hiện tại (chạy lại để cập nhật):", file=sys.stderr)
            for d in diffs:
                print("  -", d, file=sys.stderr)
            return 2
        print(f"check: đạt; {args.out} khớp dữ liệu mẫu (ngày neo {anchor.isoformat()})")
        return 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"đã ghi {args.out} (ngày neo {anchor.isoformat()})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
