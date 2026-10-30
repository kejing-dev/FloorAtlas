#!/usr/bin/env python
"""Question sets for the floor map (design `sets`, `dataPipeline`, `judging`).

Reads tools/ne-data (Natural Earth, public domain; no admin_0 / admin_1 layer is read: no borders, no countries),
writes app/src/main/assets/atlas/sets.json: 6 sets, 67 targets in map degrees (x = degrees east of 30 W, 0..360;
lat), each with judging geometry (rings / lines / point), a triangulated outline mesh for areas, one fact zh / en and
a safe landing point (inside the target, as far as possible from the others: the debug `goto`). It reports a
stricter (inside-only) version of the design's safe-landing check; minSizeM stays the design value.

    python tools/make_maps.py
"""
import json
import math
import os

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
D = os.path.join(ROOT, "tools", "ne-data")
OUTDIR = os.path.join(ROOT, "app", "src", "main", "assets", "atlas")
LON0 = -30.0


def load(name):
    assert "admin" not in name
    return json.load(open(os.path.join(D, name + ".geojson"), encoding="utf-8"))["features"]


regions = load("ne_50m_geography_regions_polys")
marine = load("ne_110m_geography_marine_polys")
rivers = load("ne_50m_rivers_lake_centerlines")
places = load("ne_110m_populated_places_simple")
land = load("ne_110m_land")
lakes = load("ne_50m_lakes")

def by(feats, key, val):
    out = [f for f in feats if (f["properties"].get(key) or "").lower() == val.lower()]
    assert out, val
    return out

def pt(name):
    f = [f for f in places if f["properties"]["name"] == name][0]
    lon, lat = f["geometry"]["coordinates"]
    return [[round((lon - LON0) % 360, 2), round(lat, 2)]]

