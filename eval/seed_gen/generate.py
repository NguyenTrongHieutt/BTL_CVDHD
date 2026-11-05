#!/usr/bin/env python3
"""Sinh du lieu mau cho 2 Cua hang (task CB-06).

Dau ra:
    deploy/seed/store-a.json   Tap hoa Lan    (tap hoa / tien loi, co Lo hang va han su dung)
    deploy/seed/store-b.json   Thoi trang Mai (thoi trang, Bien the mau / size)

Tinh chat:
- Chi dung thu vien chuan; chay bang `py -3.14` hoac `uv run python`.
- Tat dinh: hat giong ngau nhien co dinh, chay lai luon ra dung tung byte.
- Mo phong Ton kho (theo Bien the), Lo hang (FEFO), Gia von binh quan (AD-15) va
  Diem tich luy trong luc sinh, nen du lieu luon dung quy tac nghiep vu:
  khong ban vuot ton, tra khong qua so da mua tru so da tra, chi huy don chua co
  Phieu tra hang, so du diem khong am, khong ban/nhap Bien the da ngung kinh doanh.
- Thoi gian tuong doi: dayOffset (<= 0) so voi SEED_ANCHOR_DATE + gio "HH:mm"
  theo Asia/Ho_Chi_Minh. Xem deploy/seed/README.md.

Chay tu thu muc goc repo (product/):
    py -3.14 eval/seed_gen/generate.py           # ghi lai deploy/seed/store-*.json
    py -3.14 eval/seed_gen/generate.py --check   # chi kiem tra file tren dia khop ban sinh lai
"""

from __future__ import annotations

import argparse
import heapq
import json
import math
import random
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT_DIR = REPO_ROOT / "deploy" / "seed"

RANDOM_SEED = 20261216
# Moc chuan dung de xep thu trong tuan, ngay le, cuoi thang khi sinh du lieu.
# Du lieu van tuong doi (dayOffset), nap voi moc khac cung duoc; chi lich thu/ngay le lech theo.
CANONICAL_ANCHOR = date(2026, 12, 16)
FORMAT_VERSION = 1
DEMO_PASSWORD = "Demo@2026"

SETUP_DAY = -61
SETUP_MINUTE = 8 * 60
FIRST_RECEIPT_DAY = -60
FIRST_ORDER_DAY = -59
LAST_DAY = 0
DAY0_CUTOFF = 11 * 60          # moi su kien cua ngay neo (dayOffset 0) truoc 11:00
ORDER_TARGET = 500             # PRD 6.1: khoang 500 Don hang / Cua hang / 60 ngay

POP_WEIGHT = {"H": 10.0, "M": 4.0, "L": 1.2, "S": 0.15}


# --------------------------------------------------------------------------- money rules
# Ban sao doc lap voi eval/answers/compute_answers.py (khong import lan nhau).

def half_up_div(num: int, den: int) -> int:
    """Chia nguyen, lam tron half-up (num >= 0, den > 0)."""
    if num < 0 or den <= 0:
        raise ValueError(f"half_up_div({num}, {den})")
    return (2 * num + den) // (2 * den)


def percent_of(base: int, pct: int) -> int:
    return half_up_div(base * pct, 100)


