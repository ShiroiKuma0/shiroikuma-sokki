#!/usr/bin/env python3
"""Icon-variant generator for 白い熊 速記.

One stroke model -> two outputs:
  * a supersampled PIL preview (what the launcher shows),
  * an Android VectorDrawable path string (the shipped icon).

Coordinates are the adaptive-icon viewport: 108 x 108, safe content inside the
central 66 x 66 (launchers mask down to a ~72 dp circle).
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

VP = 108.0                      # adaptive-icon viewport
SS = 10                         # supersample factor for previews

INK_WARM = (255, 236, 58)       # sampled from the Ink&Paper reference
INK_BRAND = (255, 255, 0)       # our fork yellow

RULES = {
    "blue":       (0, 0, 254),      # the template's own pure blue
    "blue_bright": (46, 78, 255),   # lifted for legibility at launcher size
    "blue_dim":   (0, 0, 150),
    "amber":      (150, 120, 26),
    "yellow_dim": (120, 110, 24),
    "grey":       (78, 78, 84),
}
BLACK = (0, 0, 0)

# --- steno ruling geometry, measured from "速記 samsung notes template.png" ---
# Per period P: a heavy line at 0, hairlines at 0.39 P and 0.766 P, next heavy at P.
STENO_OFFSETS = (0.0, 25.0 / 64.0, 49.0 / 64.0)


# ---------------------------------------------------------------- spline ----
def catmull_rom(pts, samples_per_seg=28):
    pts = list(pts)
    head = (2 * pts[0][0] - pts[1][0], 2 * pts[0][1] - pts[1][1])
    tail = (2 * pts[-1][0] - pts[-2][0], 2 * pts[-1][1] - pts[-2][1])
    p = [head] + pts + [tail]
    out = []
    for i in range(len(p) - 3):
        p0, p1, p2, p3 = p[i], p[i + 1], p[i + 2], p[i + 3]
        for s in range(samples_per_seg):
            t = s / samples_per_seg
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t +
                       (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 +
                       (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t +
                       (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 +
                       (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    out.append(pts[-1])
    return out


def arclen_params(pts):
    d = [0.0]
    for a, b in zip(pts, pts[1:]):
        d.append(d[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    total = d[-1] or 1.0
    return [v / total for v in d]


# ------------------------------------------------------------ leg shapes ----
def leg(p0, p1, bow=0.0, wiggle=0.0, wphase=0.0, curl=0.0, n=13):
    """A hand-drawn stroke from p0 to p1.

    bow    - perpendicular belly at mid-stroke (a hand never rules straight)
    wiggle - low-frequency tremor along the stroke
    curl   - the hand carrying on past the end, applied as a *progressive* bend
             over the last third. A kink at the tip would read as a nub once the
             round cap lands on it; a curl reads as a pen lifting off.
    """
    x0, y0 = p0
    x1, y1 = p1
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    nx, ny = -uy, ux
    pts = []
    for i in range(n):
        t = i / (n - 1)
        tail = max(0.0, (t - 0.66) / 0.34) ** 2
        off = (bow * math.sin(math.pi * t)
               + wiggle * math.sin(math.pi * (2.3 * t + wphase))
               + curl * tail)
        pts.append((x0 + dx * t + nx * off, y0 + dy * t + ny * off))
    return pts


def X_natural(scale=0.66, bow=2.2, fl=1.0):
    """How a right hand actually draws an X: down-right first, then down-left.
    The crossing sits a little above centre and the legs bow apart."""
    c = 54.0

    def s(p):
        return (c + (p[0] - c) * scale, c + (p[1] - c) * scale)

    a = leg(s((25.0, 24.0)), s((84.0, 83.0)), bow=bow, wiggle=0.7, wphase=0.2, curl=5.0 * fl)
    b = leg(s((84.5, 25.5)), s((24.0, 82.0)), bow=-bow * 0.85, wiggle=0.6, wphase=0.7, curl=-4.2 * fl)
    return [a, b]


def X_open(scale=0.70, fl=1.0):
    """Wider stance, shallower bows - reads more clearly when masked small."""
    c = 54.0

    def s(p):
        return (c + (p[0] - c) * scale, c + (p[1] - c) * scale)

    a = leg(s((23.0, 26.0)), s((85.0, 81.0)), bow=1.4, wiggle=0.5, wphase=0.1, curl=3.2 * fl)
    b = leg(s((85.0, 27.0)), s((23.0, 82.0)), bow=-1.2, wiggle=0.5, wphase=0.6, curl=-2.8 * fl)
    return [a, b]


def X_loose(scale=0.64, fl=1.0):
    """Fast, sloppy, very obviously by hand: strong bows, big overshoot."""
    c = 54.0

    def s(p):
        return (c + (p[0] - c) * scale, c + (p[1] - c) * scale)

    a = leg(s((26.0, 22.5)), s((83.0, 85.0)), bow=2.7, wiggle=1.0, wphase=0.15, curl=6.0 * fl)
    b = leg(s((85.5, 24.0)), s((22.5, 80.0)), bow=-2.2, wiggle=0.9, wphase=0.55, curl=-5.0 * fl)
    return [a, b]


# ----------------------------------------------------------------- nibs -----
def w_pen(t, tang, wmax):
    """Gel pen: near-uniform, gentle pressure swell, quick taper at the ends."""
    body = 0.82 + 0.18 * math.sin(math.pi * min(max(t, 0.0), 1.0))
    entry = min(1.0, t / 0.09) ** 0.5
    exit_ = min(1.0, (1.0 - t) / 0.13) ** 0.45
    return wmax * body * (0.42 + 0.58 * entry * exit_)


def w_calli(t, tang, wmax, nib=math.radians(10)):
    """Broad nib at a shallow angle: both legs keep weight, with mild contrast."""
    ang = math.atan2(tang[1], tang[0])
    thin = abs(math.sin(ang - nib))
    w = wmax * (0.34 + 0.66 * thin)
    taper = min(1.0, t / 0.06, (1.0 - t) / 0.10) ** 0.45
    return w * (0.40 + 0.60 * taper)


def w_brush(t, tang, wmax):
    """Brush: points at both ends, heavy through the middle."""
    s = math.sin(math.pi * min(max(t, 0.0), 1.0)) ** 0.55
    return wmax * (0.08 + 0.92 * s)


def w_marker(t, tang, wmax):
    """Felt marker: full width almost throughout, blunt ends."""
    taper = min(1.0, t / 0.05, (1.0 - t) / 0.07) ** 0.35
    return wmax * (0.72 + 0.28 * taper)


NIBS = {"pen": w_pen, "calli": w_calli, "brush": w_brush, "marker": w_marker}


# --------------------------------------------------------------- outline ----
def stroke_geometry(centre, nib, wmax):
    """Unit tangents and the nib width at every sample of a centreline.

    Split out from [outline_from] so a partially drawn stroke can be cut from the
    FULL stroke's widths: re-deriving the profile over a prefix would make the
    loader's pen swell and shrink as it wrote, instead of laying down the width
    the finished stroke actually has there.
    """
    ts = arclen_params(centre)
    tangs = []
    for i in range(len(centre)):
        a = centre[max(0, i - 1)]
        b = centre[min(len(centre) - 1, i + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        m = math.hypot(dx, dy) or 1.0
        tangs.append((dx / m, dy / m))
    ws = [max(nib(t, tg, wmax), 0.05) for t, tg in zip(ts, tangs)]
    return ts, tangs, ws


def outline_from(centre, tangs, ws):
    left, right = [], []
    for (x, y), (tx, ty), w in zip(centre, tangs, ws):
        nx, ny = -ty, tx
        left.append((x + nx * w / 2, y + ny * w / 2))
        right.append((x - nx * w / 2, y - ny * w / 2))

    # Round caps. The outline runs left[0..n], end cap, right[n..0], start cap.
    # Each cap therefore sweeps 180 degrees *decreasing*: the end cap from the
    # left offset (+90 deg) round the front to the right offset (-90 deg), the
    # start cap from the right offset round the back. Sweeping the other way
    # folds the cap inside out and bites a notch out of the tip.
    K = 12

    def cap(pt, tang, w, start_ang):
        th = math.atan2(tang[1], tang[0]) + start_ang
        return [(pt[0] + math.cos(th - math.pi * k / K) * w / 2,
                 pt[1] + math.sin(th - math.pi * k / K) * w / 2)
                for k in range(1, K)]

    return (left
            + cap(centre[-1], tangs[-1], ws[-1], math.pi / 2)
            + right[::-1]
            + cap(centre[0], tangs[0], ws[0], -math.pi / 2))


def stroke_outline(centre, nib, wmax):
    _, tangs, ws = stroke_geometry(centre, nib, wmax)
    return outline_from(centre, tangs, ws)


def stroke_outline_partial(centre, nib, wmax, frac):
    """The stroke as it stands when the pen has travelled [frac] of its length."""
    ts, tangs, ws = stroke_geometry(centre, nib, wmax)
    if frac >= 1.0:
        return outline_from(centre, tangs, ws)
    k = max(2, sum(1 for t in ts if t <= frac))
    return outline_from(centre[:k], tangs[:k], ws[:k])


# --------------------------------------------------------------- render -----
def steno_lines(period, phase, heavy, hair):
    out = []
    y0 = phase - period * 2
    while y0 < VP + period:
        for i, off in enumerate(STENO_OFFSETS):
            y = y0 + off * period
            if -2 <= y <= VP + 2:
                out.append((y, heavy if i == 0 else hair, i == 0))
        y0 += period
    return out


def rdp(pts, eps):
    """Ramer-Douglas-Peucker: keep the outline vector-drawable-sized."""
    if len(pts) < 3:
        return list(pts)
    (x0, y0), (x1, y1) = pts[0], pts[-1]
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy)
    best, bi = -1.0, 0
    for i in range(1, len(pts) - 1):
        px_, py_ = pts[i]
        d = (abs(dy * px_ - dx * py_ + x1 * y0 - y1 * x0) / n if n
             else math.hypot(px_ - x0, py_ - y0))
        if d > best:
            best, bi = d, i
    if best <= eps:
        return [pts[0], pts[-1]]
    return rdp(pts[:bi + 1], eps)[:-1] + rdp(pts[bi:], eps)


def leg_paths(v, eps=0.09):
    """The fillable outline of each leg, as VectorDrawable path data."""
    out = []
    for i, pts in enumerate(v["legs"]):
        centre = catmull_rom(pts)
        wmax = v["wmax"] * (v.get("leg2", 1.0) if i else 1.0)
        poly = rdp(stroke_outline(centre, NIBS[v["nib"]], wmax), eps)
        d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly) + " Z"
        out.append(d)
    return out


def hexc(c):
    return "#%02X%02X%02X" % c


def svg_for(v, px):
    """The variant as SVG - the same geometry and the same nonzero winding the
    shipped VectorDrawable uses, so a preview cannot flatter the real icon."""
    rule = RULES[v["rule"]]
    faint = tuple(int(c * v.get("hair_dim", 0.55)) for c in rule)
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" '
             f'viewBox="0 0 {VP:.0f} {VP:.0f}">',
             f'<rect width="{VP:.0f}" height="{VP:.0f}" fill="{hexc(BLACK)}"/>']
    for y, th, heavy in steno_lines(v["period"], v["phase"], v["heavy"], v["hair"]):
        parts.append(f'<rect x="0" y="{y - th / 2:.2f}" width="{VP:.0f}" height="{th:.2f}" '
                     f'fill="{hexc(rule if heavy else faint)}"/>')
    for d in leg_paths(v):
        parts.append(f'<path d="{d}" fill="{hexc(v["ink"])}" fill-rule="nonzero"/>')
    parts.append("</svg>")
    return "\n".join(parts)


_CACHE = {}


def render(v, px):
    key = (id(v), px)
    if key in _CACHE:
        return _CACHE[key].copy()
    import subprocess, tempfile, os
    with tempfile.TemporaryDirectory() as td:
        sp, op = os.path.join(td, "a.svg"), os.path.join(td, "a.png")
        with open(sp, "w") as fh:
            fh.write(svg_for(v, px))
        subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), sp, "-o", op],
                       check=True)
        img = Image.open(op).convert("RGB")
    _CACHE[key] = img
    return img.copy()


def _mask(px, kind):
    m = Image.new("L", (px * 4, px * 4), 0)
    md = ImageDraw.Draw(m)
    if kind == "circle":
        md.ellipse([0, 0, px * 4 - 1, px * 4 - 1], fill=255)
    else:
        n, R = 4.0, px * 4 / 2
        for yy in range(px * 4):
            dy = abs(yy - R + 0.5) / R
            val = 1 - dy ** n
            if val <= 0:
                continue
            dx = val ** (1 / n) * R
            md.line([(R - dx, yy), (R + dx, yy)], fill=255)
    return m.resize((px, px), Image.LANCZOS)


# Android shows only the central 72 dp of the 108 dp layer; the outer 18 dp on
# each side is bleed for parallax and is normally never seen. Every masked
# preview therefore crops to that window before masking - otherwise the icon
# looks roomier here than it ever does on a launcher.
VISIBLE = 72.0


def masked(v, px, kind):
    full = render(v, int(round(px * VP / VISIBLE)))
    f = full.size[0]
    inset = int(round(f * (VP - VISIBLE) / (2 * VP)))
    crop = full.crop((inset, inset, f - inset, f - inset)).resize((px, px), Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(crop, (0, 0), _mask(px, kind))
    return out


def with_safe_guides(img, v):
    """The full 108 layer with the 72 dp mask window and the safe circle drawn on."""
    px = img.size[0]
    s = px / VP
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle([18 * s, 18 * s, 90 * s - 1, 90 * s - 1], outline=(255, 255, 255, 90))
    d.ellipse([18 * s, 18 * s, 90 * s - 1, 90 * s - 1], outline=(255, 90, 90, 130))
    return img


# ------------------------------------------------------------ sheet -------
def font(sz, bold=False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    for p in ("/usr/share/fonts/truetype/dejavu/", "/usr/share/fonts/TTF/"):
        try:
            return ImageFont.truetype(p + name, sz)
        except OSError:
            continue
    return ImageFont.load_default()


def sheet(variants, path, title, subtitle="", cols=3):
    BIG, MID, SM, GAP = 208, 92, 48, 16
    cellw = BIG + GAP + MID + 38
    cellh = BIG + 88
    W = cols * cellw + 30
    rows = (len(variants) + cols - 1) // cols
    HEAD = 62 + 19 * (subtitle.count(chr(10)) + 1)
    H = rows * cellh + HEAD + 16
    sh = Image.new("RGB", (W, H), (24, 24, 26))
    d = ImageDraw.Draw(sh)
    fb, fs, ft, fx = font(21, True), font(14), font(15, True), font(11)
    d.text((26, 22), title, fill=(242, 242, 242), font=fb)
    if subtitle:
        for j, line in enumerate(subtitle.split("\n")):
            d.text((26, 52 + j * 19), line, fill=(155, 155, 162), font=fs)

    for i, (key, label, v) in enumerate(variants):
        r, c = divmod(i, cols)
        x, y = 26 + c * cellw, HEAD + 8 + r * cellh
        big = masked(v, BIG, "squircle")
        sh.paste(big, (x, y), big)
        cir = masked(v, MID, "circle")
        sh.paste(cir, (x + BIG + GAP, y), cir)
        sm = masked(v, SM, "circle")
        sh.paste(sm, (x + BIG + GAP + (MID - SM) // 2, y + MID + 10), sm)
        sh.paste(with_safe_guides(render(v, MID), v), (x + BIG + GAP, y + MID + SM + 26))
        d.text((x, y + BIG + 10), key, fill=(255, 220, 60), font=ft)
        d.text((x + 22, y + BIG + 10), label, fill=(226, 226, 230), font=fs)
        d.text((x + BIG + GAP, y + MID + SM + 26 + MID + 6), "full 108 layer",
               fill=(120, 120, 126), font=fx)
        d.text((x + BIG + GAP, y + MID + 4), "48 px", fill=(120, 120, 126), font=fx)
    sh.save(path)
    return path


# ------------------------------------------------------------- variants -----
def V(**kw):
    base = dict(period=40.0, phase=23.0, heavy=2.6, hair=1.0, hair_dim=0.55,
                rule="blue", ink=INK_WARM, nib="pen", wmax=7.2,
                leg2=1.0, legs=X_natural())
    base.update(kw)
    return base


STYLE_SHEET = [
    ("A", "gel pen — even weight",
     V(nib="pen", wmax=7.4, legs=X_natural())),
    ("B", "calligraphic — mild contrast",
     V(nib="calli", wmax=10.6, legs=X_natural())),
    ("C", "brush — tapered ends",
     V(nib="brush", wmax=9.6, legs=X_natural())),
    ("D", "felt marker — blunt, bold",
     V(nib="marker", wmax=8.2, legs=X_open())),
    ("E", "fast & loose — big overshoot",
     V(nib="pen", wmax=7.0, legs=X_loose())),
    ("F", "brush, dominant first stroke",
     V(nib="brush", wmax=10.6, leg2=0.68, legs=X_natural(bow=2.8))),
]

RULE_SHEET = [
    ("1", "blue — the template's own #0000FE", V(rule="blue")),
    ("2", "blue, lifted for small sizes", V(rule="blue_bright")),
    ("3", "blue, dimmed back", V(rule="blue_dim")),
    ("4", "amber ruling", V(rule="amber")),
    ("5", "olive-yellow ruling", V(rule="yellow_dim")),
    ("6", "grey ruling (neutral paper)", V(rule="grey")),
]

DENSITY_SHEET = [
    ("i", "one band — calmest", V(period=76.0, phase=30.0)),
    ("ii", "two bands (as shown above)", V(period=40.0, phase=23.0)),
    ("iii", "three bands — busiest", V(period=28.0, phase=17.0)),
    ("iv", "ink: our #FFFF00 vs the reference", V(ink=INK_BRAND)),
]

OUT = os.path.expanduser("~/tmp/")  # previews always land where 白い熊 can open them

if __name__ == "__main__":
    sheet(STYLE_SHEET, OUT + "sokki-icon-1-style.png",
          "白い熊 速記 — 1. the X: six hand-drawn treatments",
          "Identical blue steno ruling throughout, so only the pen changes.\n"
          "Each cell: squircle mask · circle mask · true 48 px launcher size · the full 108 dp layer\n"
          "(white square = the 72 dp Android actually shows, red circle = the round-mask edge).")
    sheet(RULE_SHEET, OUT + "sokki-icon-2-rules.png",
          "白い熊 速記 — 2. the paper: blue vs yellow ruling",
          "Same gel-pen X throughout, so only the ruling changes. The choice carries over\n"
          "to whichever X you pick from sheet 1.")
    sheet(DENSITY_SHEET, OUT + "sokki-icon-3-density.png",
          "白い熊 速記 — 3. how much paper, and which yellow",
          "Ruling density (i–iii), then iv: our brand #FFFF00 against the warmer #FFEC3A\n"
          "sampled from your Ink&Paper screenshot, which every other cell uses.",
          cols=4)
    print("sheets written")