SETS = {
  "world-continents": [
    ("亚洲", "Asia", "area", by(regions, "NAME", "ASIA")), ("非洲", "Africa", "area", by(regions, "NAME", "AFRICA")),
    ("北美洲", "North America", "area", by(regions, "NAME", "NORTH AMERICA")), ("南美洲", "South America", "area", by(regions, "NAME", "SOUTH AMERICA")),
    ("南极洲", "Antarctica", "area", by(regions, "NAME", "ANTARCTICA")), ("欧洲", "Europe", "area", by(regions, "NAME", "EUROPE")),
    ("大洋洲", "Oceania", "area", by(regions, "NAME", "AUSTRALIA")),
    ("太平洋", "Pacific Ocean", "area", by(marine, "name", "North Pacific Ocean") + by(marine, "name", "South Pacific Ocean")),
    ("大西洋", "Atlantic Ocean", "area", by(marine, "name", "North Atlantic Ocean") + by(marine, "name", "South Atlantic Ocean")),
    ("印度洋", "Indian Ocean", "area", by(marine, "name", "INDIAN OCEAN")), ("北冰洋", "Arctic Ocean", "area", by(marine, "name", "Arctic Ocean")),
  ],
  "world-relief": [
    ("撒哈拉沙漠", "Sahara", "area", by(regions, "NAME", "SAHARA")), ("阿拉伯半岛", "Arabian Peninsula", "area", by(regions, "NAME", "ARABIAN PENINSULA")),
    ("卡拉哈里沙漠", "Kalahari Desert", "area", by(regions, "NAME", "KALAHARI DESERT")), ("维多利亚大沙漠", "Great Victoria Desert", "area", by(regions, "NAME", "GREAT VICTORIA DESERT")),
    ("青藏高原", "Tibetan Plateau", "area", by(regions, "NAME", "PLATEAU OF TIBET")), ("安第斯山脉", "Andes", "area", by(regions, "NAME", "ANDES")),
    ("落基山脉", "Rocky Mountains", "area", by(regions, "NAME", "ROCKY MOUNTAINS")), ("乌拉尔山脉", "Ural Mountains", "area", by(regions, "NAME", "URAL MOUNTAINS")),
    ("巴西高原", "Brazilian Highlands", "area", by(regions, "NAME", "BRAZILIAN HIGHLANDS")), ("阿尔卑斯山脉", "Alps", "area", by(regions, "NAME", "ALPS")),
    ("西伯利亚", "Siberia", "area", by(regions, "NAME", "SIBERIA")), ("格陵兰岛", "Greenland", "area", by(regions, "NAME", "GREENLAND")),
  ],
  "world-rivers": [(zh, en, "line", by(rivers, "name", n)) for zh, en, n in [
    ("尼罗河", "Nile", "Nile"), ("刚果河", "Congo", "Congo"), ("亚马孙河", "Amazon", "Amazonas"), ("巴拉那河", "Paraná", "Paraná"),
    ("密西西比河", "Mississippi", "Mississippi"), ("长江", "Yangtze", "Chang Jiang" if any((f["properties"].get("name") or "") == "Chang Jiang" for f in rivers) else "Yangtze"),
    ("恒河", "Ganges", "Ganges"), ("伏尔加河", "Volga", "Volga"), ("多瑙河", "Danube", "Donau"), ("叶尼塞河", "Yenisey", "Yenisey"), ("墨累河", "Murray", "Murray")]],
  "world-cities": [(zh, en, "point", n) for zh, en, n in [
    ("北京", "Beijing", "Beijing"), ("东京", "Tokyo", "Tokyo"), ("新德里", "New Delhi", "New Delhi"), ("开罗", "Cairo", "Cairo"), ("伦敦", "London", "London"),
    ("莫斯科", "Moscow", "Moscow"), ("纽约", "New York", "New York"), ("洛杉矶", "Los Angeles", "Los Angeles"), ("里约热内卢", "Rio de Janeiro", "Rio de Janeiro"),
    ("悉尼", "Sydney", "Sydney"), ("开普敦", "Cape Town", "Cape Town"), ("新加坡", "Singapore", "Singapore")]],
  "world-seas": [(zh, en, "area", by(marine, "name", n)) for zh, en, n in [
    ("地中海", "Mediterranean Sea", "Mediterranean Sea"), ("红海", "Red Sea", "Red Sea"), ("加勒比海", "Caribbean Sea", "Caribbean Sea"),
    ("墨西哥湾", "Gulf of Mexico", "Gulf of Mexico"), ("孟加拉湾", "Bay of Bengal", "Bay of Bengal"), ("阿拉伯海", "Arabian Sea", "Arabian Sea"),
    ("珊瑚海", "Coral Sea", "Coral Sea"), ("哈德逊湾", "Hudson Bay", "Hudson Bay"), ("鄂霍次克海", "Sea of Okhotsk", "Sea of Okhotsk"),
    ("黑海", "Black Sea", "Black Sea"), ("塔斯曼海", "Tasman Sea", "Tasman Sea")]],
  "world-lakes": [("里海", "Caspian Sea", "area", by(marine, "name", "Caspian Sea"))] + [(zh, en, "area", by(lakes, "name", n)) for zh, en, n in [
    ("贝加尔湖", "Lake Baikal", "Lake Baikal"), ("维多利亚湖", "Lake Victoria", "Lake Victoria"), ("苏必利尔湖", "Lake Superior", "Lake Superior"),
    ("的的喀喀湖", "Lake Titicaca", "Lago Titicaca"), ("坦噶尼喀湖", "Lake Tanganyika", "Lake Tanganyika"), ("巴尔喀什湖", "Lake Balkhash", "Lake Balkhash"),
    ("大熊湖", "Great Bear Lake", "Great Bear Lake"), ("拉多加湖", "Lake Ladoga", "Lake Ladoga"), ("埃尔湖", "Lake Eyre", "Lake Eyre North")]],
}

