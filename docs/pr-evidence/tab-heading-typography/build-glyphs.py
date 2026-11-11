"""Builds the tab-heading lettering and its specimen.

Not an Android capture. Run from the repository root with fontTools available (a throwaway venv is
fine; fontTools is not a project dependency):

    python3 -m venv /tmp/glyphvenv && /tmp/glyphvenv/bin/pip install fonttools
    /tmp/glyphvenv/bin/python docs/pr-evidence/tab-heading-typography/build-glyphs.py

It writes:

* `src/rn/ui/tabHeadingGlyphs.ts` — the production glyph set, one contour per letter, in the
  wordmark's own coordinate space (y down, baseline 451).
* `heading-lettering-light.png` / `heading-lettering-dark.png` — the rendered specimen.

Contours for a, e, n and t are the approved wordmark's own subpaths, split out of
`src/rn/assets/branding/gaten-wordmark-light.svg` (M/L only) and translated so each starts at x 0.
The letters the wordmark does not carry (A, S, l, y, i, c, s, g) are drawn from Poppins SemiBold
(google/fonts, OFL 1.1) through a TransformPen, scaled so x-height or cap height matches the
wordmark's, and thickened with a compensating SVG stroke so every stem lands on the wordmark's own
92-unit stem. SemiBold is the weight whose stem-to-x-height ratio sits next to the wordmark's; the
stroke closes the remaining gap.
"""
import json
import re
import subprocess
import urllib.request
from pathlib import Path

from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
# Official upstream, OFL 1.1 (`app/src/main/assets/licenses/Poppins-OFL.txt`). Cached outside the
# repository; fetched here when absent so the run is reproducible from a clean checkout.
POPPINS_URL = "https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf"
POPPINS = Path("/tmp/fontcand/Poppins-SemiBold.ttf")


def poppins() -> Path:
    if not POPPINS.is_file():
        POPPINS.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(POPPINS_URL, POPPINS)
    return POPPINS

# ---- the wordmark's own metrics, in its viewBox units (1833 x 472) ----
BASELINE = 451.0      # G bottom 450; a and e overshoot to 455
WORD_XHEIGHT = 321.0  # n top 130 -> 451
WORD_CAP = 435.0      # G top 16 -> 451
WORD_STEM = 92.0      # n left stem, x 1473..1565

# Every glyph is drawn in one shared vertical box so the rows align without per-glyph offsets.
BOX_TOP = -30.0
BOX_BOTTOM = 620.0
GAP = 18.0            # ink-to-ink letter gap, about the wordmark's own t/e spacing

# The heading's cap height and line height in dp at font scale 1, matching the 34 sp role it replaces.
CAP_DP = 24.5
LINE_HEIGHT_DP = 44.0


