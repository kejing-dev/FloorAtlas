#!/usr/bin/env python
"""Terrain tiles for the floor map (design `terrain`).

Imagery : NASA Blue Marble Next Generation, Shaded Relief + Bathymetry, from NASA GIBS WMTS (EPSG:4326, 500m set).
Heights : AWS Terrain Tiles (Mapzen "terrarium" PNG; sources ETOPO1 / SRTM / GMTED, public domain), sampled per vertex.

The app's map is plate carree from 30 W eastwards over 360 deg, 84 N to 80 S (aspect 360:164). Level z has
2^(z+1) x 2^z tiles of 512 x 512 px; tile (z, i, j) spans x = i*180/2^z .. +180/2^z deg east of 30 W and
lat = 84 - j*164/2^z .. -164/2^z. Level 4 is resampled from GIBS level 5; levels 3..0 are box-downsampled from
their children; levels 5 and 6 exist only inside the featured regions (GIBS 6 and 7).

Each tile also gets a 33 x 33 grid of heights (metres above sea level, sea floor clamped to 0) at the vertex
positions (row 0 = north edge, column 0 = west edge), int16 little endian: tiles/z/i_j.h16.
Also writes elev.h16: a 1025 x 513 global grid at the level-4 vertices (draping, cursor height) and index.json.

    python tools/make_tiles.py            # downloads into tools/.cache (kept), writes app/src/main/assets/atlas/
"""
import concurrent.futures as cf
import io
import json
import math
import os
import struct
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
CACHE = os.path.join(ROOT, "tools", ".cache")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "atlas")
GIBS = "https://gibs.earthdata.nasa.gov/wmts/epsg4326/best/BlueMarble_ShadedRelief_Bathymetry/default/default/500m/{s}/{r}/{c}.jpeg"
TERRA = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
LON0, LAT_TOP, LAT_SPAN = -30.0, 84.0, 164.0
PX, GRID = 512, 33
QUALITY = 82
GLOBAL_MAX = 4

# Featured landform regions (design terrain.featured.v1): lon/lat boxes; levels 5 and 6 exist inside them.
FEATURED = {
    "himalaya": (78.0, 98.0, 26.0, 37.5),
    "colorado": (-114.5, -107.5, 34.5, 39.0),
    "rift": (29.0, 39.5, -9.0, 1.5),
}


def tile_box(z, i, j):
    w = 180.0 / 2 ** z
    h = LAT_SPAN / 2 ** z
    x0 = i * w
    return x0, x0 + w, LAT_TOP - j * h, LAT_TOP - (j + 1) * h  # x west, x east, lat north, lat south


def fetch(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "floor-atlas-build/0.1 (offline tile build)"})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
            with open(path + ".part", "wb") as f:
                f.write(data)
            os.replace(path + ".part", path)
            return path
        except Exception as e:  # noqa: BLE001
            if attempt == 4:
                raise RuntimeError(f"{url}: {e}")
            time.sleep(1.5 * (attempt + 1))


# ---------------------------------------------------------------- imagery


def gibs_path(s, r, c):
    return os.path.join(CACHE, "gibs", str(s), f"{r}_{c}.jpg")


def gibs_cols(s):
    return int(round(360.0 / (288.0 / 2 ** s)))


def gibs_needed(s, lon_w, lon_e, lat_n, lat_s):
    T = 288.0 / 2 ** s
    cols = gibs_cols(s)
    c0 = math.floor((lon_w + 180.0) / T)
    c1 = math.floor((lon_e + 180.0 - 1e-9) / T)
    r0 = max(0, math.floor((90.0 - lat_n) / T))
    r1 = math.floor((90.0 - lat_s - 1e-9) / T)
    return [(r, c % cols, c) for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)]


def render_tile(z, i, j, s):
    """Resamples GIBS level s into our tile (z, i, j)."""
    xw, xe, ln, ls = tile_box(z, i, j)
    lon_w, lon_e = LON0 + xw, LON0 + xe  # may run past 180: columns wrap
    T = 288.0 / 2 ** s
    need = gibs_needed(s, lon_w, lon_e, ln, ls)
    cs = sorted({u for _, _, u in need})
    rs = sorted({r for r, _, _ in need})
    mosaic = Image.new("RGB", (len(cs) * PX, len(rs) * PX))
    for r, c, u in need:
        im = Image.open(gibs_path(s, r, c)).convert("RGB")
        mosaic.paste(im, ((u - cs[0]) * PX, (r - rs[0]) * PX))
    ppd = PX / T
    bx0 = (lon_w + 180.0 - cs[0] * T) * ppd
    bx1 = (lon_e + 180.0 - cs[0] * T) * ppd
    by0 = (90.0 - ln - rs[0] * T) * ppd
    by1 = (90.0 - ls - rs[0] * T) * ppd
    return mosaic.resize((PX, PX), Image.LANCZOS, box=(bx0, by0, bx1, by1))