FACTS = {
  "地中海": ("约 250 万 km²，欧亚非之间", "About 2.5 million km², between Europe, Asia and Africa"),
  "红海": ("最咸的海之一，约 2,250 km 长", "One of the saltiest seas, about 2,250 km long"),
  "加勒比海": ("约 275 万 km²", "About 2.75 million km²"),
  "墨西哥湾": ("约 160 万 km²，密西西比河在此入海", "About 1.6 million km², where the Mississippi ends"),
  "孟加拉湾": ("世界最大的海湾", "The largest bay in the world"),
  "阿拉伯海": ("约 390 万 km²，印度洋北部", "About 3.9 million km², northern Indian Ocean"),
  "珊瑚海": ("大堡礁就在它的西缘", "The Great Barrier Reef lines its western edge"),
  "哈德逊湾": ("约 123 万 km²，冬季结冰", "About 1.23 million km², freezes in winter"),
  "鄂霍次克海": ("冬季大部分结冰", "Mostly frozen in winter"),
  "黑海": ("约 44 万 km²，多瑙河在此入海", "About 440,000 km², where the Danube ends"),
  "塔斯曼海": ("澳大利亚与新西兰之间", "Between Australia and New Zealand"),
  "里海": ("约 37 万 km²，世界最大的湖", "About 371,000 km², the largest lake"),
  "贝加尔湖": ("最深 1,642 米，世界最深的湖", "1,642 m deep, the deepest lake"),
  "维多利亚湖": ("非洲最大的湖，尼罗河的源头之一", "Africa's largest lake, a source of the Nile"),
  "苏必利尔湖": ("面积最大的淡水湖", "The largest freshwater lake by area"),
  "的的喀喀湖": ("海拔约 3,812 米", "About 3,812 m above sea level"),
  "坦噶尼喀湖": ("世界最长的淡水湖，约 673 km", "The longest freshwater lake, about 673 km"),
  "巴尔喀什湖": ("一半淡水一半咸水", "Half fresh water, half salt water"),
  "大熊湖": ("北美最北的大湖之一", "One of North America's northernmost big lakes"),
  "拉多加湖": ("欧洲最大的湖", "Europe's largest lake"),
  "埃尔湖": ("澳大利亚最低点，常年干涸", "Australia's lowest point, usually dry"),
  "亚洲": ("约 4,460 万 km²，最大的洲", "About 44.6 million km², the largest continent"),
  "非洲": ("约 3,030 万 km²，赤道横穿中部", "About 30.3 million km², crossed by the equator"),
  "北美洲": ("约 2,470 万 km²", "About 24.7 million km²"),
  "南美洲": ("约 1,780 万 km²，安第斯山脉纵贯西侧", "About 17.8 million km², the Andes run down its west side"),
  "南极洲": ("约 1,400 万 km²，几乎全被冰盖覆盖", "About 14 million km², almost all ice"),
  "欧洲": ("约 1,020 万 km²", "About 10.2 million km²"),
  "大洋洲": ("约 850 万 km²，最小的洲", "About 8.5 million km², the smallest continent"),
  "太平洋": ("约 1.65 亿 km²，最大最深的大洋", "About 165 million km², the largest and deepest ocean"),
  "大西洋": ("约 1.06 亿 km²，在这张图的两端", "About 106 million km², at both ends of this map"),
  "印度洋": ("约 7,060 万 km²", "About 70.6 million km²"),
  "北冰洋": ("约 1,400 万 km²，最小最浅的大洋", "About 14 million km², the smallest ocean"),
  "撒哈拉沙漠": ("约 920 万 km²，世界最大的热沙漠", "About 9.2 million km², the largest hot desert"),
  "阿拉伯半岛": ("约 320 万 km²，世界最大的半岛", "About 3.2 million km², the largest peninsula"),
  "卡拉哈里沙漠": ("约 90 万 km²", "About 900,000 km²"),
  "维多利亚大沙漠": ("澳大利亚最大的沙漠", "Australia's largest desert"),
  "青藏高原": ("平均海拔 4,000 米以上，世界最高的高原", "Average height over 4,000 m, the highest plateau"),
  "安第斯山脉": ("约 7,000 km，世界最长的山脉", "About 7,000 km, the longest mountain range"),
  "落基山脉": ("约 4,800 km，北美西部的脊梁", "About 4,800 km, the spine of western North America"),
  "乌拉尔山脉": ("亚洲与欧洲的传统分界之一", "A traditional boundary between Europe and Asia"),
  "巴西高原": ("世界面积最大的高原之一", "One of the largest plateaus on Earth"),
  "阿尔卑斯山脉": ("最高峰勃朗峰 4,806 米", "Highest peak Mont Blanc, 4,806 m"),
  "西伯利亚": ("约 1,300 万 km²，亚洲北部", "About 13 million km² of northern Asia"),
  "格陵兰岛": ("约 217 万 km²，世界最大的岛", "About 2.17 million km², the largest island"),
  "尼罗河": ("约 6,650 km，世界最长的河之一", "About 6,650 km, one of the longest rivers"),
  "刚果河": ("世界最深的河，两次穿过赤道", "The deepest river; crosses the equator twice"),
  "亚马孙河": ("流量世界第一", "The largest river by discharge"),
  "巴拉那河": ("约 4,880 km，南美第二长河", "About 4,880 km, South America's second longest"),
  "密西西比河": ("约 3,730 km，北美最长的干流", "About 3,730 km, North America's main trunk river"),
  "长江": ("约 6,300 km，亚洲最长的河", "About 6,300 km, the longest river in Asia"),
  "恒河": ("约 2,500 km，流入孟加拉湾", "About 2,500 km, flows into the Bay of Bengal"),
  "伏尔加河": ("约 3,530 km，欧洲最长的河，流入里海", "About 3,530 km, Europe's longest, ends in the Caspian"),
  "多瑙河": ("约 2,850 km，流经 10 个国家", "About 2,850 km, through 10 countries"),
  "叶尼塞河": ("约 3,490 km，向北流入北冰洋", "About 3,490 km, flows north to the Arctic Ocean"),
  "墨累河": ("约 2,500 km，澳大利亚最长的河", "About 2,500 km, Australia's longest river"),
  "北京": ("北纬 39.9°", "39.9° N"), "东京": ("北纬 35.7°", "35.7° N"), "新德里": ("北纬 28.6°", "28.6° N"),
  "开罗": ("尼罗河三角洲的起点", "At the head of the Nile delta"), "伦敦": ("几乎正好在 0° 经线上", "Almost exactly on 0° longitude"),
  "莫斯科": ("北纬 55.8°", "55.8° N"), "纽约": ("北纬 40.7°", "40.7° N"), "洛杉矶": ("北纬 34.1°", "34.1° N"),
  "里约热内卢": ("在南回归线附近", "Near the Tropic of Capricorn"), "悉尼": ("南纬 33.9°", "33.9° S"),
  "开普敦": ("非洲南端附近", "Near the southern tip of Africa"), "新加坡": ("离赤道只有约 137 km", "Only about 137 km from the equator"),
}