def fmt(v: float) -> str:
    s = ("%.1f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("", "-", "-0") else s


def round_path(d: str) -> str:
    """Trims SVGPathPen's long decimals: 0.1 unit is 0.006 dp at the headings' scale."""
    return re.sub(r"-?\d*\.?\d+(?:e-?\d+)?", lambda m: fmt(round(float(m.group(0)), 1)), d)


# ---------------------------------------------------------------- wordmark contours
svg = (ROOT / "src/rn/assets/branding/gaten-wordmark-light.svg").read_text()
subs = []
for m in re.finditer(
    r"M\s*([-\d.]+)\s+([-\d.]+)|L\s*([-\d.]+)\s+([-\d.]+)",
    re.search(r'\sd="([^"]+)"', svg).group(1),
):
    if m.group(1) is not None:
        subs.append([])
    subs[-1].append(
        (
            float(m.group(1) if m.group(1) is not None else m.group(3)),
            float(m.group(2) if m.group(2) is not None else m.group(4)),
        )
    )

# Subpaths of the single wordmark path, in file order, with the letter each belongs to. The G and its
# two accent marks are deliberately absent: no title uses them, and the approved G lives only in
# `GatenWordmark`, complete. Subpaths 6 and 7 are those two marks.
WORD_GLYPHS = {"n": [0], "a": [1, 2], "e": [3, 4], "t": [5]}


def word_glyph(name: str):
    idxs = WORD_GLYPHS[name]
    pts = [p for i in idxs for p in subs[i]]
    dx = min(p[0] for p in pts)
    d = ""
    for i in idxs:
        p = subs[i]
        d += "M" + fmt(p[0][0] - dx) + " " + fmt(p[0][1])
        d += "".join("L" + fmt(x - dx) + " " + fmt(y) for x, y in p[1:])
        d += "Z"
    extents = (0.0, max(p[0] for p in pts) - dx, min(p[1] for p in pts), max(p[1] for p in pts))
    return d, max(p[0] for p in pts) - dx, 0.0, extents


# ---------------------------------------------------------------- Poppins for the rest
font = TTFont(poppins())
glyphset = font.getGlyphSet()
cmap = font.getBestCmap()


def bounds(gname: str):
    pen = BoundsPen(glyphset)
    glyphset[gname].draw(pen)
    return pen.bounds


FONT_XHEIGHT = bounds(cmap[ord("x")])[3]
FONT_CAP = bounds(cmap[ord("H")])[3]
# 'l' is a plain stem, so its ink width is the face's stem width at this weight.
FONT_STEM = bounds(cmap[ord("l")])[2] - bounds(cmap[ord("l")])[0]
# Lowercase letters are scaled to the wordmark's x-height, capitals to its cap height.
POP_GLYPHS = {"A": False, "S": False, "l": True, "y": True, "i": True, "c": True, "s": True, "g": True}


def font_glyph(ch: str):
    gname = cmap[ord(ch)]
    s = (WORD_XHEIGHT / FONT_XHEIGHT) if POP_GLYPHS[ch] else (WORD_CAP / FONT_CAP)
    stroke = round(WORD_STEM - FONT_STEM * s, 1)
    x0, y0, x1, y1 = bounds(gname)
    pen = SVGPathPen(glyphset)
    # Font space is y-up with the baseline at 0; the wordmark's is y-down with the baseline at 451.
    # The outline is inset by half the stroke so that the stroked ink starts exactly at x 0.
    glyphset[gname].draw(TransformPen(pen, Transform(s, 0, 0, -s, -s * x0 + stroke / 2, BASELINE)))
    extents = (s * x0 - s * x0 + stroke / 2, s * x1 - s * x0 + stroke / 2,
               BASELINE - s * y1, BASELINE - s * y0)
    return round_path(pen.getCommands()), s * (x1 - x0) + stroke, stroke, extents


GLYPHS = {k: word_glyph(k) for k in WORD_GLYPHS}
GLYPHS.update({k: font_glyph(k) for k in POP_GLYPHS})

# Every glyph's ink, stroke included, must sit inside the shared box: a glyph that overflows would be
# clipped by the Svg's viewBox on device. Extents come from the outlines' bounds, not from the path
# text, which may carry H/V shorthands.
for _ch, (_d, _w, _stroke, (_ex0, _ex1, _ey0, _ey1)) in GLYPHS.items():
    _pad = _stroke / 2
    if (_ex0 - _pad) < -0.01 or (_ex1 + _pad) > _w + 0.01 or (_ey0 - _pad) < BOX_TOP or (_ey1 + _pad) > BOX_BOTTOM:
        raise SystemExit(
            f"{_ch} overflows its box: x [{_ex0 - _pad:.1f}, {_ex1 + _pad:.1f}] w {_w:.1f} "
            f"y [{_ey0 - _pad:.1f}, {_ey1 + _pad:.1f}]"
        )

# ---------------------------------------------------------------- production module
rows = "\n".join(
    f'  {k}: {{d: "{d}", w: {round(w, 1)}, stroke: {st}}},'
    for k, (d, w, st, _e) in sorted(GLYPHS.items(), key=lambda kv: (kv[0].isupper(), kv[0]))
)
module = f'''/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The two tab headings as the approved wordmark's own lettering: one contour per glyph, in the
 * wordmark's coordinate space (y down, baseline {fmt(BASELINE)}, cap {fmt(WORD_CAP)}, x-height {fmt(WORD_XHEIGHT)}).
 *
 * a, e, n and t are the wordmark's own subpaths, split out of
 * `src/rn/assets/branding/gaten-wordmark-light.svg` and translated to start at x 0. The letters the
 * wordmark does not carry are Poppins SemiBold outlines (google/fonts, OFL 1.1 — see NOTICE) scaled to
 * the wordmark's x-height or cap height and given the `stroke` a face of this weight needs to reach the
 * wordmark's {fmt(WORD_STEM)}-unit stem. `w` is the glyph's ink width including that stroke, so a row is
 * laid out by ink and the gap is real whitespace.
 *
 * Generated by docs/pr-evidence/tab-heading-typography/build-glyphs.py; regenerate there rather than
 * editing a contour by hand.
 */
export type TabHeadingGlyph = {{d: string; w: number; stroke: number}};

export const TAB_HEADING_GLYPHS: Record<string, TabHeadingGlyph> = {{
{rows}
}};

/** The shared box every glyph is drawn in, and the heading's own dp metrics at font scale 1. */
export const TAB_HEADING_METRICS = {{
  boxTop: {fmt(BOX_TOP)},
  boxBottom: {fmt(BOX_BOTTOM)},
  cap: {fmt(WORD_CAP)},
  gap: {fmt(GAP)},
  capDp: {fmt(CAP_DP)},
  lineHeightDp: {fmt(LINE_HEIGHT_DP)},
}};
'''
(ROOT / "src/rn/ui/tabHeadingGlyphs.ts").write_text(module)

# ---------------------------------------------------------------- specimen
def line(text: str, x: float, y: float, ink: str):
    d = ""
    for ch in text:
        gd, w, st, _e = GLYPHS[ch]
        attrs = f'fill="{ink}" fill-rule="evenodd"'
        if st:
            attrs += f' stroke="{ink}" stroke-width="{fmt(st)}" stroke-linejoin="round"'
        d += f'<path transform="translate({fmt(x)} {fmt(y)})" {attrs} d="{gd}"/>'
        x += w + GAP
    return d


def reference_inner(dark: bool) -> str:
    """The approved file's own contents — every contour, accents included — for this theme."""
    source = (ROOT / f"src/rn/assets/branding/gaten-wordmark-{'dark' if dark else 'light'}.svg").read_text()
    return re.sub(r"^.*?<svg[^>]*>", "", source, flags=re.S).replace("</svg>", "").strip()


def specimen(dark: bool) -> None:
    page, ink, muted = ("#101114", "#F5F5F6", "#8A8A92") if dark else ("#FDFDFD", "#17171A", "#68686F")
    rows = [
        (f"Approved wordmark — src/rn/assets/branding/gaten-wordmark-{'dark' if dark else 'light'}.svg",
         f'<svg x="{fmt(40)}" y="{fmt(0)}" width="1833" height="472" viewBox="0 0 1833 472">{reference_inner(dark)}</svg>'),
        ("Analytics — TabHeading lettering", line("Analytics", 40, 0, ink)),
        ("Settings — TabHeading lettering", line("Settings", 40, 0, ink)),
    ]
    row_h = 700.0
    height = 60 + row_h * len(rows)
    body = ""
    for i, (caption, content) in enumerate(rows):
        top = 60 + i * row_h
        ty = top - BOX_TOP
        body += f'<text x="40" y="{fmt(top - 26)}" font-family="sans-serif" font-size="22" fill="{muted}">{caption}</text>'
        body += f'<g transform="translate(0 {fmt(ty)})">{content}</g>'
    out = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="2900" height="{fmt(height)}" '
        f'viewBox="0 0 2900 {fmt(height)}"><rect width="2900" height="{fmt(height)}" fill="{page}"/>{body}</svg>'
    )
    stem = "heading-lettering-dark" if dark else "heading-lettering-light"
    (OUT / f"{stem}.svg").write_text("\n".join(row.rstrip() for row in out.splitlines()) + "\n")
    subprocess.run(["rsvg-convert", "-z", "1.4", "-o", str(OUT / f"{stem}.png"), str(OUT / f"{stem}.svg")], check=True)


specimen(False)
specimen(True)
print("wrote src/rn/ui/tabHeadingGlyphs.ts, heading-lettering-light.png, heading-lettering-dark.png")
for k, (d, w, st, _e) in GLYPHS.items():
    print(f"  {k}: w={w:.1f} stroke={st}")