# ---------------------------------------------------------------- heights


class Terrarium:
    def __init__(self, z):
        self.z = z
        self.cache = {}

    def path(self, x, y):
        return os.path.join(CACHE, "terra", str(self.z), f"{x}_{y}.png")

    def tile(self, x, y):
        key = (x, y)
        if key not in self.cache:
            a = np.asarray(Image.open(self.path(x, y)).convert("RGB")).astype(np.float64)
            self.cache[key] = a[..., 0] * 256.0 + a[..., 1] + a[..., 2] / 256.0 - 32768.0
        return self.cache[key]

    def xy(self, lon, lat):
        n = 2 ** self.z
        lon = ((lon + 180.0) % 360.0) - 180.0
        lat = max(-85.0, min(85.0, lat))
        fx = (lon + 180.0) / 360.0 * n
        fy = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n
        return fx, fy

    def needed(self, lon_w, lon_e, lat_n, lat_s):
        n = 2 ** self.z
        x0, y0 = self.xy(lon_w, lat_n)
        x1, y1 = self.xy(lon_e - 1e-7, lat_s)
        xs = range(int(math.floor((lon_w + 180.0) / 360.0 * n)), int(math.floor((lon_e + 180.0 - 1e-7) / 360.0 * n)) + 1)
        return {(x % n, y) for x in xs for y in range(int(y0), min(n - 1, int(y1)) + 1)}

    def height(self, lon, lat):
        n = 2 ** self.z
        fx, fy = self.xy(lon, lat)
        tx, ty = int(fx) % n, min(n - 1, int(fy))
        a = self.tile(tx, ty)
        px = min(255, int((fx - math.floor(fx)) * 256))
        py = min(255, int((fy - math.floor(fy)) * 256))
        return a[py, px]


def grid_heights(terra, xw, xe, ln, ls, n=GRID):
    out = np.zeros((n, n), dtype=np.int16)
    for r in range(n):
        lat = ln + (ls - ln) * r / (n - 1)
        for c in range(n):
            lon = LON0 + xw + (xe - xw) * c / (n - 1)
            out[r, c] = int(max(0.0, min(9000.0, terra.height(lon, lat))))
    return out


# ---------------------------------------------------------------- plan


def featured_tiles(z):
    """Tiles of level z that touch a featured box."""
    out = set()
    n_i, n_j = 2 ** (z + 1), 2 ** z
    for lo0, lo1, la0, la1 in FEATURED.values():
        x0, x1 = (lo0 - LON0) % 360.0, (lo1 - LON0) % 360.0
        for i in range(n_i):
            for j in range(n_j):
                xw, xe, ln, ls = tile_box(z, i, j)
                if xe > x0 and xw < x1 and ln > la0 and ls < la1:
                    out.add((i, j))
    return sorted(out)


