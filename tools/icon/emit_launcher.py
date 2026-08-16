#!/usr/bin/env python3
"""Cut the shipped launcher assets for 白い熊 速記 from the chosen icon variant.

The variant is 白い熊's pick off the preview sheets: F (brush, dominant first
stroke) + 2 (lifted blue ruling) + iii (three bands), inked in the fork yellow #FFFF00.

Writes, into app/src/main/res:
  drawable/ic_launcher_sk_background.xml   black sheet + the 速記 ruling
  drawable/ic_launcher_sk_foreground.xml   the yellow X
  drawable/ic_launcher_sk_monochrome.xml   the X alone, for Material You
  mipmap-*/ic_launcher{,_round,_foreground}.png   the legacy set

The themed icon is the vector alone: upstream dropped its per-density monochrome PNGs in
v0.8.12, and ours were dead too - the adaptive XML has always pointed <monochrome> at the
drawable.

Re-runnable: same input, same bytes out.
"""
import importlib.util
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("gen", os.path.join(HERE, "gen.py"))
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

REPO = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(REPO, "app/src/main/res")

CHOSEN = gen.V(
    nib="brush", wmax=10.6, leg2=0.68, legs=gen.X_natural(bow=2.8),
    rule="blue_bright", period=28.0, phase=17.0, ink=gen.INK_BRAND,
)

INK = gen.hexc(gen.INK_BRAND)
RULE = gen.hexc(gen.RULES["blue_bright"])
HAIR = gen.hexc(tuple(int(c * 0.55) for c in gen.RULES["blue_bright"]))

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
VECTOR_OPEN = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    android:width="108dp"\n'
               '    android:height="108dp"\n'
               '    android:viewportWidth="108"\n'
               '    android:viewportHeight="108">\n')


def x_paths(color):
    return "".join(
        f'  <path\n      android:pathData="{d}"\n      android:fillColor="{color}"/>\n'
        for d in gen.leg_paths(CHOSEN)
    )


def write(path, text):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh:
        fh.write(text)
    print("wrote", path)


# ------------------------------------------------------------------ vectors --
rules = []
for y, th, heavy in gen.steno_lines(CHOSEN["period"], CHOSEN["phase"],
                                    CHOSEN["heavy"], CHOSEN["hair"]):
    top, bot = y - th / 2, y + th / 2
    rules.append(
        f'  <path\n'
        f'      android:pathData="M0,{top:.2f}h108v{th:.2f}h-108z"\n'
        f'      android:fillColor="{RULE if heavy else HAIR}"/>\n'
    )

write("drawable/ic_launcher_sk_background.xml", HEADER + f"""<!-- shiroikuma-sokki fork: the 速記 paper the 白い熊 速記 mark is written on.

     The adaptive icon's background layer. Ruling geometry is the Samsung Notes 速記 template
     measured line for line - a heavy rule opening each band, hairlines at 25/64 and 49/64 of it -
     at three bands across the icon, which is what survives being shrunk to a launcher tile.
     The lines run the full 108 dp so they still reach the rim after any mask crops to the
     middle 72 dp; the blue is lifted off the template's own #0000FE, which goes muddy that small.

     Kept deliberately in step with PagePattern.SOKKI (core/model/PageStyle.kt): the icon is a
     picture of the paper the app now writes on. -->
""" + VECTOR_OPEN + f"""  <path
      android:pathData="M0,0h108v108h-108z"
      android:fillColor="@color/ic_launcher_background"/>
{"".join(rules)}</vector>
""")

write("drawable/ic_launcher_sk_foreground.xml", HEADER + f"""<!-- shiroikuma-sokki fork: the 白い熊 速記 mark.

     An X actually written rather than constructed: two brush strokes, the first one dominant, both
     bowed the way a hand bows them, crossing a little above centre and carrying on past the end.
     Filled outlines, not a stroked path, because the width has to vary along each leg - the
     centrelines and the nib that swelled them are in tools/icon/gen.py.

     Sized to sit inside the 72 dp a launcher mask leaves, with room to spare at the rim. -->
""" + VECTOR_OPEN + x_paths(INK) + "</vector>\n")

write("drawable/ic_launcher_sk_monochrome.xml", HEADER + """<!-- shiroikuma-sokki fork: the 白い熊 速記 mark for Material You themed icons (Android 13+).

     The same two strokes as the foreground, and the ruling deliberately dropped: the system
     flattens this layer to one tint, which would fuse paper and ink into a striped tile with an X
     lost in it. The mark alone is what stays recognisable. -->
""" + VECTOR_OPEN + x_paths("#FF000000") + "</vector>\n")

# ------------------------------------------------------------------ rasters --
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def ink_only(px, color):
    """The X on transparency, in the full 108 dp layer - the foreground PNGs."""
    body = "".join(
        f'<path d="{d}" fill="{color}" fill-rule="nonzero"/>' for d in gen.leg_paths(CHOSEN))
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{px}" height="{px}" '
           f'viewBox="0 0 108 108">{body}</svg>')
    import subprocess, tempfile
    with tempfile.TemporaryDirectory() as td:
        sp, op = os.path.join(td, "a.svg"), os.path.join(td, "a.png")
        open(sp, "w").write(svg)
        subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), sp, "-o", op], check=True)
        return Image.open(op).convert("RGBA")


for density, factor in DENSITIES.items():
    legacy = int(48 * factor)      # pre-API-26 launcher icon
    layer = int(108 * factor)      # a full adaptive layer at this density
    for name, kind in (("ic_launcher", "squircle"), ("ic_launcher_round", "circle")):
        gen.masked(CHOSEN, legacy, kind).save(os.path.join(RES, f"mipmap-{density}/{name}.png"))
    ink_only(layer, INK).save(os.path.join(RES, f"mipmap-{density}/ic_launcher_foreground.png"))
    print(f"wrote mipmap-{density}/ (legacy {legacy}px, layers {layer}px)")

print(f"\nink {INK}   rule {RULE}   hairline {HAIR}")