# ------------------------------------------------------------------ geometry

EPS = {"world-continents": 0.25, "world-cities": 0.0, "world-rivers": 0.06, "world-relief": 0.10, "world-seas": 0.10, "world-lakes": 0.03}
OCEANS = {"太平洋", "大西洋", "印度洋", "北冰洋"}
MIN_SIZE = {"world-continents": 2.8, "world-cities": 2.4, "world-rivers": 2.4, "world-relief": 2.4, "world-seas": 3.2, "world-lakes": 4.0}
LEVEL = {"world-continents": 1, "world-cities": 2, "world-rivers": 3, "world-relief": 4, "world-seas": 5, "world-lakes": 6}
NAMES = {
    "world-continents": ("大洲与大洋", "Continents & Oceans"), "world-cities": ("世界名城", "World Cities"),
    "world-rivers": ("大江大河", "Great Rivers"), "world-relief": ("地形与大区域", "Landforms & Regions"),
    "world-seas": ("海与海湾", "Seas & Gulfs"), "world-lakes": ("湖泊与内海", "Lakes & Inland Seas"),
}
ORDER = ["world-continents", "world-cities", "world-rivers", "world-relief", "world-seas", "world-lakes"]


def dp_open(pts, eps):
    if eps <= 0 or len(pts) < 3:
        return list(pts)
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i0, i1 = stack.pop()
        a, b = pts[i0], pts[i1]
        dx, dy = b[0] - a[0], b[1] - a[1]
        L = math.hypot(dx, dy)
        best, idx = 0.0, -1
        for i in range(i0 + 1, i1):
            p = pts[i]
            d = abs(dy * (p[0] - a[0]) - dx * (p[1] - a[1])) / L if L > 1e-12 else math.hypot(p[0] - a[0], p[1] - a[1])
            if d > best:
                best, idx = d, i
        if idx >= 0 and best > eps:
            keep[idx] = True
            stack += [(i0, idx), (idx, i1)]
    return [p for p, k in zip(pts, keep) if k]


