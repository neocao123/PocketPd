"""PocketPd launcher icon — PC-98/VHS design language (design/pc98-analog).

BACKGROUND layer: the $1 chrome-blue ground (#0000A8) with a BIG run-green
play glyph ($C #54FC54) as backdrop, then the CRT scanline texture (2dp
black @25% every 2dp) over everything, like every screen in the app.
FOREGROUND layer: "PD" in F5.6 letterforms as the sole subject — ink white,
hard black outline, red/cyan chroma ghosts (VHS OSD, mockups 02/03) — and
the 2dp bright-blue underline (#5454FC) beneath the word, the title-bar
signature.

Everything visible stays inside the 66dp adaptive-icon safe circle.
Outputs 432px PNGs (108dp @xxxhdpi) into app/src/main/res/drawable/.
"""
from PIL import Image, ImageDraw, ImageFont
import os

SIZE = 432          # 108dp adaptive icon at 4x
SAFE = 132          # safe-zone radius (66dp / 2) around the center
FONT = r"C:\Projects\26PDAndroid\app\src\main\res\font\pd98_latin.otf"
OUT = r"C:\Projects\26PDAndroid\app\src\main\res\drawable"

CHROME = (0x00, 0x00, 0xA8)
UNDER = (0x54, 0x54, 0xFC)
INK = (0xFC, 0xFC, 0xFC)
BLACK = (0x04, 0x04, 0x0C)
RUN = (0x54, 0xFC, 0x54)
RED = (0xFC, 0x54, 0x54)
CYAN = (0x54, 0xFC, 0xFC)

# ---- background: chrome blue + big play glyph + scanlines -------------------
bg = Image.new("RGB", (SIZE, SIZE), CHROME)
bd = ImageDraw.Draw(bg, "RGBA")
cx = cy = SIZE // 2
half_h = 160                         # oversized backdrop triangle (user: wider)
nose = 260
left = cx - nose * 0.46              # triangle's vertical left edge (unchanged)
# the blue strip left of the triangle turns green too (user) — the triangle
# itself keeps its exact angle and size
bd.rectangle([0, cy - half_h, left, cy + half_h], fill=RUN + (255,))
bd.polygon(
    [(left, cy - half_h), (cx + nose * 0.54, cy), (left, cy + half_h)],
    fill=RUN + (255,),
)
for y in range(0, SIZE, 8):          # 2dp black @25%, 2dp clear — over the glyph
    bd.rectangle([0, y, SIZE, y + 3], fill=(0, 0, 0, 64))
bg.save(os.path.join(OUT, "ic_launcher_background.png"))

# ---- foreground: PD wordmark -------------------------------------------------
S = 4                                 # supersample for crisp pixel edges
W = SIZE * S
fg = Image.new("RGBA", (W, W), (0, 0, 0, 0))
fd = ImageDraw.Draw(fg)

text = "PD"
# F5.6 cap height ≈ 0.87em; with the triangle gone the lockup (PD + underline)
# fits the safe circle at a much larger size — the underline corner binds at
# em ≈ 129. 106 leaves margin and keeps a comfortable optical weight.
cap_h = 106 * S
size = int(cap_h / 0.87)
font = ImageFont.truetype(FONT, size)
tbox = fd.textbbox((0, 0), text, font=font)
tw, th = tbox[2] - tbox[0], tbox[3] - tbox[1]
ox = (W - tw) // 2 - tbox[0]
oy = (W - th) // 2 - tbox[1]

def draw_text(dx, dy, fill):
    fd.text((ox + dx, oy + dy), text, font=font, fill=fill)

# chroma ghosts (mockup 02: red -2,0 / cyan +2,+2 at 26px → scaled)
gs = size * 2 // 26
draw_text(-2 * gs, 0, RED + (140,))
draw_text(+2 * gs, +2 * gs, CYAN + (140,))
# hard black outline, 8 directions (mockup paint-order stroke)
r = 4 * S
for ddx in (-r, 0, r):
    for ddy in (-r, 0, r):
        if ddx or ddy:
            draw_text(ddx, ddy, BLACK + (255,))
draw_text(0, 0, INK + (255,))

# bright-blue underline under the word (title-bar signature)
uy = oy + th + int(size * 0.18)
fd.rectangle([ox + tbox[0], uy, ox + tbox[2], uy + 2 * S], fill=UNDER + (255,))

fg = fg.resize((SIZE, SIZE), Image.LANCZOS)

# sanity: all ink inside the adaptive safe zone
bbox = fg.split()[3].getbbox()
corner = max(
    abs(bbox[0] - SIZE / 2), abs(bbox[1] - SIZE / 2),
    abs(bbox[2] - SIZE / 2), abs(bbox[3] - SIZE / 2),
)
assert corner <= SAFE, "foreground exceeds safe zone: %s (%.0f)" % (bbox, corner)

fg.save(os.path.join(OUT, "ic_launcher_foreground.png"))
print("icon written; ink bbox:", bbox, "corner radius used:", round(corner))
