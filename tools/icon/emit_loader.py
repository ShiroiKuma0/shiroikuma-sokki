#!/usr/bin/env python3
"""Re-cut the 24 launch-animation frames for 白い熊 速記 from the icon's brush X.

The old frames traced the outline of the geometric X. These *write* the mark
instead — the same two brush strokes as the launcher icon, laid down one after
the other, held, then faded so the loop cuts cleanly. For a handwriting app the
splash showing a pen writing is the honest animation.

The 速記 ruling is under it, at the same period and phase the icon uses, so the
splash is the launcher tile enlarged. The frames are a square on a fullscreen
black stage, so each rule fades out towards the left and right ends rather than
stopping dead at the frame edge — otherwise the paper reads as a floating patch
with two visible seams. The paper does NOT fade with the ink at the end of the
loop: it stays put while the writing appears and dims, which is what makes the
wrap seamless and what makes it read as paper rather than as part of the mark.

    frames  0-7   stroke one (the dominant one) is written
    frames  8-14  stroke two crosses it
    frames 15-20  held, fully written
    frames 21-23  faded down, so frame 0 is not a hard cut

Writes app/src/main/res/drawable-nodpi/xnotes_frame_NN.png, 540x540 opaque black,
matching the frame size and format the existing AnimationDrawable expects.
"""
import importlib.util
import math
import os
import subprocess
import sys
import tempfile

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("gen", os.path.join(HERE, "gen.py"))
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

REPO = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(REPO, "app/src/main/res")
OUT = os.path.join(RES, "drawable-nodpi")
SIZE = 540
FRAMES = 24
INK = gen.INK_BRAND

# The launcher icon's X exactly — the loader is the same mark, just framed tighter.
X = gen.V(nib="brush", wmax=10.6, leg2=0.68, legs=gen.X_natural(bow=2.8), ink=INK,
          rule="blue_bright", period=28.0, phase=17.0)

RULE = gen.RULES[X["rule"]]
HAIR = tuple(int(c * X["hair_dim"]) for c in RULE)
FADE_IN = 0.20      # fraction of the frame width over which a rule reaches full strength

DRAW1 = (0, 8)      # frames over which stroke one is written
DRAW2 = (8, 15)     # ... and stroke two crosses it
HOLD = 21           # written and held until here
FADE = [0.60, 0.34, 0.14]   # then dimmed, so the loop's wrap is not a hard cut


def ease(t):
    """Slow at the start, quick through the middle, easing off — how a hand moves."""
    return 0.5 - 0.5 * math.cos(math.pi * max(0.0, min(1.0, t)))


def viewbox():
    """A square box around the finished mark, padded, so it fills the frame."""
    xs, ys = [], []
    for i in range(len(X["legs"])):
        centre = gen.catmull_rom(X["legs"][i])
        wmax = X["wmax"] * (X["leg2"] if i else 1.0)
        for px, py in gen.stroke_outline(centre, gen.NIBS[X["nib"]], wmax):
            xs.append(px)
            ys.append(py)
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    half = max(max(xs) - min(xs), max(ys) - min(ys)) / 2 * 1.14   # 14% air at the rim
    return cx - half, cy - half, half * 2


def frac_for(frame, span):
    start, end = span
    if frame < start:
        return 0.0
    if frame >= end - 1:
        return 1.0
    return ease((frame - start) / float(end - 1 - start))


def dim(color, k):
    return tuple(int(round(c * k)) for c in color)


def ruling(vx, vy, vw):
    """The 速記 rules across the frame, each dissolving into the black at both ends."""
    defs, rects = [], []
    for i, (c, name) in enumerate(((RULE, "heavy"), (HAIR, "hair"))):
        h = gen.hexc(c)
        defs.append(
            f'<linearGradient id="{name}" x1="0" y1="0" x2="1" y2="0">'
            f'<stop offset="0" stop-color="{h}" stop-opacity="0"/>'
            f'<stop offset="{FADE_IN}" stop-color="{h}" stop-opacity="1"/>'
            f'<stop offset="{1 - FADE_IN}" stop-color="{h}" stop-opacity="1"/>'
            f'<stop offset="1" stop-color="{h}" stop-opacity="0"/>'
            f'</linearGradient>')
    for y, th, heavy in gen.steno_lines(X["period"], X["phase"], X["heavy"], X["hair"]):
        if not (vy - th <= y <= vy + vw + th):
            continue
        rects.append(f'<rect x="{vx:.3f}" y="{y - th / 2:.3f}" width="{vw:.3f}" '
                     f'height="{th:.3f}" fill="url(#{"heavy" if heavy else "hair"})"/>')
    return f'<defs>{"".join(defs)}</defs>' + "".join(rects)


def render(frame):
    vx, vy, vw = viewbox()
    k = 1.0
    if frame >= HOLD:
        k = FADE[min(frame - HOLD, len(FADE) - 1)]
    color = gen.hexc(dim(INK, k))

    paths = []
    for i, span in enumerate((DRAW1, DRAW2)):
        f = frac_for(frame, span)
        if f <= 0.0:
            continue
        centre = gen.catmull_rom(X["legs"][i])
        wmax = X["wmax"] * (X["leg2"] if i else 1.0)
        poly = gen.rdp(gen.stroke_outline_partial(centre, gen.NIBS[X["nib"]], wmax, f), 0.06)
        d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly) + " Z"
        paths.append(f'<path d="{d}" fill="{color}" fill-rule="nonzero"/>')

    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" '
           f'viewBox="{vx:.3f} {vy:.3f} {vw:.3f} {vw:.3f}">'
           f'<rect x="{vx:.3f}" y="{vy:.3f}" width="{vw:.3f}" height="{vw:.3f}" fill="#000000"/>'
           f'{ruling(vx, vy, vw)}'
           f'{"".join(paths)}</svg>')

    with tempfile.TemporaryDirectory() as td:
        sp, op = os.path.join(td, "f.svg"), os.path.join(td, "f.png")
        open(sp, "w").write(svg)
        subprocess.run(["rsvg-convert", "-w", str(SIZE), "-h", str(SIZE), sp, "-o", op], check=True)
        # Flatten onto black: the frames are opaque RGB, like the ones they replace.
        img = Image.open(op).convert("RGBA")
        flat = Image.new("RGB", (SIZE, SIZE), (0, 0, 0))
        flat.paste(img, (0, 0), img)
        return flat


if __name__ == "__main__":
    strip = Image.new("RGB", (SIZE // 4 * FRAMES, SIZE // 4), (0, 0, 0))
    for f in range(FRAMES):
        img = render(f)
        img.save(os.path.join(OUT, f"xnotes_frame_{f:02d}.png"))
        strip.paste(img.resize((SIZE // 4, SIZE // 4), Image.LANCZOS), (f * SIZE // 4, 0))
    strip.save(os.path.expanduser("~/tmp/sokki-loader-new.png"))
    print(f"wrote {FRAMES} frames to {OUT}")
    print("contact strip: ~/tmp/sokki-loader-new.png")