def dp_ring(ring, eps):
    if len(ring) < 4 or eps <= 0:
        return ring
    # split the closed ring at its farthest-apart vertex so both halves are simplified as open lines
    far = max(range(len(ring)), key=lambda i: (ring[i][0] - ring[0][0]) ** 2 + (ring[i][1] - ring[0][1]) ** 2)
    a = dp_open(ring[: far + 1], eps)
    b = dp_open(ring[far:] + [ring[0]], eps)
    out = a[:-1] + b[:-1]
    return out if len(out) >= 3 else ring


def unwrap(coords):
    out, prev = [], None
    for lon, lat in coords:
        x = lon - LON0
        if prev is not None:
            while x - prev > 180:
                x -= 360
            while x - prev < -180:
                x += 360
        out.append((x, lat))
        prev = x
    if out and out[0] == out[-1]:
        out = out[:-1]
    return out


def clip_half(poly, keep):
    """Sutherland-Hodgman against one vertical line: keep(x) -> (inside, x_line)."""
    inside, xl = keep
    out = []
    n = len(poly)
    for k in range(n):
        p, q = poly[k], poly[(k + 1) % n]
        pin, qin = inside(p[0]), inside(q[0])
        if pin:
            out.append(p)
        if pin != qin:
            t = (xl - p[0]) / (q[0] - p[0])
            out.append((xl, p[1] + (q[1] - p[1]) * t))
    return out


def clip_to_map(poly):
    """A polygon in unwrapped x -> its pieces inside 0..360 (copies shifted by 360)."""
    xs = [p[0] for p in poly]
    pieces = []
    for shift in range(int(math.floor(min(xs) / 360.0)) * 360, int(math.ceil(max(xs) / 360.0)) * 360 + 1, 360):
        q = [(x - shift, y) for x, y in poly]
        q = clip_half(q, (lambda x: x >= 0.0, 0.0))
        if len(q) >= 3:
            q = clip_half(q, (lambda x: x <= 360.0, 360.0))
        if len(q) >= 3:
            q = [(min(360.0, max(0.0, x)), max(-80.0, min(84.0, y))) for x, y in q]
            if abs(area(q)) > 1e-4:
                pieces.append(q)
    return pieces


def area(r):
    s = 0.0
    for k in range(len(r)):
        x1, y1 = r[k]
        x2, y2 = r[(k + 1) % len(r)]
        s += x1 * y2 - x2 * y1
    return s / 2.0


def rings_of(feats):
    out = []
    for f in feats:
        g = f["geometry"]
        if g["type"] == "Polygon":
            polys = [g["coordinates"]]
        elif g["type"] == "MultiPolygon":
            polys = g["coordinates"]
        else:
            continue
        for p in polys:
            out.append(unwrap(p[0]))
    return out


def lines_of(feats):
    out = []
    for f in feats:
        g = f["geometry"]
        ls = [g["coordinates"]] if g["type"] == "LineString" else g["coordinates"] if g["type"] == "MultiLineString" else []
        for l in ls:
            cur, prev = [], None
            for lon, lat in l:
                x = (lon - LON0) % 360.0
                if prev is not None and abs(x - prev) > 180:
                    if len(cur) >= 2:
                        out.append(cur)
                    cur = []
                cur.append((x, lat))
                prev = x
            if len(cur) >= 2:
                out.append(cur)
    return out


