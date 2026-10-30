"""Static copy-width check (profile D13): measures every single-line panel string, in English and Chinese,
with the bundled Noto fonts against the width its box leaves for text. Sizes are the SpatialUI type scale as
measured on the emulator (approximate, 1 sp = 1 dp); anything over 90 % of its box is listed as tight.

The on-device check is the `CLIP panel=` log line (FitText.kt); this one runs without an emulator.

    python -I tools/check_text_width.py
"""
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

from PIL import ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FONT = {
    400: RES / "font/noto_sans_regular.ttf",
    500: RES / "font/noto_sans_medium.ttf",
    600: RES / "font/noto_sans_semibold.ttf",
}
CJK = RES / "font/noto_sans_sc_subset.ttf"
SCALE = 4  # measure at 4x and divide, for sub-pixel accuracy

# type scale (sp) and weight per role used in the panels
# calibrated on the round-1 HD screenshots (board 600 dp ~ 620 px): key labels ~14 sp, board title ~20 sp
LABEL, HINT, TITLE_M, TITLE_L, HEAD_S, HEAD_M = (14, 500), (14, 500), (16, 500), (20, 500), (24, 400), (28, 400)
KEY = (14, 600)


def width(text, size, weight):
    latin = ImageFont.truetype(str(FONT[weight]), size * SCALE)
    cjk = ImageFont.truetype(str(CJK), size * SCALE)
    total = 0.0
    for ch in text:
        f = cjk if ord(ch) > 0x2E7F else latin
        total += f.getlength(ch)
    return total / SCALE


def strings(lang):
    path = RES / ("values/strings.xml" if lang == "en" else "values-zh/strings.xml")
    return {e.get("name"): (e.text or "").replace("\\'", "'") for e in ET.parse(path).getroot().iter("string")}


def fill(s, *args):
    for i, a in enumerate(args, 1):
        s = re.sub(r"%%%d\$[sd]" % i, str(a), s)
    return s.replace("%%", "%")


def main():
    data = json.loads((ROOT / "app/src/main/assets/atlas/sets.json").read_text(encoding="utf-8"))
    rows = []
    for lang in ("en", "zh"):
        S = strings(lang)
        zh = lang == "zh"
        names = [t["zh" if zh else "en"] for s in data["sets"] for t in s["targets"]]
        other = [t["en" if zh else "zh"] for s in data["sets"] for t in s["targets"]]
        landforms = data.get("landforms", [])
        set_names = [s["zh" if zh else "en"] for s in data["sets"]]
        longest = max(names, key=lambda n: width(n, *TITLE_L))
        checks = [
            # (where, text, style, available dp)
            ("menu answer option (2 lines allowed)", S["answer_drag"], LABEL, 2 * 140),
            ("menu answer option (2 lines allowed)", S["answer_point"], LABEL, 2 * 140),
            ("menu floor row", S["menu_floor"], LABEL, 336 - 2 * 52 - 60 - 24),
            ("menu lang segment", S["lang_zh"], LABEL, 93),
            ("menu recenter key", S["recenter"], KEY, 336 - 8 - 96 - 30 - 32),
            ("menu exit key", S["menu_exit"], KEY, 200),
            ("board map key (x4)", S["rotate_left"], KEY, 95),
            ("board map key (x4)", S["rotate_right"], KEY, 95),
            ("board map key (x4)", S["zoom_out"], KEY, 95),
            ("board map key (x4)", S["zoom_in"], KEY, 95),
            ("board recenter key", S["recenter"], KEY, 200),
            ("board title", S["app_name"], TITLE_L, 360),
            ("board step line", fill(S["step_line"], "8,700", fill(S["detail_region"], "Himalaya"), "98"), LABEL, 552),
            ("board ready title", fill(S["ready_title"], 6, 15), HEAD_S, 552),
            ("board ready zoom", fill(S["ready_zoom"], "4.0"), HINT, 552),
            ("board paused line", fill(S["paused_line"], fill(S["ask_progress"], 15, 15), "12:34"), HEAD_S, 552),
            ("board measure hint", S["measure_hint"], HINT, 552),
            ("board measure map", fill(S["measure_map"], "12.34", "16.5"), LABEL, 552),
            ("board new best badge", S["new_best"], LABEL, 160),
            ("board set row title", max((fill(S["set_level"], 6, n) for n in set_names), key=lambda t: width(t, *TITLE_M)), TITLE_M, 380),
            ("board asking name", longest, HEAD_M, 552),
            ("tag: go to (2 lines)", f'{S["ask_go"]} {longest}', TITLE_L, 2 * 300),
            ("tag: wrong (2 lines)", fill(S["wrong_here"], longest), TITLE_L, 2 * 300),
            ("tag: reveal, arrow shown (2 lines)", fill(S["reveal"], longest), TITLE_L, 2 * 256),
            ("tag: off map (2 lines)", S["off_map"], TITLE_L, 2 * 300),
            ("tag: paused (2 lines)", S["tag_paused"], TITLE_L, 2 * 300),
            ("tag: nowhere hint (2 lines)", S["nowhere"], HINT, 2 * 200),
            ("snack: floor", S["snack_floor"], TITLE_M, 444),
            ("snack: lost", S["snack_lost"], TITLE_M, 444),
            ("snack: max", S["snack_max"], TITLE_M, 444),
            ("name label title", max(names, key=lambda n: width(n, *TITLE_M)), TITLE_M, 258),
            ("name label subtitle", max(other, key=lambda n: width(n, *LABEL)), LABEL, 258),
        ]
        for lf in landforms:
            title = lf["zh" if zh else "en"] + (f' {lf["h"]:,} m' if lf.get("h") else "")
            checks.append(("landform plate title", title, TITLE_M, 252))
        for where, text, (size, weight), avail in checks:
            w = width(text, size, weight)
            rows.append((lang, where, text, w, avail))
    bad = 0
    for lang, where, text, w, avail in rows:
        ratio = w / avail
        flag = "CUT " if ratio > 1 else ("TIGHT" if ratio > 0.9 else "")
        if flag:
            bad += ratio > 1
            print(f"{flag:5} {lang} {where:36} {w:6.0f}/{avail:<4} {text}")
    print(f"{len(rows)} strings checked, {bad} over their box")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