def run_pool(jobs, workers=16, label=""):
    done = 0
    with cf.ThreadPoolExecutor(workers) as ex:
        for f in cf.as_completed([ex.submit(*j) for j in jobs]):
            f.result()
            done += 1
            if done % 100 == 0:
                print(f"  {label} {done}/{len(jobs)}", flush=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    level4 = [(i, j) for i in range(32) for j in range(16)]
    feat = {5: featured_tiles(5), 6: featured_tiles(6)}
    print("featured tiles:", {k: len(v) for k, v in feat.items()}, flush=True)

    # downloads: imagery
    gjobs = {}
    for z, s, tiles in [(4, 5, level4), (5, 6, feat[5]), (6, 7, feat[6])]:
        for i, j in tiles:
            xw, xe, ln, ls = tile_box(z, i, j)
            for r, c, _ in gibs_needed(s, LON0 + xw, LON0 + xe, ln, ls):
                gjobs[(s, r, c)] = (fetch, GIBS.format(s=s, r=r, c=c), gibs_path(s, r, c))
    print("gibs tiles:", len(gjobs), flush=True)
    run_pool(list(gjobs.values()), label="gibs")

    # downloads: heights
    t_glob, t_feat = Terrarium(4), Terrarium(7)
    tjobs = {}
    for i, j in level4:
        xw, xe, ln, ls = tile_box(4, i, j)
        for x, y in t_glob.needed(LON0 + xw, LON0 + xe, ln, ls):
            tjobs[(4, x, y)] = (fetch, TERRA.format(z=4, x=x, y=y), t_glob.path(x, y))
    for z in (5, 6):
        for i, j in feat[z]:
            xw, xe, ln, ls = tile_box(z, i, j)
            for x, y in t_feat.needed(LON0 + xw, LON0 + xe, ln, ls):
                tjobs[(7, x, y)] = (fetch, TERRA.format(z=7, x=x, y=y), t_feat.path(x, y))
    print("terrain tiles:", len(tjobs), flush=True)
    run_pool(list(tjobs.values()), label="terra")

    # level 4 and the featured levels from GIBS
    images = {}

    def emit(z, i, j, im, h):
        d = os.path.join(OUT, "tiles", str(z))
        os.makedirs(d, exist_ok=True)
        im.save(os.path.join(d, f"{i}_{j}.jpg"), "JPEG", quality=QUALITY, optimize=True, progressive=False)
        with open(os.path.join(d, f"{i}_{j}.h16"), "wb") as f:
            f.write(h.astype("<i2").tobytes())

    heights4 = {}
    for n, (i, j) in enumerate(level4):
        im = render_tile(4, i, j, 5)
        images[(4, i, j)] = im
        h = grid_heights(t_glob, *tile_box(4, i, j))
        heights4[(i, j)] = h
        emit(4, i, j, im, h)
        if n % 64 == 0:
            print("  level 4", n, flush=True)
    for z, s in ((5, 6), (6, 7)):
        for i, j in feat[z]:
            emit(z, i, j, render_tile(z, i, j, s), grid_heights(t_feat, *tile_box(z, i, j)))
        print("  level", z, len(feat[z]), flush=True)

    # global height grid at the level-4 vertices
    G = np.zeros((16 * 32 + 1, 32 * 32 + 1), dtype=np.int16)
    for (i, j), h in heights4.items():
        G[j * 32 : j * 32 + 33, i * 32 : i * 32 + 33] = h
    with open(os.path.join(OUT, "elev.h16"), "wb") as f:
        f.write(struct.pack("<ii", G.shape[1], G.shape[0]))
        f.write(G.astype("<i2").tobytes())

    # levels 3..0: box-downsampled images, heights taken every other vertex of the children
    hz = {(i, j): heights4[(i, j)] for (i, j) in level4}
    for z in range(3, -1, -1):
        nh = {}
        for i in range(2 ** (z + 1)):
            for j in range(2 ** z):
                big = Image.new("RGB", (2 * PX, 2 * PX))
                hb = np.zeros((65, 65), dtype=np.int16)
                for di in (0, 1):
                    for dj in (0, 1):
                        big.paste(images[(z + 1, 2 * i + di, 2 * j + dj)], (di * PX, dj * PX))
                        hb[dj * 32 : dj * 32 + 33, di * 32 : di * 32 + 33] = hz[(2 * i + di, 2 * j + dj)]
                im = big.resize((PX, PX), Image.LANCZOS)
                images[(z, i, j)] = im
                h = hb[::2, ::2]
                nh[(i, j)] = h
                emit(z, i, j, im, h)
        hz = nh
        print("  level", z, flush=True)

    feat_map = {k: [(v[0] - LON0) % 360.0, (v[1] - LON0) % 360.0, v[2], v[3]] for k, v in FEATURED.items()}
    index = {
        "lon0": LON0, "latTop": LAT_TOP, "latSpan": LAT_SPAN, "px": PX, "grid": GRID, "globalMax": GLOBAL_MAX,
        "featured": feat_map,
        "extra": {str(z): [f"{i}_{j}" for i, j in feat[z]] for z in (5, 6)},
        "sources": "Imagery: NASA Blue Marble Next Generation (Shaded Relief + Bathymetry) via NASA GIBS. Heights: AWS Terrain Tiles (ETOPO1, SRTM, GMTED).",
    }
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    total = sum(os.path.getsize(os.path.join(dp, fn)) for dp, _, fns in os.walk(os.path.join(OUT, "tiles")) for fn in fns)
    print(f"done: {total / 1e6:.1f} MB", flush=True)


if __name__ == "__main__":
    sys.exit(main())