def ear_clip(ring):
    pts = list(ring)
    if area(pts) < 0:
        pts.reverse()
    idx = list(range(len(pts)))
    tris = []

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    def inside(p, a, b, c):
        return cross(a, b, p) >= -1e-12 and cross(b, c, p) >= -1e-12 and cross(c, a, p) >= -1e-12

    guard = 0
    while len(idx) > 3 and guard < 100000:
        guard += 1
        found = False
        n = len(idx)
        for k in range(n):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % n]
            a, b, c = pts[i0], pts[i1], pts[i2]
            if cross(a, b, c) <= 1e-12:
                continue
            if any(inside(pts[j], a, b, c) for j in idx if j not in (i0, i1, i2) and pts[j] not in (a, b, c)):
                continue
            tris.append((i0, i1, i2))
            idx.pop(k)
            found = True
            break
        if not found:  # degenerate leftovers: drop the flattest vertex
            k = min(range(n), key=lambda k: abs(cross(pts[idx[k - 1]], pts[idx[k]], pts[idx[(k + 1) % n]])))
            idx.pop(k)
    if len(idx) == 3 and cross(pts[idx[0]], pts[idx[1]], pts[idx[2]]) > 0:
        tris.append(tuple(idx))
    return pts, tris


def seg_dist(px, py, ax, ay, bx, by_):
    dx, dy = bx - ax, by_ - ay
    L = dx * dx + dy * dy
    t = 0.0 if L < 1e-18 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def point_in(px, py, ring):
    c = False
    n = len(ring)
    for k in range(n):
        x1, y1 = ring[k]
        x2, y2 = ring[(k + 1) % n]
        if (y1 > py) != (y2 > py) and px < (x2 - x1) * (py - y1) / (y2 - y1) + x1:
            c = not c
    return c


class Shape:
    """Judging geometry of one target in map degrees (distance = degrees; floor metres = degrees * S)."""

    def __init__(self, kind, rings=(), lines=(), point=None):
        self.kind, self.rings, self.lines, self.point = kind, list(rings), list(lines), point
        segs = []
        for r in self.rings:
            segs += [(r[k], r[(k + 1) % len(r)]) for k in range(len(r))]
        for l in self.lines:
            segs += [(l[k], l[k + 1]) for k in range(len(l) - 1)]
        self.segs = np.array([[a[0], a[1], b[0], b[1]] for a, b in segs]) if segs else np.zeros((0, 4))

    def dist(self, x, y):
        if self.kind == "point":
            return math.hypot(x - self.point[0], y - self.point[1])
        if self.kind == "area" and any(point_in(x, y, r) for r in self.rings):
            return 0.0
        s = self.segs
        dx, dy = s[:, 2] - s[:, 0], s[:, 3] - s[:, 1]
        L = dx * dx + dy * dy
        t = np.clip(((x - s[:, 0]) * dx + (y - s[:, 1]) * dy) / np.where(L < 1e-18, 1, L), 0, 1)
        return float(np.min(np.hypot(x - s[:, 0] - t * dx, y - s[:, 1] - t * dy)))

    def candidates(self):
        if self.kind == "point":
            return [tuple(self.point)]
        if self.kind == "line":
            out = []
            for l in self.lines:
                for k in range(len(l) - 1):
                    for f in (0.0, 0.5):
                        out.append((l[k][0] + (l[k + 1][0] - l[k][0]) * f, l[k][1] + (l[k + 1][1] - l[k][1]) * f))
            return out
        out = []
        for r in self.rings:
            xs, ys = [p[0] for p in r], [p[1] for p in r]
            n = 28
            for a in range(n):
                for b in range(n):
                    x = min(xs) + (max(xs) - min(xs)) * (a + 0.5) / n
                    y = min(ys) + (max(ys) - min(ys)) * (b + 0.5) / n
                    if point_in(x, y, r):
                        out.append((x, y))
        return out