def allocate_pro_rata(total: int, weights: list[int]) -> list[int]:
    """Chia `total` theo ty le `weights`: cac dong truoc lay phan nguyen (floor), dong cuoi nhan phan du."""
    if total == 0:
        return [0] * len(weights)
    s = sum(weights)
    if s <= 0:
        raise ValueError("allocate_pro_rata: tong trong so = 0")
    shares = [total * w // s for w in weights[:-1]]
    shares.append(total - sum(shares))
    return shares


def weighted_avg_cost(q_old: int, c_old: int, q_in: int, c_in: int) -> int:
    """AD-15: avg_moi = HALF_UP((Q*C + q*c) / (Q + q)); Q = 0 thi lay c."""
    if q_old == 0:
        return c_in
    return half_up_div(q_old * c_old + q_in * c_in, q_old + q_in)


def price_order(lines, order_discount, points_value):
    """lines: [(unit_price, qty, discount|None)], discount = (type, value).

    Tra ve dict: gross[], line_discount[], after_line[], subtotal, order_discount,
    points_discount, allocation[], net[], total.
    """
    gross = [p * q for p, q, _ in lines]
    ld = []
    for (p, q, d), g in zip(lines, gross):
        if d is None:
            ld.append(0)
        elif d[0] == "PERCENT":
            ld.append(percent_of(g, d[1]))
        else:
            ld.append(min(d[1], g))
    after = [g - x for g, x in zip(gross, ld)]
    subtotal = sum(after)
    od = 0
    if order_discount is not None:
        od = percent_of(subtotal, order_discount[1]) if order_discount[0] == "PERCENT" else order_discount[1]
    if od + points_value > subtotal:
        raise ValueError("giam gia vuot tong tien")
    alloc = allocate_pro_rata(od + points_value, after)
    net = [a - x for a, x in zip(after, alloc)]
    if any(n < 0 for n in net):
        raise ValueError("net_line_amount am")
    return {
        "gross": gross, "line_discount": ld, "after": after, "subtotal": subtotal,
        "order_discount": od, "points_discount": points_value, "alloc": alloc,
        "net": net, "total": subtotal - od - points_value,
    }


def line_refund(net: int, qty: int, returned: int, refunded: int, r: int) -> int:
    """AD-8: hoan theo ty le so luong tren net_line_amount (floor); lan tra cuoi cua dong nhan phan con lai."""
    if returned + r == qty:
        return net - refunded
    return net * r // qty


# --------------------------------------------------------------------------- helpers

def hhmm(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def day_of(d: date) -> int:
    return (d - CANONICAL_ANCHOR).days


def weekday(day: int) -> int:
    return (CANONICAL_ANCHOR + timedelta(days=day)).weekday()


def ean13(body12: str) -> str:
    assert len(body12) == 12 and body12.isdigit()
    s = sum(int(c) * (3 if i % 2 else 1) for i, c in enumerate(body12))
    return body12 + str((10 - s % 10) % 10)


def wpick(rng: random.Random, items, weights):
    total = sum(weights)
    r = rng.random() * total
    acc = 0.0
    for it, w in zip(items, weights):
        acc += w
        if r < acc:
            return it
    return items[-1]


def round_to(x: float, unit: int) -> int:
    return max(unit, int(math.floor(x / unit + 0.5)) * unit)


SHARED_SOCK_BARCODE = ean13("893500012345")

# --------------------------------------------------------------------------- catalog data
# Tap hoa: (category, name, unit, price, cost, pop, shelfDays|None, flags, description)
# flags: multi = mua nhieu (lon, goi); starve = nha cung cap ngung giao 14 ngay cuoi;
#        overstock = ton dau ky lon; deact1/deact2 = ngung kinh doanh; near:N = lo dau ky het han
#        anchor+N; clearance = gia ban < gia von; nearpromo = giam 20% vi can date; shared = ma vach
#        dung chung voi Cua hang B.
GROCERY_CATEGORIES = [
    ("DO-UONG", "Đồ uống", "NCC-A01"),
    ("SUA", "Sữa và chế phẩm từ sữa", "NCC-A02"),
    ("MI-AN-LIEN", "Mì gói và đồ ăn liền", "NCC-A03"),
    ("BANH-KEO", "Bánh kẹo, đồ ăn vặt", "NCC-A03"),
    ("GIA-VI", "Gia vị", "NCC-A04"),
    ("GAO-KHO", "Gạo và thực phẩm khô", "NCC-A04"),
    ("TUOI", "Trứng và thực phẩm tươi, đông lạnh", "NCC-A02"),
    ("HOA-MY-PHAM", "Hóa mỹ phẩm", "NCC-A05"),
    ("TAY-RUA", "Giặt giũ và tẩy rửa", "NCC-A05"),
    ("GIA-DUNG", "Đồ dùng gia đình", "NCC-A05"),
]

GROCERY = [
    # Do uong (20)
    ("DO-UONG", "Nước ngọt Coca-Cola lon 320ml", "lon", 10000, 7600, "H", None, "multi", "nước ngọt có ga vị cola, uống lạnh"),
    ("DO-UONG", "Nước ngọt Pepsi lon 320ml", "lon", 10000, 7500, "H", None, "multi", "nước ngọt có ga vị cola"),
    ("DO-UONG", "Nước ngọt 7Up lon 320ml", "lon", 10000, 7400, "M", None, "multi", "nước ngọt có ga vị chanh, giải khát"),
    ("DO-UONG", "Nước cam có ga Fanta lon 320ml", "lon", 10000, 7400, "M", None, "multi", "nước ngọt có ga hương cam"),
    ("DO-UONG", "Nước ngọt Mirinda soda kem lon 320ml", "lon", 10000, 7400, "L", None, "multi", "nước ngọt có ga vị soda kem"),
    ("DO-UONG", "Nước tinh khiết Aquafina 500ml", "chai", 6000, 3900, "H", None, "multi", "nước uống đóng chai"),
    ("DO-UONG", "Nước khoáng La Vie 500ml", "chai", 6000, 4000, "H", None, "multi", "nước khoáng thiên nhiên đóng chai"),
    ("DO-UONG", "Nước tinh khiết Aquafina 1,5 lít", "chai", 12000, 8200, "M", None, "multi", "nước uống chai lớn cho gia đình"),
    ("DO-UONG", "Trà xanh Không Độ 455ml", "chai", 11000, 7900, "H", None, "multi", "trà xanh đóng chai vị chanh, giải nhiệt"),
    ("DO-UONG", "Trà Ô Long Tea+ Plus 450ml", "chai", 11000, 8000, "M", None, "multi", "trà ô long đóng chai, ít béo"),
    ("DO-UONG", "Nước tăng lực Red Bull lon 250ml", "lon", 12000, 9200, "H", None, "multi", "nước tăng lực giúp tỉnh táo, chống buồn ngủ"),
    ("DO-UONG", "Nước tăng lực Sting 330ml", "chai", 10000, 7300, "H", None, "multi", "nước tăng lực bổ sung năng lượng"),
    ("DO-UONG", "Cà phê sữa Highlands lon 235ml", "lon", 15000, 11000, "M", None, "multi", "cà phê sữa pha sẵn đóng lon"),
    ("DO-UONG", "Bia Sài Gòn Lager lon 330ml", "lon", 15000, 12200, "H", None, "multi", "bia lon cho bữa nhậu, tiệc"),
    ("DO-UONG", "Bia Tiger lon 330ml", "lon", 18000, 14800, "M", None, "multi", "bia lon vị đậm"),
    ("DO-UONG", "Thùng bia Sài Gòn Lager 24 lon", "thùng", 345000, 290000, "L", None, "overstock", "thùng bia 24 lon cho đám tiệc, liên hoan"),
    ("DO-UONG", "Nước yến Sanest lon 190ml", "lon", 12000, 8800, "S", None, "", "nước yến bồi bổ sức khỏe"),
    ("DO-UONG", "Nước dừa đóng hộp Cocoxim 330ml", "hộp", 15000, 10500, "L", None, "", "nước dừa xiêm nguyên chất"),
    ("DO-UONG", "Nước ép cam Vfresh 1 lít", "hộp", 42000, 33000, "L", None, "", "nước ép cam giàu vitamin C"),
    ("DO-UONG", "Sữa trái cây TH true JUICE milk 180ml", "hộp", 9000, 6700, "M", None, "multi", "sữa trái cây cho trẻ em"),
    # Sua (13) - theo Lo hang
    ("SUA", "Sữa tươi tiệt trùng Vinamilk có đường 180ml", "hộp", 7500, 5800, "H", 180, "multi daily", "sữa tươi cho trẻ em uống hằng ngày"),
    ("SUA", "Sữa tươi Vinamilk không đường 1 lít", "hộp", 36000, 29500, "M", 180, "", "sữa tươi không đường cho người lớn, ăn kiêng"),
    ("SUA", "Lốc 4 hộp sữa TH true MILK ít đường 180ml", "lốc", 34000, 27800, "H", 180, "", "sữa tươi sạch ít đường"),
    ("SUA", "Sữa chua Vinamilk 100g", "hộp", 7000, 5200, "H", 45, "multi daily", "sữa chua ăn tốt cho tiêu hóa"),
    ("SUA", "Lốc 4 chai sữa chua uống Probi 65ml", "lốc", 25000, 19500, "M", 45, "", "sữa chua uống men sống tốt cho đường ruột"),
    ("SUA", "Sữa đặc Ông Thọ lon 380g", "lon", 28000, 22500, "M", 365, "", "sữa đặc có đường pha cà phê"),
    ("SUA", "Sữa đậu nành Fami 200ml", "hộp", 6000, 4300, "M", 180, "multi", "sữa đậu nành nguyên chất"),
    ("SUA", "Sữa bột Dielac Grow Plus 900g", "lon", 465000, 398000, "S", 540, "overstock", "sữa bột cho bé 2–10 tuổi tăng chiều cao, cân nặng"),
    ("SUA", "Phô mai Con Bò Cười 8 miếng", "hộp", 36000, 28500, "L", 120, "near:12", "phô mai ăn sáng cho bé"),
    ("SUA", "Lốc 5 chai sữa chua uống Yakult", "lốc", 27000, 21500, "M", 45, "", "sữa chua uống lợi khuẩn"),
    ("SUA", "Váng sữa Monte lốc 4 hộp 55g", "lốc", 42000, 33000, "L", 90, "", "váng sữa ăn dặm cho bé"),
    ("SUA", "Lốc 4 hộp sữa Milo 180ml", "lốc", 30000, 24000, "H", 180, "", "sữa lúa mạch cho trẻ năng động"),
    ("SUA", "Sữa đậu nành Vinasoy 200ml", "hộp", 5500, 4000, "M", 180, "multi", "sữa đậu nành ít đường"),
    # Mi, do an lien (15)
    ("MI-AN-LIEN", "Mì Hảo Hảo tôm chua cay 75g", "gói", 4500, 3450, "H", None, "multi starve", "mì gói ăn liền vị tôm chua cay"),
    ("MI-AN-LIEN", "Mì Omachi xốt bò hầm 80g", "gói", 8000, 6200, "M", None, "multi", "mì khoai tây không chiên"),
    ("MI-AN-LIEN", "Mì Kokomi đại 90g", "gói", 4000, 3000, "H", None, "multi", "mì gói giá rẻ, gói to"),
    ("MI-AN-LIEN", "Thùng mì Hảo Hảo 30 gói", "thùng", 125000, 103000, "M", None, "", "thùng mì tôm chua cay 30 gói"),
    ("MI-AN-LIEN", "Phở bò ăn liền Vifon 65g", "gói", 7000, 5300, "L", None, "multi", "phở gói ăn liền vị bò"),
    ("MI-AN-LIEN", "Cháo yến Gấu Đỏ 50g", "gói", 6000, 4500, "L", None, "multi", "cháo ăn liền cho người ốm, ăn sáng nhanh"),
    ("MI-AN-LIEN", "Miến Phú Hương sườn heo 55g", "gói", 9000, 6900, "L", None, "multi", "miến ăn liền vị sườn"),
    ("MI-AN-LIEN", "Mì ly Modern lẩu Thái 65g", "ly", 9000, 6800, "M", None, "multi", "mì ly ăn liền vị lẩu Thái chua cay"),
    ("MI-AN-LIEN", "Bún bò Huế ăn liền Cung Đình 78g", "gói", 9000, 6900, "L", None, "multi", "bún bò Huế ăn liền"),
    ("MI-AN-LIEN", "Mì trộn Indomie Mi Goreng 85g", "gói", 7000, 5300, "M", None, "multi", "mì xào khô kiểu Indonesia"),
    ("MI-AN-LIEN", "Hủ tiếu Nam Vang Nhịp Sống 70g", "gói", 8000, 6100, "L", None, "multi deact2", "hủ tiếu ăn liền vị Nam Vang"),
    ("MI-AN-LIEN", "Cá hộp sốt cà 3 Cô Gái 155g", "hộp", 22000, 17000, "L", None, "", "cá mòi sốt cà chua đóng hộp"),
    ("MI-AN-LIEN", "Pate gan heo Hạ Long 150g", "hộp", 25000, 19000, "S", None, "", "pate ăn kèm bánh mì"),
    ("MI-AN-LIEN", "Xúc xích heo Vissan 175g", "gói", 30000, 23500, "M", 90, "", "xúc xích heo ăn liền, chiên"),
    ("MI-AN-LIEN", "Cơm cháy chà bông 200g", "gói", 35000, 26000, "S", 150, "", "cơm cháy giòn ăn vặt"),
    # Banh keo (18)
    ("BANH-KEO", "Bánh Chocopie Orion hộp 12 cái", "hộp", 55000, 43000, "M", 270, "", "bánh phủ sô cô la nhân kem dẻo"),
    ("BANH-KEO", "Bánh quy Cosy Marie 144g", "gói", 18000, 13500, "L", None, "", "bánh quy giòn ăn sáng"),
    ("BANH-KEO", "Snack khoai tây Lay's 52g", "gói", 12000, 8900, "H", 180, "multi", "snack khoai tây chiên giòn"),
    ("BANH-KEO", "Snack Oishi 40g", "gói", 6000, 4300, "H", None, "multi", "snack giòn cho trẻ em"),
    ("BANH-KEO", "Kẹo Alpenliebe gói 120g", "gói", 18000, 13800, "L", None, "", "kẹo sữa caramen"),
    ("BANH-KEO", "Kẹo cao su Doublemint vỉ", "vỉ", 6000, 4500, "M", None, "multi", "kẹo cao su bạc hà thơm miệng"),
    ("BANH-KEO", "Bánh gạo One One 150g", "gói", 22000, 16800, "M", None, "", "bánh gạo giòn vị ngọt dịu"),
    ("BANH-KEO", "Bánh mì sandwich Kinh Đô 280g", "gói", 22000, 16500, "H", 21, "daily", "bánh mì gối ăn sáng, làm sandwich"),
    ("BANH-KEO", "Bánh bông lan Solite 360g", "hộp", 48000, 37000, "L", 120, "near:9", "bánh bông lan kem bơ"),
    ("BANH-KEO", "Hạt hướng dương Tân Tân 100g", "gói", 15000, 11000, "M", None, "", "hạt hướng dương rang ăn vặt ngày Tết"),
    ("BANH-KEO", "Đậu phộng da cá Tân Tân 275g", "hũ", 38000, 29000, "S", None, "", "đậu phộng da cá giòn, nhắm bia"),
    ("BANH-KEO", "Bánh trung thu thập cẩm 150g", "cái", 45000, 52000, "S", 90, "clearance near:6 nearpromo", "bánh trung thu nhân thập cẩm, hàng tồn sau Trung thu"),
    ("BANH-KEO", "Kẹo dẻo Chupa Chups 90g", "gói", 20000, 15000, "L", None, "", "kẹo dẻo trái cây cho bé"),
    ("BANH-KEO", "Bánh quế Cosy 132g", "gói", 20000, 15200, "S", None, "", "bánh quế kem sữa"),
    ("BANH-KEO", "Sô cô la KitKat 4 thanh 35g", "gói", 18000, 13800, "M", None, "", "bánh xốp phủ sô cô la"),
    ("BANH-KEO", "Bánh tráng trộn Tây Ninh", "gói", 15000, 9500, "M", None, "", "bánh tráng trộn sẵn ăn vặt"),
    ("BANH-KEO", "Bánh quy bơ Danisa hộp 454g", "hộp", 155000, 124000, "S", None, "overstock", "bánh quy bơ hộp thiếc làm quà biếu"),
    ("BANH-KEO", "Kẹo sữa Milkita 30 viên", "gói", 25000, 18500, "L", None, "", "kẹo sữa viên cho bé"),
    # Gia vi (16)
    ("GIA-VI", "Nước mắm Nam Ngư 500ml", "chai", 38000, 30500, "H", None, "starve", "nước mắm chấm, nêm nếm món ăn"),
    ("GIA-VI", "Nước mắm Phú Quốc 40 độ đạm 500ml", "chai", 85000, 66000, "L", None, "overstock", "nước mắm truyền thống Phú Quốc đậm đà"),
    ("GIA-VI", "Nước tương Maggi 300ml", "chai", 22000, 17200, "M", None, "", "nước tương đậu nành chấm, ướp"),
    ("GIA-VI", "Dầu ăn Neptune 1 lít", "chai", 58000, 48500, "H", None, "starve", "dầu ăn chiên xào"),
    ("GIA-VI", "Dầu ăn Tường An 1 lít", "chai", 52000, 43000, "M", None, "", "dầu thực vật nấu ăn"),
    ("GIA-VI", "Bột ngọt Ajinomoto 400g", "gói", 35000, 28500, "M", None, "", "bột ngọt nêm món ăn"),
    ("GIA-VI", "Hạt nêm Knorr thịt thăn 400g", "gói", 42000, 34000, "H", None, "", "hạt nêm nấu canh"),
    ("GIA-VI", "Đường trắng Biên Hòa 1kg", "gói", 28000, 23000, "H", None, "", "đường cát trắng"),
    ("GIA-VI", "Muối i-ốt 500g", "gói", 6000, 4200, "M", None, "multi", "muối ăn có i-ốt"),
    ("GIA-VI", "Tương ớt Chinsu 250g", "chai", 15000, 11200, "H", None, "", "tương ớt cay chấm đồ chiên"),
    ("GIA-VI", "Tiêu đen xay 50g", "hũ", 25000, 18500, "L", None, "", "tiêu đen xay nhuyễn"),
    ("GIA-VI", "Giấm gạo 400ml", "chai", 14000, 10500, "S", None, "", "giấm gạo lên men pha nước chấm"),
    ("GIA-VI", "Dầu hào Maggi 350g", "chai", 32000, 25000, "M", None, "", "dầu hào xào rau, ướp thịt"),
    ("GIA-VI", "Sa tế tôm 90g", "hũ", 18000, 13200, "S", None, "", "sa tế cay ăn lẩu, bún"),
    ("GIA-VI", "Bột chiên giòn Tài Ký 150g", "gói", 12000, 8800, "L", None, "", "bột chiên giòn tẩm bột chiên"),
    ("GIA-VI", "Nước cốt dừa Vietcoco 400ml", "hộp", 28000, 21000, "S", None, "", "nước cốt dừa nấu chè, cà ri"),
    # Gao, thuc pham kho (12)
    ("GAO-KHO", "Gạo ST25 túi 5kg", "túi", 185000, 155000, "M", None, "", "gạo thơm dẻo ST25"),
    ("GAO-KHO", "Gạo thơm Jasmine túi 5kg", "túi", 125000, 104000, "H", None, "", "gạo thơm nấu cơm hằng ngày"),
    ("GAO-KHO", "Gạo nếp cái hoa vàng 1kg", "túi", 38000, 30000, "L", None, "", "nếp nấu xôi, làm bánh"),
    ("GAO-KHO", "Bún khô Safoco 500g", "gói", 22000, 16800, "L", None, "", "bún khô nấu nhanh"),
    ("GAO-KHO", "Đậu xanh cà vỏ 500g", "gói", 28000, 21500, "S", None, "", "đậu xanh nấu chè, đồ xôi"),
    ("GAO-KHO", "Nấm hương khô 100g", "gói", 55000, 42000, "S", None, "", "nấm đông cô khô nấu canh, hầm"),
    ("GAO-KHO", "Miến dong 200g", "gói", 25000, 18500, "L", None, "", "miến dong nấu canh, xào"),
    ("GAO-KHO", "Bột mì đa dụng Meizan 1kg", "gói", 25000, 19500, "L", None, "", "bột mì làm bánh"),
    ("GAO-KHO", "Yến mạch Quaker 400g", "hộp", 65000, 51000, "S", None, "", "yến mạch ăn sáng, ăn kiêng giảm cân"),
    ("GAO-KHO", "Ngũ cốc Calbee 600g", "gói", 165000, 132000, "S", None, "overstock", "ngũ cốc trái cây sấy ăn sáng"),
    ("GAO-KHO", "Đậu phộng sống 500g", "gói", 32000, 24000, "L", None, "", "đậu phộng nấu xôi, rang"),
    ("GAO-KHO", "Bánh phở khô 500g", "gói", 24000, 18000, "L", None, "", "bánh phở khô nấu phở tại nhà"),
    # Tuoi, dong lanh (9) - theo Lo hang
    ("TUOI", "Trứng gà hộp 10 quả", "hộp", 32000, 26000, "H", 30, "daily starve", "trứng gà tươi"),
    ("TUOI", "Trứng vịt hộp 10 quả", "hộp", 38000, 30500, "M", 30, "", "trứng vịt tươi"),
    ("TUOI", "Đậu hũ non hộp 300g", "hộp", 9000, 6500, "M", 21, "daily", "đậu hũ non nấu canh, chiên"),
    ("TUOI", "Chả lụa Vissan 250g", "cây", 55000, 44000, "L", 45, "", "chả lụa giò lụa ăn kèm bánh mì, xôi"),
    ("TUOI", "Lạp xưởng Mai Quế Lộ 500g", "gói", 110000, 88000, "L", 120, "", "lạp xưởng chiên cơm"),
    ("TUOI", "Há cảo tôm thịt Cầu Tre 500g", "gói", 65000, 51000, "S", 180, "", "há cảo đông lạnh hấp, chiên"),
    ("TUOI", "Chả giò rế Cầu Tre 500g", "gói", 70000, 55000, "L", 180, "", "chả giò đông lạnh chiên giòn"),
    ("TUOI", "Xúc xích tiệt trùng Đức Việt 5 cây", "gói", 25000, 19000, "M", 90, "", "xúc xích ăn liền cho bé"),
    ("TUOI", "Bánh bao nhân thịt trứng cút 2 cái", "gói", 30000, 22000, "L", 90, "", "bánh bao đông lạnh hấp ăn sáng"),
    # Hoa my pham (21)
    ("HOA-MY-PHAM", "Dầu gội Sunsilk mềm mượt diệu kỳ 320g", "chai", 78000, 61000, "M", None, "", "dầu gội cho tóc khô xơ, giúp tóc mềm mượt"),
    ("HOA-MY-PHAM", "Dầu gội Clear mát lạnh bạc hà 370g", "chai", 95000, 75000, "M", None, "", "dầu gội sạch gàu, mát lạnh da đầu"),
    ("HOA-MY-PHAM", "Dầu gội Head & Shoulders 330ml", "chai", 115000, 91000, "L", None, "", "dầu gội trị gàu, giảm ngứa da đầu"),
    ("HOA-MY-PHAM", "Dầu gội dược liệu Thái Dương 3 200ml", "chai", 72000, 55000, "L", None, "", "dầu gội thảo dược giảm rụng tóc"),
    ("HOA-MY-PHAM", "Dầu xả TRESemmé phục hồi hư tổn 340g", "chai", 89000, 70000, "S", None, "", "dầu xả cho tóc khô, hư tổn do uốn nhuộm"),
    ("HOA-MY-PHAM", "Dầu gội Dove phục hồi hư tổn 640g", "chai", 159000, 127000, "S", None, "", "dầu gội phục hồi tóc khô hư tổn"),
    ("HOA-MY-PHAM", "Sữa tắm Lifebuoy bảo vệ vượt trội 850g", "chai", 135000, 108000, "M", None, "", "sữa tắm kháng khuẩn cho cả nhà"),
    ("HOA-MY-PHAM", "Sữa tắm Dove dưỡng ẩm sâu 500g", "chai", 145000, 116000, "L", None, "", "sữa tắm dưỡng ẩm cho da khô"),
    ("HOA-MY-PHAM", "Kem đánh răng P/S bảo vệ 123 230g", "tuýp", 32000, 24500, "H", None, "starve", "kem đánh răng ngừa sâu răng"),
    ("HOA-MY-PHAM", "Kem đánh răng Colgate MaxFresh 200g", "tuýp", 38000, 29500, "M", None, "", "kem đánh răng thơm mát hơi thở"),
    ("HOA-MY-PHAM", "Bàn chải đánh răng Colgate lông mềm", "cái", 18000, 12800, "M", None, "", "bàn chải lông mềm cho răng nhạy cảm"),
    ("HOA-MY-PHAM", "Xà bông cục Lifebuoy 90g", "cục", 16000, 12000, "M", None, "multi", "xà phòng cục kháng khuẩn"),
    ("HOA-MY-PHAM", "Sữa rửa mặt Senka Perfect Whip 120g", "tuýp", 95000, 74000, "L", None, "", "sữa rửa mặt tạo bọt cho da dầu, mụn"),
    ("HOA-MY-PHAM", "Dầu gội Rejoice 3 trong 1 900g", "chai", 155000, 124000, "S", None, "overstock", "dầu gội xả 3 trong 1 chai lớn"),
    ("HOA-MY-PHAM", "Băng vệ sinh Diana Sensi siêu mỏng 8 miếng", "gói", 22000, 16800, "M", None, "", "băng vệ sinh siêu mỏng cánh"),
    ("HOA-MY-PHAM", "Khăn giấy rút Tempo 3 lớp", "gói", 32000, 24800, "M", None, "", "khăn giấy rút mềm dai"),
    ("HOA-MY-PHAM", "Lăn khử mùi Nivea Men 50ml", "chai", 75000, 58000, "S", None, "", "lăn khử mùi cho nam, ngăn mồ hôi"),
    ("HOA-MY-PHAM", "Kem chống nắng Sunplay SPF50 30g", "tuýp", 85000, 66000, "S", None, "", "kem chống nắng đi biển, chống tia UV"),
    ("HOA-MY-PHAM", "Dao cạo râu Gillette vỉ 2 cây", "vỉ", 22000, 16000, "L", None, "", "dao cạo râu dùng một lần"),
    ("HOA-MY-PHAM", "Nước súc miệng Listerine 250ml", "chai", 69000, 53000, "L", None, "", "nước súc miệng diệt khuẩn, hơi thở thơm"),
    ("HOA-MY-PHAM", "Dầu gió xanh Con Ó 24ml", "chai", 55000, 41000, "L", None, "", "dầu gió trị cảm, nhức đầu, chóng mặt"),
    # Giat giu, tay rua (12)
    ("TAY-RUA", "Bột giặt OMO Comfort 2,7kg", "túi", 175000, 142000, "M", None, "", "bột giặt hương Comfort giặt tay, máy"),
    ("TAY-RUA", "Nước giặt OMO Matic cửa trên 2,9kg", "túi", 189000, 154000, "M", None, "", "nước giặt cho máy giặt cửa trên"),
    ("TAY-RUA", "Nước giặt Ariel giữ màu 2,1kg", "túi", 182000, 148000, "L", None, "", "nước giặt giữ màu quần áo"),
    ("TAY-RUA", "Nước xả vải Comfort hương ban mai 1,6 lít", "túi", 98000, 79000, "M", None, "", "nước xả làm mềm vải, thơm lâu"),
    ("TAY-RUA", "Nước rửa chén Sunlight chanh 750g", "chai", 32000, 25000, "H", None, "", "nước rửa chén bát sạch dầu mỡ"),
    ("TAY-RUA", "Nước lau sàn Sunlight hương lily 1kg", "chai", 42000, 33000, "M", None, "", "nước lau nhà thơm, diệt khuẩn"),
    ("TAY-RUA", "Nước tẩy bồn cầu Vim 880ml", "chai", 38000, 29500, "L", None, "", "tẩy rửa bồn cầu, nhà vệ sinh"),
    ("TAY-RUA", "Nước rửa tay Lifebuoy 180ml", "chai", 45000, 34500, "L", None, "", "nước rửa tay diệt khuẩn"),
    ("TAY-RUA", "Nước giặt Lix đậm đặc 3,6kg", "can", 135000, 108000, "L", None, "", "nước giặt can lớn tiết kiệm"),
    ("TAY-RUA", "Viên tẩy lồng máy giặt hộp 12 viên", "hộp", 55000, 40000, "S", None, "", "vệ sinh lồng máy giặt, khử mùi hôi"),
    ("TAY-RUA", "Nước lau kính Gift 580ml", "chai", 28000, 21000, "S", None, "", "nước lau kính, gương sáng bóng"),
    ("TAY-RUA", "Bột giặt Viso 3kg", "túi", 125000, 100000, "L", None, "deact1", "bột giặt túi lớn"),
    # Do dung gia dinh (14)
    ("GIA-DUNG", "Giấy vệ sinh Pulppy 10 cuộn", "lốc", 85000, 68000, "H", None, "", "giấy vệ sinh cuộn mềm"),
    ("GIA-DUNG", "Túi đựng rác tự hủy 3 cuộn", "lốc", 30000, 21000, "M", None, "", "túi rác sinh học tự phân hủy"),
    ("GIA-DUNG", "Pin Panasonic AA vỉ 4 viên", "vỉ", 28000, 20500, "L", None, "", "pin tiểu cho remote, đồng hồ"),
    ("GIA-DUNG", "Bật lửa gas", "cái", 5000, 3000, "M", None, "multi", "hộp quẹt gas"),
    ("GIA-DUNG", "Hộp đựng thực phẩm Lock&Lock 1 lít", "hộp", 89000, 65000, "S", None, "overstock", "hộp nhựa đựng thức ăn bảo quản tủ lạnh"),
    ("GIA-DUNG", "Màng bọc thực phẩm Ringo 30cm", "cuộn", 45000, 33000, "L", None, "", "màng bọc thức ăn"),
    ("GIA-DUNG", "Bóng đèn LED Rạng Đông 9W", "cái", 39000, 28500, "S", None, "", "bóng đèn LED tiết kiệm điện"),
    ("GIA-DUNG", "Đũa tre 10 đôi", "bó", 15000, 9500, "L", None, "", "đũa tre ăn cơm"),
    ("GIA-DUNG", "Khẩu trang y tế 4 lớp hộp 50 cái", "hộp", 35000, 24000, "L", None, "", "khẩu trang y tế chống bụi"),
    ("GIA-DUNG", "Tất cổ ngắn cotton", "đôi", 15000, 9000, "L", None, "shared", "tất vớ cotton cổ ngắn"),
    ("GIA-DUNG", "Ly nhựa dùng một lần 50 cái", "lốc", 25000, 17000, "S", None, "", "ly nhựa cho tiệc, đám"),
    ("GIA-DUNG", "Nhang muỗi hộp 10 khoanh", "hộp", 25000, 18000, "M", None, "", "hương đuổi muỗi"),
    ("GIA-DUNG", "Khăn lau đa năng 3 cái", "gói", 30000, 20000, "S", None, "", "khăn lau bếp, lau bàn"),
    ("GIA-DUNG", "Lon gas mini 250g", "lon", 35000, 26000, "L", None, "", "lon gas cho bếp mini nấu lẩu"),
]

GROCERY_VARIANTS = {
    "Nước tăng lực Sting 330ml": [("Hương vị", "Dâu đỏ", "DAU", 1.2), ("Hương vị", "Vàng nhân sâm", "VANG", 0.8)],
    "Sữa chua Vinamilk 100g": [("Loại", "Có đường", "CD", 1.3), ("Loại", "Ít đường", "ID", 0.8), ("Loại", "Nha đam", "ND", 0.9)],
    "Snack Oishi 40g": [("Vị", "Tôm cay", "TOM", 1.2), ("Vị", "Bí đỏ", "BIDO", 0.8)],
}

# Thoi trang: (category, name, unit, price, cost, pop, colors, sizes, season, flags, description)
# sizes: SXL = S..XL, SL = S..L, MXL = M..XL, NAM = 29..32, NU = 26..29, KID = 4T/6T/8T, "" = khong co size.
# flags: starve, overstock, deact (ngung ca San pham), deactvar (ngung 1 Bien the), clearance, sale30, shared.
FASHION_CATEGORIES = [
    ("AO-THUN", "Áo thun, áo polo", "NCC-B01"),
    ("AO-SO-MI", "Áo sơ mi, áo kiểu", "NCC-B02"),
    ("AO-KHOAC", "Áo khoác", "NCC-B04"),
    ("AO-LEN", "Áo len, áo nỉ", "NCC-B04"),
    ("QUAN-JEAN", "Quần jean", "NCC-B03"),
    ("QUAN-TAY", "Quần tây, quần kaki", "NCC-B02"),
    ("QUAN-SHORT", "Quần short", "NCC-B03"),
    ("VAY-DAM", "Váy, đầm", "NCC-B02"),
    ("DO-BO", "Đồ bộ, đồ ngủ, đồ bơi", "NCC-B01"),
    ("PHU-KIEN", "Phụ kiện", "NCC-B05"),
]

FASHION = [
    # Ao thun (22)
    ("AO-THUN", "Áo thun nam cổ tròn basic", "cái", 149000, 68000, "H", "Đen,Trắng", "SXL", None, "", "áo thun cotton trơn mặc hằng ngày, thấm hút mồ hôi"),
    ("AO-THUN", "Áo thun nữ cổ tròn basic", "cái", 139000, 62000, "H", "Trắng,Hồng", "SL", None, "", "áo thun nữ cotton co giãn, dễ phối đồ"),
    ("AO-THUN", "Áo thun oversize in chữ", "cái", 189000, 85000, "M", "Đen,Be", "MXL", None, "", "áo thun form rộng phong cách đường phố"),
    ("AO-THUN", "Áo polo nam cá sấu", "cái", 249000, 115000, "M", "Xanh navy,Trắng", "MXL", None, "", "áo polo có cổ lịch sự đi làm, đi chơi"),
    ("AO-THUN", "Áo polo nữ phối viền", "cái", 229000, 105000, "L", "Trắng", "SL", None, "", "áo polo nữ trẻ trung"),
    ("AO-THUN", "Áo thun tay dài nam", "cái", 179000, 82000, "M", "Đen,Xám", "MXL", "winter", "", "áo thun dài tay giữ ấm nhẹ khi trời se lạnh"),
    ("AO-THUN", "Áo thun ba lỗ nam", "cái", 99000, 45000, "L", "Đen,Trắng", "MXL", "summer", "deact", "áo ba lỗ thoáng mát mùa hè, tập gym"),
    ("AO-THUN", "Áo thun croptop nữ", "cái", 129000, 58000, "M", "Đen,Trắng", "SL", None, "", "áo croptop ngắn trẻ trung"),
    ("AO-THUN", "Áo thun raglan unisex", "cái", 169000, 76000, "L", "Xám", "SXL", None, "", "áo thun tay raglan phối màu"),
    ("AO-THUN", "Áo thun in hình mèo", "cái", 159000, 70000, "M", "Trắng", "SL", None, "", "áo thun in hình dễ thương"),
    ("AO-THUN", "Áo thun thể thao dry-fit nam", "cái", 199000, 90000, "M", "Đen,Xanh dương", "MXL", None, "", "áo thể thao thoáng khí, nhanh khô khi chạy bộ, đá bóng"),
    ("AO-THUN", "Áo thun cổ tim nữ", "cái", 139000, 62000, "L", "Đen", "SL", None, "", "áo thun cổ chữ V"),
    ("AO-THUN", "Áo thun gân tăm nữ", "cái", 149000, 66000, "M", "Kem,Đen", "SL", None, "", "áo thun gân ôm body"),
    ("AO-THUN", "Áo thun henley nam", "cái", 199000, 92000, "L", "Xanh rêu", "MXL", None, "", "áo thun cổ trụ có nút"),
    ("AO-THUN", "Áo thun sọc ngang", "cái", 169000, 75000, "L", "Sọc xanh", "SL", None, "", "áo thun kẻ sọc phong cách thủy thủ"),
    ("AO-THUN", "Áo thun cotton compact cao cấp", "cái", 259000, 120000, "M", "Đen,Trắng", "MXL", None, "", "áo thun vải cotton dày dặn, ít nhăn"),
    ("AO-THUN", "Áo thun form rộng wash", "cái", 219000, 98000, "L", "Xám", "MXL", None, "", "áo thun wash màu bụi bặm"),
    ("AO-THUN", "Áo thun tay lỡ unisex", "cái", 179000, 80000, "M", "Đen,Be", "MXL", None, "", "áo thun tay lỡ form rộng"),
    ("AO-THUN", "Áo thun đôi in chữ", "cái", 159000, 72000, "S", "Trắng", "SXL", None, "", "áo đôi cho các cặp đôi"),
    ("AO-THUN", "Áo thun bé trai in khủng long", "cái", 119000, 52000, "L", "Xanh dương", "KID", None, "", "áo thun trẻ em in hình khủng long"),
    ("AO-THUN", "Áo thun nữ tay phồng", "cái", 169000, 76000, "L", "Trắng", "SL", None, "", "áo thun tay bồng điệu đà"),
    ("AO-THUN", "Áo thun nam cổ bẻ trơn", "cái", 189000, 86000, "L", "Xám,Xanh navy", "MXL", None, "", "áo thun cổ bẻ trơn đơn giản"),
    # Ao so mi (16)
    ("AO-SO-MI", "Áo sơ mi nam trắng công sở", "cái", 289000, 135000, "H", "Trắng", "MXL", None, "", "áo sơ mi trắng đi làm, đi phỏng vấn"),
    ("AO-SO-MI", "Áo sơ mi nam Oxford xanh nhạt", "cái", 299000, 140000, "M", "Xanh nhạt", "MXL", None, "", "áo sơ mi vải Oxford công sở"),
    ("AO-SO-MI", "Áo sơ mi nữ lụa tay dài", "cái", 319000, 148000, "M", "Trắng,Hồng", "SL", None, "", "áo sơ mi lụa mềm mát mặc đi làm"),
    ("AO-SO-MI", "Áo sơ mi caro flannel", "cái", 279000, 128000, "M", "Caro đỏ,Caro xanh", "MXL", "winter", "", "áo sơ mi caro vải dạ mỏng mùa lạnh"),
    ("AO-SO-MI", "Áo sơ mi nam tay ngắn họa tiết", "cái", 259000, 118000, "L", "Họa tiết", "MXL", "summer", "", "áo sơ mi đi biển, du lịch"),
    ("AO-SO-MI", "Áo sơ mi nữ cổ nơ", "cái", 299000, 138000, "L", "Trắng", "SL", None, "", "áo sơ mi nữ cổ thắt nơ thanh lịch"),
    ("AO-SO-MI", "Áo sơ mi denim unisex", "cái", 329000, 152000, "L", "Xanh đậm", "MXL", None, "", "áo sơ mi vải jean bụi"),
    ("AO-SO-MI", "Áo sơ mi linen nam", "cái", 349000, 165000, "S", "Be", "MXL", "summer", "", "áo sơ mi vải lanh thoáng mát"),
    ("AO-SO-MI", "Áo sơ mi nữ kẻ sọc", "cái", 279000, 128000, "L", "Sọc xanh", "SL", None, "", "áo sơ mi kẻ sọc dọc"),
    ("AO-SO-MI", "Áo sơ mi nam slimfit đen", "cái", 309000, 142000, "M", "Đen", "MXL", None, "", "áo sơ mi ôm dáng màu đen"),
    ("AO-SO-MI", "Áo kiểu nữ voan hoa", "cái", 259000, 118000, "L", "Họa tiết", "SL", "summer", "", "áo voan in hoa nhẹ nhàng"),
    ("AO-SO-MI", "Áo sơ mi nữ croptop", "cái", 239000, 108000, "L", "Trắng", "SL", None, "", "áo sơ mi dáng ngắn"),
    ("AO-SO-MI", "Áo sơ mi nam cổ trụ", "cái", 289000, 132000, "L", "Trắng", "MXL", None, "", "áo sơ mi cổ tàu"),
    ("AO-SO-MI", "Áo blouse nữ công sở", "cái", 299000, 136000, "M", "Kem,Đen", "SL", None, "", "áo kiểu nữ đi làm"),
    ("AO-SO-MI", "Áo sơ mi form rộng nữ", "cái", 269000, 122000, "M", "Trắng,Xanh nhạt", "SL", None, "", "áo sơ mi oversize nữ"),
    ("AO-SO-MI", "Áo sơ mi kaki nam túi hộp", "cái", 339000, 158000, "S", "Be", "MXL", None, "", "áo sơ mi kaki phong cách quân đội"),
    # Ao khoac (14)
    ("AO-KHOAC", "Áo khoác gió nam chống nước", "cái", 399000, 185000, "H", "Đen,Xanh navy", "MXL", "winter", "", "áo khoác gió chống nước, cản gió khi đi xe máy"),
    ("AO-KHOAC", "Áo khoác gió nữ siêu nhẹ", "cái", 379000, 175000, "M", "Hồng,Đen", "SL", "winter", "", "áo khoác gió nữ gấp gọn"),
    ("AO-KHOAC", "Áo khoác jean nữ", "cái", 429000, 198000, "M", "Xanh nhạt", "SL", None, "", "áo khoác bò nữ cá tính"),
    ("AO-KHOAC", "Áo khoác bomber nam", "cái", 459000, 212000, "L", "Đen,Xanh rêu", "MXL", "winter", "", "áo bomber nam bo chun"),
    ("AO-KHOAC", "Áo khoác phao nhẹ nữ", "cái", 599000, 280000, "H", "Đen,Kem", "SL", "winter", "starve", "áo phao siêu nhẹ giữ ấm mùa đông"),
    ("AO-KHOAC", "Áo khoác phao nam có mũ", "cái", 699000, 325000, "M", "Đen,Xanh navy", "MXL", "winter", "", "áo phao dày có mũ chống rét"),
    ("AO-KHOAC", "Áo khoác dạ nữ dáng dài", "cái", 990000, 470000, "L", "Be,Đen", "SL", "winter", "overstock", "áo măng tô dạ dáng dài sang trọng"),
    ("AO-KHOAC", "Áo blazer nữ công sở", "cái", 549000, 255000, "L", "Đen,Be", "SL", None, "", "áo vest nữ đi làm"),
    ("AO-KHOAC", "Áo vest nam lịch lãm", "cái", 1290000, 610000, "S", "Đen,Xanh navy", "MXL", None, "overstock", "áo vest nam đi tiệc cưới, sự kiện"),
    ("AO-KHOAC", "Áo khoác kaki nam", "cái", 459000, 212000, "L", "Be", "MXL", None, "", "áo khoác kaki dáng ngắn"),
    ("AO-KHOAC", "Áo cardigan len mỏng nữ", "cái", 329000, 150000, "M", "Kem,Xám", "SL", "winter", "", "áo khoác len mỏng mặc ngoài"),
    ("AO-KHOAC", "Áo khoác chống nắng nữ", "cái", 199000, 90000, "L", "Hồng,Trắng", "", "summer", "sale30", "áo chống nắng tia UV khi đi xe máy ban ngày"),
    ("AO-KHOAC", "Áo khoác da nam", "cái", 890000, 420000, "S", "Đen,Nâu", "MXL", "winter", "overstock", "áo khoác da cá tính"),
    ("AO-KHOAC", "Áo hoodie zip unisex", "cái", 359000, 165000, "M", "Đen,Xám", "MXL", "winter", "", "áo hoodie khóa kéo có mũ"),
    # Ao len, ao ni (14)
    ("AO-LEN", "Áo len cổ lọ nữ", "cái", 329000, 152000, "H", "Đen,Kem", "SL", "winter", "starve", "áo len cổ cao giữ ấm cổ mùa lạnh"),
    ("AO-LEN", "Áo len cổ tròn nam", "cái", 349000, 160000, "M", "Xám,Xanh navy", "MXL", "winter", "", "áo len nam cổ tròn ấm áp"),
    ("AO-LEN", "Áo len vặn thừng nữ", "cái", 369000, 170000, "M", "Kem,Nâu", "SL", "winter", "", "áo len đan vặn thừng dày"),
    ("AO-LEN", "Áo nỉ hoodie unisex", "cái", 329000, 150000, "H", "Đen,Xám", "MXL", "winter", "", "áo hoodie nỉ bông có mũ ấm áp"),
    ("AO-LEN", "Áo nỉ sweater in chữ", "cái", 289000, 132000, "M", "Be,Đen", "MXL", "winter", "", "áo sweater nỉ in chữ"),
    ("AO-LEN", "Áo gile len nam", "cái", 279000, 128000, "L", "Xám", "MXL", "winter", "", "áo len không tay mặc ngoài sơ mi"),
    ("AO-LEN", "Áo cardigan nữ dáng ngắn", "cái", 299000, 138000, "L", "Hồng", "SL", "winter", "", "áo cardigan len dáng ngắn"),
    ("AO-LEN", "Áo len mỏng cổ tim nam", "cái", 319000, 146000, "L", "Xanh navy", "MXL", "winter", "", "áo len mỏng cổ V"),
    ("AO-LEN", "Áo nỉ bông trơn", "cái", 249000, 112000, "M", "Xám,Đen", "MXL", "winter", "", "áo nỉ bông trơn giữ ấm"),
    ("AO-LEN", "Áo len sọc ngang nữ", "cái", 309000, 142000, "L", "Kem", "SL", "winter", "", "áo len kẻ sọc ngang"),
    ("AO-LEN", "Áo len trễ vai nữ", "cái", 289000, 132000, "S", "Đỏ đô", "SL", "winter", "", "áo len trễ vai gợi cảm"),
    ("AO-LEN", "Áo polo nỉ nam tay dài", "cái", 299000, 138000, "L", "Xanh rêu", "MXL", "winter", "", "áo polo nỉ dài tay"),
    ("AO-LEN", "Áo len oversize unisex", "cái", 359000, 165000, "M", "Be,Xám", "MXL", "winter", "", "áo len form rộng"),
    ("AO-LEN", "Áo giữ nhiệt nữ", "cái", 189000, 85000, "H", "Đen,Be", "SL", "winter", "", "áo giữ nhiệt mặc lót bên trong mùa đông"),
    # Quan jean (16)
    ("QUAN-JEAN", "Quần jean nữ ống rộng", "cái", 399000, 182000, "H", "Xanh nhạt,Đen", "NU", None, "", "quần jean ống rộng cạp cao tôn dáng"),
    ("QUAN-JEAN", "Quần jean nam slimfit", "cái", 449000, 205000, "H", "Xanh đậm,Đen", "NAM", None, "", "quần jean nam ôm vừa co giãn"),
    ("QUAN-JEAN", "Quần jean nữ skinny", "cái", 379000, 172000, "M", "Xanh đậm", "NU", None, "", "quần jean bó sát"),
    ("QUAN-JEAN", "Quần jean nam ống đứng", "cái", 429000, 196000, "M", "Xanh đậm", "NAM", None, "", "quần jean nam ống suông cổ điển"),
    ("QUAN-JEAN", "Quần jean nữ ống loe", "cái", 419000, 190000, "L", "Xanh nhạt", "NU", None, "", "quần jean ống loe vintage"),
    ("QUAN-JEAN", "Quần jean nam rách gối", "cái", 439000, 200000, "M", "Xanh nhạt,Đen", "NAM", None, "deactvar", "quần jean rách gối phong cách bụi"),
    ("QUAN-JEAN", "Quần jean baggy nữ", "cái", 389000, 176000, "M", "Xanh nhạt", "NU", None, "", "quần baggy jean thoải mái"),
    ("QUAN-JEAN", "Quần jean nam co giãn", "cái", 459000, 210000, "L", "Đen", "NAM", None, "", "quần jean co giãn bốn chiều"),
    ("QUAN-JEAN", "Quần jean nữ cạp cao", "cái", 399000, 182000, "M", "Đen", "NU", None, "", "quần jean lưng cao hack dáng"),
    ("QUAN-JEAN", "Quần jean boyfriend nữ", "cái", 389000, 176000, "L", "Xanh nhạt", "NU", None, "", "quần jean dáng boyfriend"),
    ("QUAN-JEAN", "Quần jean jogger nam", "cái", 419000, 190000, "L", "Xanh đậm", "NAM", None, "", "quần jean bo gấu kiểu jogger"),
    ("QUAN-JEAN", "Quần jean nữ ống đứng", "cái", 389000, 176000, "L", "Xanh đậm", "NU", None, "", "quần jean nữ ống đứng"),
    ("QUAN-JEAN", "Quần jean nam màu đen trơn", "cái", 449000, 205000, "M", "Đen", "NAM", None, "", "quần jean đen trơn dễ phối"),
    ("QUAN-JEAN", "Quần jean nữ ống suông", "cái", 399000, 182000, "L", "Xanh nhạt", "NU", None, "", "quần jean ống suông"),
    ("QUAN-JEAN", "Quần yếm jean nữ", "cái", 459000, 210000, "S", "Xanh nhạt", "SL", None, "", "quần yếm bò nữ"),
    ("QUAN-JEAN", "Quần jean nam ống côn", "cái", 429000, 196000, "L", "Xanh đậm", "NAM", None, "", "quần jean ống côn"),
    # Quan tay, kaki (12)
    ("QUAN-TAY", "Quần tây nam công sở", "cái", 359000, 165000, "M", "Đen,Xám", "NAM", None, "", "quần âu đi làm lịch sự"),
    ("QUAN-TAY", "Quần tây nữ ống suông", "cái", 329000, 150000, "M", "Đen,Be", "SL", None, "", "quần âu nữ ống suông"),
    ("QUAN-TAY", "Quần kaki nam", "cái", 339000, 155000, "M", "Be,Xanh rêu", "NAM", None, "", "quần kaki nam đi làm, đi chơi"),
    ("QUAN-TAY", "Quần kaki nữ ống đứng", "cái", 309000, 142000, "L", "Be", "SL", None, "", "quần kaki nữ"),
    ("QUAN-TAY", "Quần jogger kaki nam", "cái", 319000, 146000, "M", "Đen,Be", "MXL", None, "", "quần jogger kaki bo gấu"),
    ("QUAN-TAY", "Quần tây nữ cạp cao", "cái", 339000, 155000, "L", "Đen", "SL", None, "", "quần âu nữ lưng cao"),
    ("QUAN-TAY", "Quần culottes nữ", "cái", 289000, 132000, "L", "Kem", "SL", None, "", "quần ống rộng lửng"),
    ("QUAN-TAY", "Quần tây nam slimfit", "cái", 379000, 174000, "L", "Xanh navy", "NAM", None, "", "quần âu nam ôm dáng"),
    ("QUAN-TAY", "Quần baggy vải nữ", "cái", 299000, 136000, "M", "Đen,Kem", "SL", None, "", "quần baggy vải công sở"),
    ("QUAN-TAY", "Quần kaki nam túi hộp", "cái", 359000, 165000, "L", "Xanh rêu", "NAM", None, "", "quần túi hộp cargo"),
    ("QUAN-TAY", "Quần ống rộng vải đũi nữ", "cái", 269000, 122000, "L", "Be", "SL", "summer", "", "quần đũi mát mùa hè"),
    ("QUAN-TAY", "Quần nỉ jogger unisex", "cái", 259000, 118000, "M", "Xám,Đen", "MXL", "winter", "", "quần nỉ ấm mặc nhà, tập thể dục"),
    # Quan short (12)
    ("QUAN-SHORT", "Quần short jean nữ", "cái", 229000, 104000, "M", "Xanh nhạt", "NU", "summer", "", "quần đùi jean nữ"),
    ("QUAN-SHORT", "Quần short kaki nam", "cái", 249000, 112000, "M", "Be,Đen", "NAM", "summer", "", "quần short kaki nam"),
    ("QUAN-SHORT", "Quần short thể thao nam", "cái", 159000, 70000, "M", "Đen,Xám", "MXL", None, "", "quần đùi thể thao tập gym, đá bóng"),
    ("QUAN-SHORT", "Quần short đũi nữ", "cái", 189000, 85000, "L", "Be", "SL", "summer", "", "quần short đũi mát"),
    ("QUAN-SHORT", "Quần short jean nam", "cái", 259000, 118000, "L", "Xanh đậm", "NAM", "summer", "", "quần short bò nam"),
    ("QUAN-SHORT", "Quần short nỉ unisex", "cái", 179000, 80000, "L", "Xám", "MXL", None, "", "quần short nỉ mặc nhà"),
    ("QUAN-SHORT", "Quần short lưng thun nữ", "cái", 159000, 70000, "M", "Đen,Hồng", "SL", None, "", "quần short lưng chun thoải mái"),
    ("QUAN-SHORT", "Quần short đi biển nam", "cái", 189000, 85000, "S", "Họa tiết", "MXL", "summer", "sale30", "quần đi biển nhanh khô"),
    ("QUAN-SHORT", "Quần short tây nữ", "cái", 219000, 98000, "L", "Đen", "SL", None, "", "quần short vải tây"),
    ("QUAN-SHORT", "Quần short cargo nam", "cái", 269000, 122000, "L", "Xanh rêu", "NAM", None, "", "quần short túi hộp"),
    ("QUAN-SHORT", "Quần short thun nữ mặc nhà", "cái", 99000, 44000, "M", "Xám,Hồng", "SL", None, "", "quần đùi thun mặc nhà"),
    ("QUAN-SHORT", "Quần short chạy bộ nam", "cái", 179000, 80000, "L", "Đen", "MXL", None, "", "quần short chạy bộ có lót"),
    # Vay, dam (20)
    ("VAY-DAM", "Đầm suông nữ basic", "cái", 329000, 150000, "H", "Đen,Be", "SL", None, "", "váy suông trơn đi làm, đi chơi"),
    ("VAY-DAM", "Đầm maxi đi biển", "cái", 399000, 182000, "S", "Họa tiết", "SL", "summer", "sale30", "đầm dài đi biển, du lịch"),
    ("VAY-DAM", "Đầm công sở dáng chữ A", "cái", 419000, 190000, "M", "Đen,Xanh navy", "SL", None, "", "đầm đi làm thanh lịch"),
    ("VAY-DAM", "Chân váy jean ngắn", "cái", 249000, 112000, "M", "Xanh nhạt", "SL", None, "", "chân váy bò ngắn năng động"),
    ("VAY-DAM", "Chân váy xếp ly dài", "cái", 299000, 136000, "H", "Đen,Kem", "SL", None, "", "chân váy xếp ly dáng dài"),
    ("VAY-DAM", "Đầm hoa nhí vintage", "cái", 359000, 165000, "M", "Họa tiết", "SL", None, "", "váy hoa nhí phong cách cổ điển"),
    ("VAY-DAM", "Đầm body ôm", "cái", 339000, 155000, "L", "Đen,Đỏ đô", "SL", None, "", "đầm ôm sát tôn dáng"),
    ("VAY-DAM", "Chân váy chữ A công sở", "cái", 279000, 128000, "M", "Đen", "SL", None, "", "chân váy chữ A đi làm"),
    ("VAY-DAM", "Đầm sơ mi nữ", "cái", 379000, 172000, "L", "Trắng", "SL", None, "", "váy sơ mi thắt eo"),
    ("VAY-DAM", "Đầm len dáng dài", "cái", 459000, 210000, "M", "Kem,Xám", "SL", "winter", "", "váy len ấm áp mùa đông"),
    ("VAY-DAM", "Váy yếm nữ", "cái", 299000, 136000, "L", "Xanh nhạt", "SL", None, "", "váy yếm bò dễ thương"),
    ("VAY-DAM", "Chân váy tennis", "cái", 229000, 104000, "M", "Trắng,Đen", "SL", None, "", "chân váy xếp ly ngắn kiểu tennis"),
    ("VAY-DAM", "Đầm dạ hội đuôi cá", "cái", 1450000, 690000, "S", "Đỏ đô,Đen", "SL", None, "overstock", "đầm dạ hội đi tiệc, sự kiện sang trọng"),
    ("VAY-DAM", "Đầm babydoll", "cái", 319000, 146000, "L", "Hồng", "SL", None, "", "váy babydoll trẻ trung"),
    ("VAY-DAM", "Chân váy bút chì", "cái", 259000, 118000, "L", "Đen", "SL", None, "", "chân váy ôm công sở"),
    ("VAY-DAM", "Đầm wrap thắt eo", "cái", 389000, 176000, "L", "Xanh rêu", "SL", None, "", "đầm quấn thắt eo"),
    ("VAY-DAM", "Đầm nhung tay dài", "cái", 489000, 225000, "M", "Đỏ đô,Đen", "SL", "winter", "", "đầm nhung dự tiệc cuối năm"),
    ("VAY-DAM", "Chân váy dạ ngắn", "cái", 279000, 128000, "M", "Nâu,Đen", "SL", "winter", "", "chân váy dạ mùa đông"),
    ("VAY-DAM", "Đầm xòe dự tiệc", "cái", 529000, 245000, "L", "Hồng,Trắng", "SL", None, "", "đầm xòe đi đám cưới, dự tiệc"),
    ("VAY-DAM", "Chân váy midi lụa", "cái", 319000, 146000, "L", "Kem", "SL", None, "", "chân váy lụa dáng midi"),
    # Do bo, do ngu, do boi (10)
    ("DO-BO", "Đồ bộ nữ mặc nhà cotton", "bộ", 199000, 88000, "H", "Hồng,Xanh nhạt", "SL", None, "", "đồ bộ mặc nhà thoáng mát"),
    ("DO-BO", "Bộ pijama lụa nữ", "bộ", 349000, 160000, "M", "Hồng,Đen", "SL", None, "", "đồ ngủ lụa mềm"),
    ("DO-BO", "Bộ đồ thể thao nam", "bộ", 389000, 178000, "M", "Đen,Xanh navy", "MXL", None, "", "bộ quần áo thể thao chạy bộ"),
    ("DO-BO", "Bộ đồ ngủ nam", "bộ", 259000, 118000, "L", "Xám", "MXL", None, "", "bộ đồ ngủ cotton nam"),
    ("DO-BO", "Bộ nỉ nữ mặc nhà", "bộ", 299000, 136000, "M", "Hồng,Xám", "SL", "winter", "", "bộ nỉ ấm mặc nhà mùa đông"),
    ("DO-BO", "Đồ bơi nữ một mảnh", "bộ", 159000, 185000, "S", "Đen", "SL", "summer", "clearance", "đồ bơi đi biển, hồ bơi (hàng xả mùa hè)"),
    ("DO-BO", "Đồ bơi nữ hai mảnh", "bộ", 229000, 104000, "S", "Họa tiết", "SL", "summer", "sale30", "bikini hai mảnh đi biển"),
    ("DO-BO", "Bộ đồ tập yoga nữ", "bộ", 359000, 165000, "M", "Đen,Tím", "SL", None, "", "đồ tập yoga, gym co giãn"),
    ("DO-BO", "Bộ pijama cotton nam", "bộ", 279000, 128000, "L", "Xanh navy", "MXL", None, "", "pijama cotton nam"),
    ("DO-BO", "Bộ đồ trẻ em cotton", "bộ", 159000, 70000, "M", "Họa tiết", "KID", None, "", "đồ bộ cotton cho bé"),
    # Phu kien (14)
    ("PHU-KIEN", "Tất cổ ngắn cotton", "đôi", 19000, 8500, "M", "", "", None, "shared", "tất vớ cotton cổ ngắn"),
    ("PHU-KIEN", "Tất cổ cao thể thao", "đôi", 29000, 13000, "M", "Trắng,Đen", "", None, "", "tất thể thao cổ cao"),
    ("PHU-KIEN", "Mũ lưỡi trai", "chiếc", 129000, 56000, "M", "Đen,Be", "", None, "", "nón kết che nắng"),
    ("PHU-KIEN", "Mũ len", "chiếc", 119000, 52000, "H", "Đen,Kem", "", "winter", "starve", "mũ len giữ ấm đầu mùa đông"),
    ("PHU-KIEN", "Khăn len quàng cổ", "chiếc", 159000, 70000, "H", "Đỏ đô,Xám", "", "winter", "starve", "khăn quàng cổ len ấm"),
    ("PHU-KIEN", "Thắt lưng da nam", "chiếc", 249000, 110000, "M", "Đen,Nâu", "", None, "", "dây nịt da bò nam"),
    ("PHU-KIEN", "Thắt lưng nữ bản nhỏ", "chiếc", 149000, 65000, "L", "Đen", "", None, "", "dây lưng nữ bản nhỏ"),
    ("PHU-KIEN", "Túi tote vải canvas", "chiếc", 129000, 55000, "M", "Kem", "", None, "", "túi vải đi học, đi chợ"),
    ("PHU-KIEN", "Túi đeo chéo nữ", "chiếc", 299000, 135000, "M", "Đen,Be", "", None, "", "túi xách đeo chéo nhỏ gọn"),
    ("PHU-KIEN", "Ví da nam", "chiếc", 249000, 112000, "L", "Đen,Nâu", "", None, "", "bóp ví da nam"),
    ("PHU-KIEN", "Kính mát thời trang", "chiếc", 199000, 85000, "S", "Đen", "", "summer", "overstock", "kính râm chống nắng"),
    ("PHU-KIEN", "Găng tay len", "đôi", 79000, 34000, "M", "Đen,Xám", "", "winter", "", "bao tay len giữ ấm khi đi xe máy"),
    ("PHU-KIEN", "Bộ 3 kẹp tóc càng cua", "bộ", 59000, 24000, "M", "", "", None, "", "kẹp tóc càng cua"),
    ("PHU-KIEN", "Khẩu trang vải 3 lớp", "chiếc", 25000, 10000, "M", "Đen,Trắng", "", None, "", "khẩu trang vải giặt được"),
]

SIZE_SETS = {
    "SXL": [("S", 0.7), ("M", 1.3), ("L", 1.2), ("XL", 0.7)],
    "SL": [("S", 0.8), ("M", 1.3), ("L", 1.0)],
    "MXL": [("M", 1.1), ("L", 1.3), ("XL", 0.8)],
    "NAM": [("29", 0.8), ("30", 1.2), ("31", 1.2), ("32", 0.8)],
    "NU": [("26", 0.8), ("27", 1.2), ("28", 1.2), ("29", 0.8)],
    "KID": [("4T", 0.9), ("6T", 1.1), ("8T", 1.0)],
    "": [],
}

COLOR_CODES = {
    "Đen": "DEN", "Trắng": "TRANG", "Be": "BE", "Xanh navy": "NAVY", "Xám": "XAM", "Đỏ đô": "DODO",
    "Hồng": "HONG", "Xanh rêu": "REU", "Nâu": "NAU", "Kem": "KEM", "Xanh nhạt": "XNHAT",
    "Xanh đậm": "XDAM", "Họa tiết": "HTIET", "Xanh dương": "XDUONG", "Caro đỏ": "CARODO",
    "Caro xanh": "CAROXA", "Sọc xanh": "SOCXA", "Tím": "TIM",
}

SURNAMES = ["Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô", "Dương", "Lý"]
FEMALE = [("Thị", "Hương"), ("Thị", "Lan"), ("Ngọc", "Ánh"), ("Thu", "Trang"), ("Thị", "Hạnh"), ("Mỹ", "Linh"),
          ("Thị", "Nhung"), ("Thanh", "Thảo"), ("Kim", "Ngân"), ("Thị", "Yến"), ("Bảo", "Ngọc"), ("Thu", "Hà"),
          ("Thị", "Mai"), ("Ngọc", "Trinh"), ("Phương", "Uyên"), ("Thị", "Vân"), ("Hoài", "Thương"), ("Thị", "Quyên"),
          ("Minh", "Thư"), ("Gia", "Hân"), ("Khánh", "Vy"), ("Thị", "Hiền"), ("Thanh", "Tâm"), ("Thùy", "Dung")]
MALE = [("Văn", "Hùng"), ("Minh", "Tuấn"), ("Quốc", "Bảo"), ("Văn", "Nam"), ("Đức", "Anh"), ("Hữu", "Phúc"),
        ("Văn", "Long"), ("Thanh", "Sơn"), ("Minh", "Quang"), ("Văn", "Dũng"), ("Hoàng", "Phong"), ("Gia", "Huy"),
        ("Văn", "Hải"), ("Đức", "Thắng"), ("Minh", "Khang"), ("Văn", "Tài"), ("Quốc", "Việt"), ("Trọng", "Nghĩa"),
        ("Văn", "Bình"), ("Công", "Danh"), ("Tấn", "Lộc"), ("Văn", "Toàn")]
PHONE_PREFIXES = ["090", "091", "093", "094", "096", "097", "098", "032", "033", "034", "035", "036", "037",
                  "038", "039", "070", "076", "077", "078", "079", "081", "083", "084", "085", "088"]
# 3 Khach hang mua o ca 2 Cua hang (cung so dien thoai) de thu tach du lieu (SM-5).
SHARED_CUSTOMERS = [("0912345601", "Trần Thị Thu Hà"), ("0987654302", "Lê Minh Tuấn"), ("0356789003", "Phạm Ngọc Ánh")]


# --------------------------------------------------------------------------- model

@dataclass
class Variant:
    sku: str
    barcode: str
    attributes: dict
    is_default: bool
    price: int
    base_cost: int
    weight: float
    min_qty: int = 0
    opening_qty: int = 0
    track_lots: bool = False
    shelf_days: int | None = None
    opening_expiry: int | None = None
    flags: set = field(default_factory=set)
    product: "Product | None" = None
    # trang thai mo phong
    stock: int = 0
    avg_cost: int = 0
    lots: list = field(default_factory=list)       # [code, expiry, remaining, seq]
    lot_seq: int = 0
    active: bool = True
    no_restock_after: int | None = None
    last_receipt_abs: int = -10 ** 9
    deact_target: bool = False


@dataclass
class Product:
    code: str
    name: str
    category: str
    unit: str
    description: str
    supplier: str
    pop: str
    flags: set
    season: str | None
    variants: list = field(default_factory=list)
    active: bool = True


@dataclass
class Supplier:
    code: str
    name: str
    phone: str
    address: str
    cycle: int
    first_day: int


@dataclass
class StoreSpec:
    code: str
    letter: str
    index: int
    kind: str
    name: str
    address: str
    phone: str
    invoice_footer: str
    bank: dict
    loyalty_earn: int
    point_value: int
    expiry_warning_days: int
    owner: dict
    staff: list
    categories: list
    suppliers: list
    open_min: int
    close_min: int
    hour_weights: dict
    weekday_mult: list
    trend_growth: float
    special_days: dict
    sale_days: dict
    day0_factor: float
    customer_prob: float
    cash_prob: float
    line_dist: list
    cancel_prob: float
    return_prob: float
    receipt_window: tuple
    stocktake_days: list
    redeem_threshold: int
    redeem_prob: float
    cost_unit: int
    cost_drift: float
    stocktake_random_lines: int
    return_reasons: list
    cancel_reasons: list
    customer_notes: list


# --------------------------------------------------------------------------- store specs

def spec_store_a() -> StoreSpec:
    return StoreSpec(
        code="store-a", letter="A", index=1, kind="GROCERY",
        name="Tạp hóa Lan",
        address="25 Nguyễn Trãi, phường Bến Thành, TP. Hồ Chí Minh",
        phone="02838123456",
        invoice_footer="Cảm ơn quý khách! Hàng còn nguyên tem, hộp được đổi trả trong 3 ngày.",
        bank={"bankBin": "970436", "accountNumber": "0071000123456", "accountName": "NGUYEN THI LAN"},
        loyalty_earn=10000, point_value=200, expiry_warning_days=15,
        owner={"login": "0901000001", "displayName": "Nguyễn Thị Lan"},
        staff=[{"login": "hoa.lan", "displayName": "Lê Thị Hoa"}, {"login": "minh.lan", "displayName": "Trần Văn Minh"}],
        categories=GROCERY_CATEGORIES,
        suppliers=[
            Supplier("NCC-A01", "Công ty TNHH Phân phối Đồ uống Phú An", "02837110001", "Lô B2, KCN Tân Bình, TP. Hồ Chí Minh", 4, -60),
            Supplier("NCC-A02", "Đại lý Sữa và Thực phẩm tươi Hồng Hà", "02837110002", "88 Lý Thường Kiệt, phường Diên Hồng, TP. Hồ Chí Minh", 3, -59),
            Supplier("NCC-A03", "Công ty CP Thực phẩm Sài Gòn Xanh", "02837110003", "12 Kinh Dương Vương, phường Bình Tây, TP. Hồ Chí Minh", 5, -58),
            Supplier("NCC-A04", "Đại lý Gia vị và Hàng khô Bình Minh", "02837110004", "Chợ Bình Tây, phường Bình Tây, TP. Hồ Chí Minh", 7, -57),
            Supplier("NCC-A05", "Công ty TNHH Hóa mỹ phẩm Gia dụng An Khang", "02837110005", "45 Cộng Hòa, phường Tân Sơn Nhất, TP. Hồ Chí Minh", 7, -56),
        ],
        open_min=6 * 60 + 30, close_min=21 * 60 + 45,
        hour_weights={6: 3, 7: 6, 8: 5, 9: 3, 10: 3, 11: 4, 12: 3, 13: 2, 14: 2, 15: 3, 16: 4, 17: 6, 18: 7, 19: 6, 20: 4, 21: 2},
        weekday_mult=[0.9, 0.9, 0.95, 0.95, 1.05, 1.25, 1.3],
        trend_growth=1.15,
        special_days={
            day_of(date(2026, 10, 20)): 1.7,   # 20/10 ngay Phu nu Viet Nam
            day_of(date(2026, 11, 20)): 1.5,   # 20/11 ngay Nha giao
            day_of(date(2026, 12, 5)): 1.9,    # thu Bay: khach dat hang cho dam tiec
            **{day_of(date(2026, 11, d)): 0.75 for d in range(2, 9)},  # tuan mua lon, vang khach
        },
        sale_days={},
        day0_factor=0.4,
        customer_prob=0.35, cash_prob=0.7,
        line_dist=[(1, 0.28), (2, 0.25), (3, 0.2), (4, 0.13), (5, 0.08), (6, 0.06)],
        cancel_prob=0.025, return_prob=0.035,
        receipt_window=(7 * 60, 8 * 60 + 45),
        stocktake_days=[day_of(date(2026, 10, 31)), day_of(date(2026, 11, 30))],
        redeem_threshold=50, redeem_prob=0.4,
        cost_unit=100, cost_drift=0.04,
        stocktake_random_lines=8,
        return_reasons=["Mua nhầm loại", "Hộp bị móp", "Khách đổi ý", "Mua dư"],
        cancel_reasons=["Khách đổi ý không mua", "Nhập nhầm sản phẩm", "Khách không đủ tiền"],
        customer_notes=["Khách quen", "Hay mua buổi sáng", "Mua cho quán nước", "Nhà gần chợ", "Thường chuyển khoản"],
    )


def spec_store_b() -> StoreSpec:
    return StoreSpec(
        code="store-b", letter="B", index=2, kind="FASHION",
        name="Thời trang Mai",
        address="118 Trần Phú, phường Hà Đông, Hà Nội",
        phone="02437654321",
        invoice_footer="Cảm ơn bạn đã mua sắm tại Thời trang Mai! Đổi size trong 7 ngày, giữ nguyên tem mác.",
        bank={"bankBin": "970422", "accountNumber": "0001234567890", "accountName": "PHAM THI MAI"},
        loyalty_earn=10000, point_value=300, expiry_warning_days=30,
        owner={"login": "0902000001", "displayName": "Phạm Thị Mai"},
        staff=[{"login": "linh.mai", "displayName": "Đỗ Thùy Linh"}, {"login": "tuan.mai", "displayName": "Ngô Anh Tuấn"}],
        categories=FASHION_CATEGORIES,
        suppliers=[
            Supplier("NCC-B01", "Xưởng may Hòa Bình", "02433110001", "Cụm công nghiệp Vạn Phúc, phường Hà Đông, Hà Nội", 7, -58),
            Supplier("NCC-B02", "Công ty TNHH Dệt may Phương Nam", "02833110002", "Lô C5, KCN Tân Tạo, TP. Hồ Chí Minh", 10, -55),
            Supplier("NCC-B03", "Xưởng Jean Indigo", "02433110003", "Thôn Đông, xã Thanh Oai, Hà Nội", 9, -57),
            Supplier("NCC-B04", "Cơ sở Len và Áo khoác Đà Lạt Xanh", "02633110004", "22 Phan Đình Phùng, phường Xuân Hương, Lâm Đồng", 6, -56),
            Supplier("NCC-B05", "Đại lý Phụ kiện Minh Khôi", "02433110005", "Chợ Ninh Hiệp, xã Phù Đổng, Hà Nội", 14, -54),
        ],
        open_min=8 * 60 + 30, close_min=21 * 60 + 30,
        hour_weights={8: 1, 9: 2, 10: 3, 11: 3, 12: 2, 13: 2, 14: 3, 15: 4, 16: 5, 17: 5, 18: 6, 19: 7, 20: 6, 21: 3},
        weekday_mult=[0.7, 0.75, 0.8, 0.85, 1.1, 1.6, 1.7],
        trend_growth=1.45,
        special_days={
            day_of(date(2026, 10, 20)): 2.2,   # 20/10
            day_of(date(2026, 11, 11)): 2.0,   # 11/11
            day_of(date(2026, 11, 20)): 1.8,   # 20/11
            day_of(date(2026, 11, 27)): 3.2,   # Black Friday
            day_of(date(2026, 11, 28)): 1.6,
            day_of(date(2026, 12, 12)): 2.4,   # 12/12
        },
        sale_days={
            day_of(date(2026, 11, 11)): 11,
            day_of(date(2026, 11, 27)): 20,
            day_of(date(2026, 11, 28)): 20,
            day_of(date(2026, 12, 12)): 12,
        },
        day0_factor=0.35,
        customer_prob=0.6, cash_prob=0.4,
        line_dist=[(1, 0.55), (2, 0.3), (3, 0.12), (4, 0.03)],
        cancel_prob=0.016, return_prob=0.05,
        receipt_window=(9 * 60, 10 * 60 + 45),
        stocktake_days=[day_of(date(2026, 10, 31)), day_of(date(2026, 11, 30))],
        redeem_threshold=100, redeem_prob=0.35,
        cost_unit=1000, cost_drift=0.02,
        stocktake_random_lines=6,
        return_reasons=["Không vừa size", "Không hợp màu", "Khách đổi ý", "Lỗi đường may"],
        cancel_reasons=["Khách đổi ý không mua", "Nhập nhầm size", "Khách không đủ tiền"],
        customer_notes=["Khách quen", "Thích đồ công sở", "Mặc size M", "Hay mua dịp sale", "Mua quà tặng"],
    )


# --------------------------------------------------------------------------- simulator

class StoreSim:
    def __init__(self, spec: StoreSpec):
        self.spec = spec
        self.rng = random.Random(RANDOM_SEED * 10 + spec.index)
        self.products: list[Product] = []
        self.variants: list[Variant] = []
        self.by_sku: dict[str, Variant] = {}
        self.supplier_variants: dict[str, list[Variant]] = defaultdict(list)
        self.customers: list[dict] = []
        self.customer_weights: list[float] = []
        self.loyalty: dict[str, int] = {}
        self.used_minutes: dict[int, set] = defaultdict(set)
        self.pending: dict[int, list] = defaultdict(list)
        self.orders: list[dict] = []
        self.order_state: dict[str, dict] = {}
        self.receipts: list[dict] = []
        self.stocktakes: list[dict] = []
        self.cancellations: list[dict] = []
        self.returns: list[dict] = []
        self.deactivations: list[dict] = []
        self.seq = 0
        self.stats = defaultdict(int)
        self.deact_plan: dict[int, list] = defaultdict(list)
        self.stocktake_zero: dict[int, list] = defaultdict(list)
        self.rate_per_weight = 0.0
        self.expected_lines = 0.0
        self.order_plan: dict[int, int] = {}

    # ---- catalog -------------------------------------------------------------------
    def build_catalog(self):
        spec = self.spec
        sup_of = {c: s for c, _, s in spec.categories}
        if spec.kind == "GROCERY":
            for i, (cat, name, unit, price, cost, pop, shelf, flags, desc) in enumerate(GROCERY, start=1):
                fl = set(flags.split())
                code = f"TH{i:04d}"
                p = Product(code, name, cat, unit, desc, sup_of[cat], pop, fl, None)
                vspec = GROCERY_VARIANTS.get(name)
                if vspec:
                    fsum = sum(f for *_, f in vspec)
                    for j, (key, val, vcode, f) in enumerate(vspec, start=1):
                        v = Variant(f"{code}-{vcode}", ean13(f"8931{i:04d}{j:04d}"), {key: val}, False, price, cost,
                                    POP_WEIGHT[pop] * f / fsum)
                        p.variants.append(v)
                else:
                    bc = SHARED_SOCK_BARCODE if "shared" in fl else ean13(f"8931{i:04d}0000")
                    p.variants.append(Variant(code, bc, {}, True, price, cost, POP_WEIGHT[pop]))
                for v in p.variants:
                    v.product = p
                    v.flags = fl
                    if "daily" in fl:          # hang thiet yeu mua hang ngay (trung, banh mi, sua)
                        v.weight *= 2.5
                    if shelf:
                        v.track_lots = True
                        v.shelf_days = shelf
                    for tok in fl:
                        if tok.startswith("near:"):
                            v.opening_expiry = int(tok.split(":")[1])
                self.products.append(p)
            assert len(self.products) == 150, len(self.products)
        else:
            for i, (cat, name, unit, price, cost, pop, colors, sizes, season, flags, desc) in enumerate(FASHION, start=1):
                fl = set(flags.split())
                code = f"TT{i:04d}"
                p = Product(code, name, cat, unit, desc, sup_of[cat], pop, fl, season)
                color_list = [c for c in colors.split(",") if c]
                size_list = SIZE_SETS[sizes]
                combos = []
                cfac = [1.2, 0.8, 0.7]
                if color_list and size_list:
                    for ci, c in enumerate(color_list):
                        for s, sf in size_list:
                            combos.append(({"Màu": c, "Size": s}, f"{COLOR_CODES[c]}-{s}", cfac[ci] * sf))
                elif color_list:
                    for ci, c in enumerate(color_list):
                        combos.append(({"Màu": c}, COLOR_CODES[c], cfac[ci]))
                elif size_list:
                    for s, sf in size_list:
                        combos.append(({"Size": s}, s, sf))
                if not combos:
                    bc = SHARED_SOCK_BARCODE if "shared" in fl else ean13(f"8932{i:04d}0000")
                    p.variants.append(Variant(code, bc, {}, True, price, cost, POP_WEIGHT[pop]))
                else:
                    fsum = sum(f for *_, f in combos)
                    for j, (attrs, suffix, f) in enumerate(combos, start=1):
                        p.variants.append(Variant(f"{code}-{suffix}", ean13(f"8932{i:04d}{j:04d}"), attrs, False,
                                                  price, cost, POP_WEIGHT[pop] * f * len(combos) / fsum / max(1.0, len(combos) / 2.5)))
                for v in p.variants:
                    v.product = p
                    v.flags = fl
                self.products.append(p)
            assert len(self.products) == 150, len(self.products)
        for p in self.products:
            for v in p.variants:
                self.variants.append(v)
                self.by_sku[v.sku] = v
                self.supplier_variants[p.supplier].append(v)
        assert len({v.barcode for v in self.variants}) == len(self.variants)

    def season_factor(self, p: Product, day: int) -> float:
        progress = (day - FIRST_ORDER_DAY) / (LAST_DAY - FIRST_ORDER_DAY)
        progress = min(1.0, max(0.0, progress))
        if p.season == "winter":
            return 0.5 + 1.6 * progress
        if p.season == "summer":
            return max(0.25, 1.3 - 1.1 * progress)
        return 1.0

    def avg_qty(self, v: Variant) -> float:
        if self.spec.kind == "GROCERY":
            if "multi" in v.flags:
                return 2.23
            return 1.0 if v.price >= 100000 else 1.2
        return 1.6 if v.price < 40000 else 1.12

    def expected_daily(self, v: Variant, day: int) -> float:
        return self.rate_per_weight * v.weight * self.season_factor(v.product, day) * self.avg_qty(v)

    def init_rates(self):
        e_lines = sum(k * w for k, w in self.spec.line_dist) / sum(w for _, w in self.spec.line_dist)
        self.expected_lines = e_lines
        lines_per_day = ORDER_TARGET / (LAST_DAY - FIRST_ORDER_DAY + 1) * e_lines
        total_w = sum(v.weight for v in self.variants)
        self.rate_per_weight = lines_per_day / total_w

    def set_opening(self):
        rng = self.rng
        spec = self.spec
        for v in self.variants:
            p = v.product
            d = self.expected_daily(v, FIRST_ORDER_DAY)
            if spec.kind == "GROCERY":
                if v.price >= 100000:
                    v.min_qty = 1 if p.pop in ("S", "L") else 2
                else:
                    v.min_qty = {"H": max(4, math.ceil(d * 5)), "M": max(2, math.ceil(d * 5)), "L": 2, "S": 1}[p.pop]
                if p.pop == "S":
                    q = rng.randint(6, 14)
                elif p.pop == "L":
                    q = max(5, math.ceil(d * 25)) + v.min_qty
                else:
                    q = math.ceil(d * rng.uniform(12, 16)) + v.min_qty
                if "overstock" in p.flags:
                    q = rng.randint(10, 14) if v.price >= 100000 else rng.randint(24, 36)
                if v.track_lots:
                    # Hang theo Lo: giu ton nho de lo cu ban het truoc khi het han (FEFO).
                    v.min_qty = max(1, min(v.min_qty, math.floor(d * v.shelf_days * 0.15)))
                    if v.opening_expiry is None:
                        v.opening_expiry = FIRST_ORDER_DAY + v.shelf_days - rng.randint(0, max(1, v.shelf_days // 5))
                        q = min(q, max(2, math.floor(d * (v.opening_expiry - FIRST_ORDER_DAY) * 0.35)))
                if "clearance" in p.flags:
                    q = 15
            else:
                v.min_qty = {"H": 2, "M": 1, "L": 1, "S": 1}[p.pop]
                if p.pop == "H":
                    q = 5 + (2 if v.weight > POP_WEIGHT["H"] * 0.13 else 0)
                elif p.pop == "M":
                    q = rng.randint(3, 5)
                else:
                    q = rng.randint(2, 4)
                if "overstock" in p.flags:
                    q = rng.randint(4, 6)
                if v.is_default:
                    q = {"H": 20, "M": 14, "L": 8, "S": 8}[p.pop]
                    v.min_qty = {"H": 6, "M": 4, "L": 2, "S": 2}[p.pop]
            v.opening_qty = q
            v.stock = q
            v.avg_cost = v.base_cost
            if v.track_lots and q > 0:
                v.lots.append([f"{v.sku}-L00", v.opening_expiry, q, 0])

    def setup_people(self):
        rng = self.rng
        spec = self.spec
        phones = set(ph for ph, _ in SHARED_CUSTOMERS)
        people = [{"phone": ph, "name": nm} for ph, nm in SHARED_CUSTOMERS]
        names = set(nm for _, nm in SHARED_CUSTOMERS)
        while len(people) < 50:
            if rng.random() < 0.6:
                mid, given = rng.choice(FEMALE)
            else:
                mid, given = rng.choice(MALE)
            nm = f"{rng.choice(SURNAMES)} {mid} {given}"
            if nm in names:
                continue
            ph = rng.choice(PHONE_PREFIXES) + f"{rng.randint(0, 9999999):07d}"
            if ph in phones:
                continue
            names.add(nm)
            phones.add(ph)
            people.append({"phone": ph, "name": nm})
        for i, c in enumerate(people):
            if rng.random() < 0.3:
                c["note"] = rng.choice(spec.customer_notes)
        self.customers = people
        ws = [1.0 / (i + 1) ** 0.75 for i in range(len(people))]
        rng.shuffle(ws)
        self.customer_weights = ws
        for c in people:
            self.loyalty[c["phone"]] = 0

    def plan_specials(self):
        spec = self.spec
        st1, st2 = spec.stocktake_days
        for p in self.products:
            for v in p.variants:
                if "starve" in p.flags:
                    v.no_restock_after = -21
            if "deact1" in p.flags or "deact" in p.flags:
                self.deact_plan[st1].append(("PRODUCT", p, None))
                for v in p.variants:
                    v.no_restock_after = st1 - 15 if spec.kind == "GROCERY" else FIRST_RECEIPT_DAY - 1
                    v.deact_target = True
                    self.stocktake_zero[st1].append(v)
            if "deact2" in p.flags:
                self.deact_plan[st2].append(("PRODUCT", p, None))
                for v in p.variants:
                    v.no_restock_after = st2 - 14
                    v.deact_target = True
                    self.stocktake_zero[st2].append(v)
            if "deactvar" in p.flags:
                target = next(v for v in p.variants if v.attributes.get("Màu") == "Xanh nhạt" and v.attributes.get("Size") == "32")
                target.no_restock_after = FIRST_RECEIPT_DAY - 1
                target.deact_target = True
                self.deact_plan[st2].append(("VARIANT", p, target))
                self.stocktake_zero[st2].append(target)

    def plan_orders(self):
        rng = self.rng
        spec = self.spec
        days = list(range(FIRST_ORDER_DAY, LAST_DAY + 1))
        w = {}
        for d in days:
            progress = (d - FIRST_ORDER_DAY) / (LAST_DAY - FIRST_ORDER_DAY)
            x = spec.weekday_mult[weekday(d)] * (1 + (spec.trend_growth - 1) * progress)
            x *= spec.special_days.get(d, 1.0)
            if d == LAST_DAY:
                x *= spec.day0_factor
            x *= 0.85 + 0.3 * rng.random()
            w[d] = x
        total = sum(w.values())
        raw = {d: ORDER_TARGET * w[d] / total for d in days}
        base = {d: int(raw[d]) for d in days}
        rem = ORDER_TARGET - sum(base.values())
        for d in sorted(days, key=lambda d: (-(raw[d] - base[d]), d))[:rem]:
            base[d] += 1
        self.order_plan = base

    # ---- time helpers --------------------------------------------------------------
    def day_hi(self, day: int, hi: int) -> int:
        return min(hi, DAY0_CUTOFF - 1) if day == LAST_DAY else hi

    def take_minute(self, day: int, preferred: int, lo: int, hi: int):
        hi = self.day_hi(day, hi)
        if lo > hi:
            return None
        used = self.used_minutes[day]
        preferred = max(lo, min(hi, preferred))
        for delta in range(0, hi - lo + 1):
            for m in (preferred + delta, preferred - delta):
                if lo <= m <= hi and m not in used:
                    used.add(m)
                    return m
        return None

    def random_open_minute(self, day: int):
        spec = self.spec
        hours = [h for h in spec.hour_weights if h * 60 + 59 >= spec.open_min and h * 60 < spec.close_min]
        if day == LAST_DAY:
            hours = [h for h in hours if h * 60 < DAY0_CUTOFF]
        h = wpick(self.rng, hours, [spec.hour_weights[x] for x in hours])
        m = h * 60 + self.rng.randint(0, 59)
        return self.take_minute(day, m, spec.open_min, spec.close_min - 1)

    def next_seq(self) -> int:
        self.seq += 1
        return self.seq

    # ---- people --------------------------------------------------------------------
    def pick_seller(self, day: int, minute: int) -> str:
        owner = self.spec.owner["login"]
        s1, s2 = self.spec.staff[0]["login"], self.spec.staff[1]["login"]
        wd = weekday(day)
        if self.rng.random() < 0.1:
            return owner
        if minute < 12 * 60:
            return owner if wd == 6 else s1      # ca sang nghi Chu nhat
        if minute < 17 * 60:
            return owner
        return owner if wd == 0 else s2          # ca toi nghi Thu Hai

    def is_staff(self, login: str) -> bool:
        return login != self.spec.owner["login"]

    # ---- stock helpers -------------------------------------------------------------
    @staticmethod
    def first_lot_expiry(v: Variant) -> int:
        live = [l for l in v.lots if l[2] > 0]
        return min(live, key=lambda l: (l[1], l[3]))[1] if live else 10 ** 6

    def lot_blocked(self, v: Variant, day: int) -> bool:
        if not v.track_lots:
            return False
        live = [l for l in v.lots if l[2] > 0]
        if not live:
            return False
        first = min(live, key=lambda l: (l[1], l[3]))
        return first[1] < day

    def consume(self, v: Variant, qty: int):
        assert 0 < qty <= v.stock, (v.sku, qty, v.stock)
        v.stock -= qty
        if v.track_lots:
            left = qty
            for lot in sorted(v.lots, key=lambda l: (l[1], l[3])):
                if left == 0:
                    break
                take = min(lot[2], left)
                lot[2] -= take
                left -= take
            assert left == 0
            assert v.stock == sum(l[2] for l in v.lots)

    def sellable(self, day: int, exclude: set):
        items, weights = [], []
        for v in self.variants:
            if not v.active or v.stock <= 0 or v.sku in exclude:
                continue
            if self.lot_blocked(v, day):
                self.stats["lot_blocked_checks"] += 1
                continue
            w = v.weight * self.season_factor(v.product, day)
            if v.track_lots and self.first_lot_expiry(v) - day <= 5:
                w *= 3.0                       # hang sap het han duoc bay ra truoc quay
            if w > 0:
                items.append(v)
                weights.append(w)
        return items, weights

    def pick_qty(self, v: Variant) -> int:
        r = self.rng.random()
        if self.spec.kind == "GROCERY":
            if "multi" in v.flags:
                return 1 if r < 0.45 else 2 if r < 0.70 else 3 if r < 0.82 else 4 if r < 0.90 else 6
            if v.price >= 100000:
                return 1
            return 1 if r < 0.8 else 2
        if v.price < 40000:
            return 1 if r < 0.6 else 2 if r < 0.9 else 3
        return 1 if r < 0.88 else 2

    # ---- events --------------------------------------------------------------------
    def run(self):
        self.build_catalog()
        self.init_rates()
        self.set_opening()
        self.setup_people()
        self.plan_specials()
        self.plan_orders()
        self.used_minutes[SETUP_DAY].add(SETUP_MINUTE)
        for day in range(FIRST_RECEIPT_DAY, LAST_DAY + 1):
            self.run_day(day)

    def run_day(self, day: int):
        spec = self.spec
        heap = []

        def push(minute, kind, payload):
            heapq.heappush(heap, (minute, self.next_seq(), kind, payload))

        for sup in spec.suppliers:
            if day >= sup.first_day and (day - sup.first_day) % sup.cycle == 0:
                lo, hi = spec.receipt_window
                m = self.take_minute(day, self.rng.randint(lo, hi), lo, hi)
                if m is not None:
                    push(m, "receipt", sup)
        for kind, p, v in self.deact_plan.get(day, []):
            m = self.take_minute(day, spec.close_min + 10, spec.close_min, spec.close_min + 40)
            push(m, "deactivate", (kind, p, v))
        if day in spec.stocktake_days:
            m = self.take_minute(day, spec.close_min + 45, spec.close_min + 41, spec.close_min + 80)
            push(m, "stocktake", None)
        for _ in range(self.order_plan.get(day, 0)):
            m = self.random_open_minute(day)
            if m is None:
                self.stats["order_no_slot"] += 1
                continue
            push(m, "order", None)
        for m, payload in self.pending.pop(day, []):
            push(m, "return", payload)

        while heap:
            minute, _, kind, payload = heapq.heappop(heap)
            if kind == "receipt":
                self.do_receipt(day, minute, payload)
            elif kind == "order":
                self.do_order(day, minute, push)
            elif kind == "cancel":
                self.do_cancel(day, minute, payload)
            elif kind == "return":
                self.do_return(day, minute, payload)
            elif kind == "deactivate":
                self.do_deactivate(day, minute, payload)
            elif kind == "stocktake":
                self.do_stocktake(day, minute)

    def receipt_cost(self, v: Variant, day: int) -> int:
        progress = (day - FIRST_RECEIPT_DAY) / (LAST_DAY - FIRST_RECEIPT_DAY)
        x = v.base_cost * (1 + self.spec.cost_drift * progress + self.rng.uniform(-0.03, 0.04))
        return round_to(x, self.spec.cost_unit)

    def do_receipt(self, day: int, minute: int, sup: Supplier):
        spec = self.spec
        lines = []
        for v in self.supplier_variants[sup.code]:
            if not v.active or (v.no_restock_after is not None and day > v.no_restock_after):
                continue
            d = self.expected_daily(v, day)
            rp = v.min_qty + math.ceil(d * (sup.cycle + 1))
            if v.stock > rp:
                continue
            if spec.kind == "GROCERY":
                target = v.min_qty + math.ceil(d * (sup.cycle + 7))
                pack = 1
                if v.track_lots:
                    target = min(target, max(rp + 1, math.floor(d * v.shelf_days * 0.4)))
                elif v.price <= 15000 and "multi" in v.flags:
                    pack = 12 if d >= 1 else 6
                elif v.price <= 40000:
                    pack = 3 if d >= 0.5 else 1
            else:
                target = v.min_qty + max(2, math.ceil(d * 24))
                pack = 1
            q = target - v.stock
            if q <= 0:
                continue
            q = -(-q // pack) * pack
            cost = self.receipt_cost(v, day)
            line = {"sku": v.sku, "quantity": q, "unitCost": cost}
            if v.track_lots:
                v.lot_seq += 1
                line["lotCode"] = f"{v.sku}-L{v.lot_seq:02d}"
                line["expiryDayOffset"] = day + v.shelf_days
            lines.append((v, line))
        if not lines:
            return
        abs_min = day * 1440 + minute
        for v, line in lines:
            q, c = line["quantity"], line["unitCost"]
            v.avg_cost = weighted_avg_cost(v.stock, v.avg_cost, q, c)
            v.stock += q
            if v.track_lots:
                v.lots.append([line["lotCode"], line["expiryDayOffset"], q, v.lot_seq])
            v.last_receipt_abs = abs_min
        n = len(self.receipts) + 1
        self.receipts.append({
            "ref": f"{spec.letter}-REC-{n:03d}",
            "supplierCode": sup.code,
            "dayOffset": day,
            "time": hhmm(minute),
            "createdBy": spec.owner["login"],
            "lines": [ln for _, ln in lines],
        })

    def do_order(self, day: int, minute: int, push):
        spec = self.spec
        rng = self.rng
        seller = self.pick_seller(day, minute)
        cust = None
        if rng.random() < spec.customer_prob:
            cust = wpick(rng, self.customers, self.customer_weights)["phone"]
        k = wpick(rng, [k for k, _ in spec.line_dist], [w for _, w in spec.line_dist])
        big = spec.special_days.get(day, 1.0) >= 1.5
        if big and rng.random() < 0.4:
            k += 1
        chosen = []
        used = set()
        for _ in range(k):
            items, weights = self.sellable(day, used)
            if not items:
                break
            v = wpick(rng, items, weights)
            used.add(v.sku)
            chosen.append([v, min(self.pick_qty(v), v.stock), None])
        if not chosen:
            self.stats["order_empty"] += 1
            return
        # giam gia tung dong
        for ln in chosen:
            v, qty = ln[0], ln[1]
            gross = v.price * qty
            r = rng.random()
            if spec.kind == "GROCERY":
                if "nearpromo" in v.flags and r < 0.8:
                    ln[2] = ("PERCENT", 20)
                elif r < 0.025 and gross >= 20000:
                    ln[2] = ("AMOUNT", 1000 if gross < 50000 else 2000)
                elif r < 0.04:
                    ln[2] = ("PERCENT", 10)
            else:
                if "sale30" in v.flags:
                    ln[2] = ("PERCENT", 30)
                elif day not in spec.sale_days and r < 0.07:
                    ln[2] = ("PERCENT", 10)
        pre = price_order([(l[0].price, l[1], l[2]) for l in chosen], None, 0)
        subtotal = pre["subtotal"]
        od = None
        r = rng.random()
        if spec.kind == "GROCERY":
            if big and subtotal >= 200000 and r < 0.6:
                od = ("PERCENT", 5)
            elif subtotal >= 30000 and subtotal % 5000 and r < 0.3:
                od = ("AMOUNT", subtotal % 5000)
        else:
            if day in spec.sale_days:
                od = ("PERCENT", spec.sale_days[day])
            elif cust and subtotal >= 500000 and r < 0.3:
                od = ("AMOUNT", 50000 if subtotal >= 1000000 else 20000)
            elif subtotal % 10000 and r < 0.4:
                od = ("AMOUNT", subtotal % 10000)
        od_amount = 0 if od is None else price_order([(l[0].price, l[1], l[2]) for l in chosen], od, 0)["order_discount"]
        points = 0
        if cust and self.loyalty[cust] >= spec.redeem_threshold and rng.random() < spec.redeem_prob:
            room = (subtotal - od_amount) // 2
            pts = min(self.loyalty[cust], room // spec.point_value)
            pts = pts // 10 * 10
            if pts >= 10:
                points = pts
        priced = price_order([(l[0].price, l[1], l[2]) for l in chosen], od, points * spec.point_value)
        total = priced["total"]
        assert total > 0
        cash_p = spec.cash_prob * (0.5 if total >= 1000000 else 1.0)
        if rng.random() < cash_p:
            payment = {"method": "CASH", "cashReceived": self.cash_received(total)}
        else:
            payment = {"method": "BANK_TRANSFER"}
        # ap dung
        abs_min = day * 1440 + minute
        state_lines = []
        for (v, qty, disc), net in zip(chosen, priced["net"]):
            unit_cost = v.avg_cost
            self.consume(v, qty)
            state_lines.append({"sku": v.sku, "qty": qty, "net": net, "unit_cost": unit_cost,
                                "returned": 0, "refunded": 0, "lot": v.track_lots, "deact": v.deact_target})
        earned = 0
        if cust:
            assert self.loyalty[cust] >= points
            earned = total // spec.loyalty_earn
            self.loyalty[cust] += earned - points
        n = len(self.orders) + 1
        ref = f"{spec.letter}-ORD-{n:04d}"
        doc = {
            "ref": ref,
            "expectedOrderCode": f"DH-{n:06d}",
            "dayOffset": day,
            "time": hhmm(minute),
            "sellerLogin": seller,
        }
        if cust:
            doc["customerPhone"] = cust
        doc_lines = []
        for v, qty, disc in chosen:
            dl = {"sku": v.sku, "quantity": qty}
            if disc:
                dl["discount"] = {"type": disc[0], "value": disc[1]}
            doc_lines.append(dl)
        doc["lines"] = doc_lines
        if od:
            doc["orderDiscount"] = {"type": od[0], "value": od[1]}
        if points:
            doc["pointsRedeemed"] = points
        doc["payment"] = payment
        doc["quotedTotal"] = total
        self.orders.append(doc)
        st = {"ref": ref, "day": day, "minute": minute, "abs": abs_min, "seller": seller, "customer": cust,
              "lines": state_lines, "total": total, "earned": earned, "redeemed": points,
              "status": "COMPLETED", "returns": 0, "cum_refund": 0, "method": payment["method"]}
        self.order_state[ref] = st
        self.schedule_followups(st, push)

    def cash_received(self, total: int) -> int:
        opts = [total]
        for unit in (5000, 10000, 20000, 50000, 100000, 200000, 500000):
            c = -(-total // unit) * unit
            if c not in opts:
                opts.append(c)
        opts = opts[:4]
        return wpick(self.rng, opts, [0.25, 0.35, 0.25, 0.15][:len(opts)])

    def schedule_followups(self, st: dict, push):
        spec = self.spec
        rng = self.rng
        day, minute = st["day"], st["minute"]
        no_lot = not any(l["lot"] for l in st["lines"])
        if rng.random() < spec.cancel_prob and no_lot and st["redeemed"] == 0 and day < LAST_DAY:
            m = self.take_minute(day, minute + rng.randint(3, 90), minute + 1, spec.close_min + 5)
            if m is not None:
                push(m, "cancel", st["ref"])
                return
        if rng.random() < spec.return_prob and st["redeemed"] == 0:
            eligible = [i for i, l in enumerate(st["lines"]) if not l["lot"] and not l["deact"]]
            if not eligible:
                return
            r = rng.random()
            all_ok = len(eligible) == len(st["lines"])
            plan = "full" if (r < 0.15 and all_ok) else "two-step" if (r < 0.32 and all_ok) else "partial"
            rday = day + rng.randint(1, 6)
            if rday >= LAST_DAY:
                return
            m = self.random_open_minute(rday)
            if m is None:
                return
            self.pending[rday].append((m, (st["ref"], plan)))

    def do_cancel(self, day: int, minute: int, ref: str):
        spec = self.spec
        st = self.order_state[ref]
        if st["status"] != "COMPLETED" or st["returns"] > 0:
            self.stats["cancel_dropped"] += 1
            return
        if any(self.by_sku[l["sku"]].last_receipt_abs > st["abs"] for l in st["lines"]):
            self.stats["cancel_dropped"] += 1
            return
        cust = st["customer"]
        if cust and self.loyalty[cust] < st["earned"]:
            self.stats["cancel_dropped"] += 1
            return
        for l in st["lines"]:
            v = self.by_sku[l["sku"]]
            v.stock += l["qty"]
        if cust:
            self.loyalty[cust] += st["redeemed"] - st["earned"]
        st["status"] = "CANCELLED"
        by = st["seller"] if self.is_staff(st["seller"]) else spec.owner["login"]
        self.cancellations.append({
            "orderRef": ref,
            "dayOffset": day,
            "time": hhmm(minute),
            "byLogin": by,
            "reason": self.rng.choice(spec.cancel_reasons),
        })

    def do_return(self, day: int, minute: int, payload):
        spec = self.spec
        rng = self.rng
        ref, plan = payload
        st = self.order_state[ref]
        if st["status"] != "COMPLETED":
            self.stats["return_dropped"] += 1
            return
        open_lines = [i for i, l in enumerate(st["lines"]) if not l["lot"] and not l["deact"] and l["qty"] > l["returned"]]
        if not open_lines:
            self.stats["return_dropped"] += 1
            return
        picks = []   # (index, qty)
        if plan in ("full", "two-step-2"):
            picks = [(i, st["lines"][i]["qty"] - st["lines"][i]["returned"]) for i in open_lines]
        elif plan == "two-step":
            i = rng.choice(open_lines)
            l = st["lines"][i]
            left = l["qty"] - l["returned"]
            picks = [(i, rng.randint(1, left - 1) if left >= 2 else left)]
        else:
            n_lines = 2 if (spec.kind == "FASHION" and len(open_lines) >= 2 and rng.random() < 0.3) else 1
            for i in sorted(rng.sample(open_lines, n_lines)):
                l = st["lines"][i]
                left = l["qty"] - l["returned"]
                picks.append((i, rng.randint(1, left)))
        refunds = []
        for i, q in picks:
            l = st["lines"][i]
            refunds.append(line_refund(l["net"], l["qty"], l["returned"], l["refunded"], q))
        refund_total = sum(refunds)
        cust = st["customer"]
        deduct = 0
        if cust:
            u = spec.loyalty_earn
            new_cum = st["cum_refund"] + refund_total
            deduct = (st["total"] - st["cum_refund"]) // u - (st["total"] - new_cum) // u
            if self.loyalty[cust] < deduct:
                self.stats["return_dropped_points"] += 1
                return
        for (i, q), rf in zip(picks, refunds):
            l = st["lines"][i]
            l["returned"] += q
            l["refunded"] += rf
            self.by_sku[l["sku"]].stock += q
        st["cum_refund"] += refund_total
        st["returns"] += 1
        if cust:
            self.loyalty[cust] -= deduct
        if all(l["returned"] == l["qty"] for l in st["lines"]):
            st["status"] = "RETURNED"
        n = len(self.returns) + 1
        method = st["method"] if rng.random() < 0.8 else ("CASH" if st["method"] == "BANK_TRANSFER" else "BANK_TRANSFER")
        self.returns.append({
            "ref": f"{spec.letter}-RET-{n:03d}",
            "orderRef": ref,
            "dayOffset": day,
            "time": hhmm(minute),
            "byLogin": self.pick_seller(day, minute),
            "refundMethod": method,
            "reason": rng.choice(spec.return_reasons),
            "lines": [{"lineNo": i + 1, "sku": st["lines"][i]["sku"], "quantity": q} for i, q in picks],
            "expectedRefundTotal": refund_total,
        })
        if plan == "two-step" and st["status"] == "COMPLETED":
            rday = day + rng.randint(1, 4)
            if rday < LAST_DAY:
                m = self.random_open_minute(rday)
                if m is not None:
                    self.pending[rday].append((m, (ref, "two-step-2")))

    def do_deactivate(self, day: int, minute: int, payload):
        kind, p, v = payload
        doc = {"dayOffset": day, "time": hhmm(minute), "byLogin": self.spec.owner["login"], "target": kind,
               "productCode": p.code}
        if kind == "PRODUCT":
            p.active = False
            for x in p.variants:
                x.active = False
        else:
            v.active = False
            doc["sku"] = v.sku
        self.deactivations.append(doc)

    def do_stocktake(self, day: int, minute: int):
        spec = self.spec
        rng = self.rng
        forced = list(self.stocktake_zero.get(day, []))
        forced_skus = {v.sku for v in forced}
        cands = [v for v in self.variants if v.active and not v.track_lots and v.stock >= 3 and v.sku not in forced_skus]
        picks = rng.sample(cands, min(len(cands), spec.stocktake_random_lines + 2))
        lines = []
        for v in forced:
            lines.append((v, 0, "Ngừng kinh doanh, đã thanh lý hết hàng còn lại"))
        for v in self.variants:
            if v.track_lots and v.active and v.sku not in forced_skus:
                expired = sum(l[2] for l in v.lots if l[2] > 0 and l[1] < day)
                if expired:
                    lines.append((v, v.stock - expired, "Hết hạn sử dụng, đã hủy"))
        for j, v in enumerate(sorted(picks, key=lambda x: x.sku)):
            if j < 2:
                lines.append((v, v.stock, None))
                continue
            delta = rng.choice([-2, -1, -1, -1, 1])
            reason = "Đếm thừa so với sổ" if delta > 0 else rng.choice(["Hư hỏng", "Móp, bể vỡ", "Thất thoát"])
            lines.append((v, v.stock + delta, reason))
        doc_lines = []
        for v, counted, reason in lines:
            dl = {"sku": v.sku, "countedQuantity": counted, "expectedSystemQuantity": v.stock}
            if reason:
                dl["reason"] = reason
            doc_lines.append(dl)
            if v.track_lots:
                assert counted <= v.stock, "du lieu mau chi giam Ton kho theo Lo hang khi kiem kho"
                if counted < v.stock:
                    self.consume(v, v.stock - counted)
            v.stock = counted
        n = len(self.stocktakes) + 1
        self.stocktakes.append({
            "ref": f"{spec.letter}-STK-{n:02d}",
            "dayOffset": day,
            "time": hhmm(minute),
            "createdBy": spec.owner["login"],
            "note": "Kiểm kho cuối tháng",
            "lines": doc_lines,
        })

    # ---- output --------------------------------------------------------------------
    def final_checks(self):
        for v in self.variants:
            assert v.stock >= 0, v.sku
            if v.track_lots:
                assert v.stock == sum(l[2] for l in v.lots), v.sku
            if v.deact_target:
                assert v.stock == 0, (v.sku, v.stock)
        for ph, bal in self.loyalty.items():
            assert bal >= 0, ph

    def to_document(self) -> dict:
        spec = self.spec
        products = []
        for p in self.products:
            vs = []
            for v in p.variants:
                vd = {
                    "sku": v.sku,
                    "barcode": v.barcode,
                    "isDefault": v.is_default,
                    "attributes": v.attributes,
                    "salePrice": v.price,
                    "openingQuantity": v.opening_qty,
                    "openingUnitCost": v.base_cost,
                    "minQuantity": v.min_qty,
                    "trackLots": v.track_lots,
                }
                if v.track_lots and v.opening_qty > 0:
                    vd["openingLot"] = {"lotCode": f"{v.sku}-L00", "expiryDayOffset": v.opening_expiry}
                vd["status"] = "ACTIVE" if v.active else "INACTIVE"
                vs.append(vd)
            products.append({
                "code": p.code,
                "name": p.name,
                "categoryCode": p.category,
                "unit": p.unit,
                "description": p.description,
                "status": "ACTIVE" if p.active else "INACTIVE",
                "variants": vs,
            })
        users = [{"login": spec.owner["login"], "displayName": spec.owner["displayName"], "role": "OWNER"}]
        users += [{"login": s["login"], "displayName": s["displayName"], "role": "STAFF"} for s in spec.staff]
        return {
            "$schema": "./schema/store-seed.schema.json",
            "formatVersion": FORMAT_VERSION,
            "generator": {
                "script": "eval/seed_gen/generate.py",
                "randomSeed": RANDOM_SEED,
                "canonicalAnchorDate": CANONICAL_ANCHOR.isoformat(),
            },
            "defaultPassword": DEMO_PASSWORD,
            "store": {
                "code": spec.code,
                "name": spec.name,
                "kind": spec.kind,
                "address": spec.address,
                "phone": spec.phone,
                "invoiceFooter": spec.invoice_footer,
                "bankAccount": spec.bank,
                "loyalty": {"earnAmountPerPoint": spec.loyalty_earn, "pointValue": spec.point_value},
                "expiryWarningDays": spec.expiry_warning_days,
                "setupDayOffset": SETUP_DAY,
                "setupTime": hhmm(SETUP_MINUTE),
            },
            "users": users,
            "categories": [{"code": c, "name": n} for c, n, _ in spec.categories],
            "suppliers": [{"code": s.code, "name": s.name, "phone": s.phone, "address": s.address} for s in spec.suppliers],
            "customers": self.customers,
            "products": products,
            "purchaseReceipts": self.receipts,
            "stocktakes": self.stocktakes,
            "orders": self.orders,
            "cancellations": self.cancellations,
            "returns": self.returns,
            "deactivations": self.deactivations,
        }


def dump_seed(doc: dict) -> str:
    """JSON hop le; moi phan tu cua mang cap 1 nam tren mot dong (de doc va de diff)."""
    out = ["{"]
    items = list(doc.items())
    for i, (k, v) in enumerate(items):
        comma = "," if i < len(items) - 1 else ""
        key = json.dumps(k, ensure_ascii=False)
        if isinstance(v, list):
            if not v:
                out.append(f"  {key}: []{comma}")
                continue
            out.append(f"  {key}: [")
            for j, it in enumerate(v):
                c2 = "," if j < len(v) - 1 else ""
                out.append("    " + json.dumps(it, ensure_ascii=False, separators=(", ", ": ")) + c2)
            out.append(f"  ]{comma}")
        else:
            body = json.dumps(v, ensure_ascii=False, indent=2).replace("\n", "\n  ")
            out.append(f"  {key}: {body}{comma}")
    out.append("}")
    return "\n".join(out) + "\n"


def generate() -> dict[str, tuple[str, StoreSim]]:
    result = {}
    for spec in (spec_store_a(), spec_store_b()):
        sim = StoreSim(spec)
        sim.run()
        sim.final_checks()
        result[f"{spec.code}.json"] = (dump_seed(sim.to_document()), sim)
    return result


def summary_line(name: str, sim: StoreSim) -> str:
    nv = sum(len(p.variants) for p in sim.products)
    return (f"{name}: products={len(sim.products)} variants={nv} customers={len(sim.customers)} "
            f"suppliers={len(sim.spec.suppliers)} orders={len(sim.orders)} receipts={len(sim.receipts)} "
            f"stocktakes={len(sim.stocktakes)} returns={len(sim.returns)} cancellations={len(sim.cancellations)} "
            f"deactivations={len(sim.deactivations)} stats={dict(sorted(sim.stats.items()))}")


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(description="Sinh du lieu mau deploy/seed/store-*.json (CB-06)")
    ap.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    ap.add_argument("--check", action="store_true", help="khong ghi; bao loi neu file tren dia khac ban sinh lai")
    args = ap.parse_args(argv)
    files = generate()
    bad = 0
    for name, (text, sim) in files.items():
        path = args.out_dir / name
        print(summary_line(name, sim))
        if args.check:
            current = path.read_bytes().decode("utf-8").replace("\r\n", "\n") if path.exists() else None
            if current != text:
                print(f"  KHAC: {path} khong khop ban sinh lai", file=sys.stderr)
                bad += 1
            else:
                print(f"  ok: {path} khop ban sinh lai")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
            print(f"  da ghi {path}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