# ------------------------------------------------------------------ build

def slug(en):
    return "".join(ch if ch.isalnum() else "-" for ch in en.lower().replace("ã", "a").replace("á", "a")).strip("-").replace("--", "-")


sets_out = []
report = []
for sid in ORDER:
    eps = EPS[sid]
    targets, shapes = [], []
    for zh, en, kind, src in SETS[sid]:
        t = {"id": slug(en), "zh": zh, "en": en, "kind": kind, "fz": FACTS[zh][0], "fe": FACTS[zh][1]}
        if kind == "point":
            p = pt(src)[0]
            t["point"] = [p[0], p[1]]
            shape = Shape("point", point=(p[0], p[1]))
        elif kind == "line":
            lines = [dp_open(l, eps) for l in lines_of(src)]
            lines = [l for l in lines if len(l) >= 2]
            t["lines"] = [[round(v, 3) for p in l for v in p] for l in lines]
            shape = Shape("line", lines=lines)
        else:
            e = 0.4 if zh in OCEANS else eps
            pieces = []
            for r in rings_of(src):
                for q in clip_to_map(r):
                    q = dp_ring(q, e)
                    if len(q) >= 3 and abs(area(q)) > (0.5 if sid == "world-continents" else 0.002):
                        pieces.append(q)
            t["rings"] = [[round(v, 3) for p in q for v in p] for q in pieces]
            verts, idx = [], []
            for q in pieces:
                pts, tris = ear_clip(q)
                base = len(verts) // 2
                verts += [round(v, 3) for p in pts for v in p]
                idx += [base + i for tri in tris for i in tri]
            t["mesh"] = {"v": verts, "i": idx}
            shape = Shape("area", rings=pieces)
        targets.append(t)
        shapes.append(shape)
    # safe landing point: inside (or on) the target, as far as possible from every other target of the set
    worst = []
    for k, (t, s) in enumerate(zip(targets, shapes)):
        best, bp = -1.0, None
        cands = s.candidates()
        if len(cands) > 400:
            cands = cands[:: max(1, len(cands) // 400)]
        for x, y in cands:
            m = min(o.dist(x, y) for j, o in enumerate(shapes) if j != k)
            if m > best:
                best, bp = m, (x, y)
        t["anchor"] = [round(bp[0], 3), round(bp[1], 3)]
        need = 36.0 / best  # floor size (m) at which the margin is 0.10 m
        worst.append((need, t["zh"]))
        t["tris"] = len(t.get("mesh", {}).get("i", [])) // 3
    need = max(worst)
    # the design rule allows the landing point up to 0.04 m outside the target (validated at design time with
    # raster masks); this check is stricter (inside only), so it is reported, not applied
    minsize = MIN_SIZE[sid]
    report.append((sid, MIN_SIZE[sid], round(need[0], 2), need[1], minsize, max(t["tris"] for t in targets)))
    sets_out.append({"id": sid, "zh": NAMES[sid][0], "en": NAMES[sid][1], "level": LEVEL[sid], "minSizeM": minsize, "targets": targets})

LANDFORMS = [
    # featured region, zh, en, lon, lat, height m (0 = none), fact zh, fact en
    ("himalaya", "喜马拉雅山脉", "Himalayas", 84.5, 28.6, 0, "世界最高的山脉，14 座 8,000 米级山峰中有 10 座在这里", "The highest range, with 10 of the 14 peaks over 8,000 m"),
    ("himalaya", "珠穆朗玛峰", "Mount Everest", 86.925, 27.988, 8849, "世界最高峰", "The highest mountain on Earth"),
    ("himalaya", "干城章嘉峰", "Kangchenjunga", 88.147, 27.703, 8586, "世界第三高峰", "The third highest mountain"),
    ("himalaya", "纳木错", "Namtso", 90.6, 30.7, 4718, "世界上海拔最高的大型咸水湖之一", "One of the highest large salt lakes"),
    ("himalaya", "羊卓雍错", "Yamdrok Lake", 90.7, 28.95, 4441, "藏南的高原湖泊", "A high lake in southern Tibet"),
    ("himalaya", "雅鲁藏布江", "Yarlung Tsangpo", 91.5, 29.3, 0, "世界海拔最高的大河之一，下游是大峡谷", "One of the highest big rivers, ending in a great canyon"),
    ("himalaya", "柴达木盆地", "Qaidam Basin", 94.5, 37.0, 0, "青藏高原北部的大盆地，多盐湖", "A large basin with salt lakes north of the plateau"),
    ("colorado", "大峡谷", "Grand Canyon", -112.1, 36.1, 0, "科罗拉多河切出的峡谷，深约 1,800 米", "Carved by the Colorado River, about 1,800 m deep"),
    ("colorado", "科罗拉多河", "Colorado River", -111.6, 36.75, 0, "约 2,330 km，流向加利福尼亚湾", "About 2,330 km, flows to the Gulf of California"),
    ("colorado", "米德湖", "Lake Mead", -114.35, 36.2, 0, "胡佛水坝拦出的水库", "The reservoir behind Hoover Dam"),
    ("colorado", "鲍威尔湖", "Lake Powell", -110.9, 37.3, 0, "格伦峡谷水坝拦出的水库", "The reservoir behind Glen Canyon Dam"),
    ("colorado", "纪念碑谷", "Monument Valley", -110.1, 36.98, 0, "红色砂岩孤峰", "Red sandstone buttes"),
    ("colorado", "科罗拉多高原", "Colorado Plateau", -109.3, 38.2, 0, "平均海拔约 1,600 米的高原", "A plateau about 1,600 m high"),
    ("rift", "乞力马扎罗山", "Kilimanjaro", 37.355, -3.067, 5895, "非洲最高峰，一座休眠火山", "Africa's highest peak, a dormant volcano"),
    ("rift", "肯尼亚山", "Mount Kenya", 37.308, -0.152, 5199, "非洲第二高峰", "Africa's second highest peak"),
    ("rift", "维多利亚湖", "Lake Victoria", 33.0, -1.0, 1134, "非洲最大的湖", "Africa's largest lake"),
    ("rift", "坦噶尼喀湖", "Lake Tanganyika", 29.7, -6.2, 773, "世界最长的淡水湖", "The longest freshwater lake"),
    ("rift", "恩戈罗恩戈罗火山口", "Ngorongoro Crater", 35.588, -3.161, 0, "直径约 19 km 的完整火山口", "An intact caldera about 19 km across"),
    ("rift", "东非大裂谷", "Great Rift Valley", 36.2, -2.3, 0, "地壳正在裂开的地方", "Where the crust is pulling apart"),
]

out = {
    "lon0": LON0,
    "sets": sets_out,
    "landforms": [{"region": r, "zh": zh, "en": en, "x": round((lon - LON0) % 360.0, 3), "lat": lat, "h": h, "fz": fz, "fe": fe} for r, zh, en, lon, lat, h, fz, fe in LANDFORMS],
    "regionNames": {"himalaya": ["喜马拉雅—青藏高原", "Himalaya & Tibet"], "colorado": ["科罗拉多高原—大峡谷", "Colorado Plateau & Grand Canyon"], "rift": ["东非大裂谷—乞力马扎罗", "East African Rift & Kilimanjaro"]},
    "sources": "Natural Earth (public domain): geography regions, marine polygons, lakes, rivers, populated places.",
}
os.makedirs(OUTDIR, exist_ok=True)
s = json.dumps(out, ensure_ascii=False, separators=(",", ":"))
open(os.path.join(OUTDIR, "sets.json"), "w", encoding="utf-8").write(s)
print("sets.json", len(s), "bytes")
for r in report:
    print("%-17s design %.1f m  needs %.2f m (tightest %s)  -> minSizeM %.1f  max tris %d" % r)
print("targets:", sum(len(x["targets"]) for x in sets_out))
